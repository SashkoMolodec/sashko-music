# Agent Architecture

## Огляд

```
Telegram Update
  └─ TelegramChatBot.consume()
       ├─ buildContext(chatId, messageThreadId) → ConversationContext
       │    ├─ DM / General:  ConversationContext(chatId, null)    conversationId = "chatId"
       │    └─ Group Topic:   ConversationContext(chatId, topicId) conversationId = "chatId:topicId"
       │
       └─ UserInteractionOrchestrator.handleUserRequest(ctx, text)
            ├─ "стоп"                    → clearAllCaches(ctx)
            ├─ OngoingFlow.appliesTo(ctx) → OngoingFlow.handle(ctx, text)
            ├─ /np /process /reprocess /newtopic → FlowService
            └─ runMainAgent(ctx, text)
                 ├─ accumulator.begin(conversationId)
                 ├─ MainAgent.chat(conversationId, text)
                 │    └─ [tool calls]
                 │         ├─ findMusic    → DiscoveryAgentService
                 │         ├─ downloadMusic → DownloadAgentService
                 │         ├─ discussRelease → SearchContextService (read-only)
                 │         └─ manageLibrary → LibraryAgentService
                 ├─ accumulator.drain(conversationId)
                 └─ chatBot.sendResponse(ctx, each response)
```

---

## ConversationContext

```java
record ConversationContext(long chatId, @Nullable Integer topicId) {
    String conversationId();  // "chatId" або "chatId:topicId"
    boolean isGroupTopic();
    static ConversationContext dm(long chatId);
    static ConversationContext topic(long chatId, int topicId);
    static ConversationContext from(String conversationId); // parse з рядка
}
```

`conversationId` — єдиний ключ для:
- `conversation_messages` (LangChain4j memory)
- `chat_state` (per-flow session state)
- `SearchContextService` (in-memory search results)
- Всіх async event DTOs

---

## Пам'ять агентів

### PostgresChatMemoryStore
Реалізує `dev.langchain4j.store.memory.chat.ChatMemoryStore`.

```
conversation_messages
  conversation_id TEXT PK
  messages        JSONB     ← ChatMessageSerializer.messagesToJson()
  updated_at      TIMESTAMPTZ
```

| Agent         | Memory ID               | maxMessages | Приклад ключа            |
|---------------|-------------------------|-------------|--------------------------|
| MainAgent     | `conversationId`        | 32          | `"-1003551198668"`       |
| DiscoveryAgent| `conversationId + ":d"` | 16          | `"-1003551198668:d"`     |

**Чому різні ID:** якби обидва агенти використовували однаковий ключ, то після
`tool_use` від MainAgent наступний виклик DiscoveryAgent дописував би `SYSTEM+USER`
одразу після незакритого `tool_use` — Claude API відхиляє таку послідовність.

**Window semantics:** `MessageWindowChatMemory` автоматично витискає найстаріші
повідомлення коли вікно переповнюється. Кожен хід розмови:
- Без tool: 2 messages (USER + AI)
- З tool:   4 messages (USER + AI(tool_use) + TOOL_RESULT + AI(text))

**Очищення (команда "стоп"):** `UserInteractionOrchestrator.clearAllCaches(ctx)` видаляє
обидва memory IDs: `conversationId` і `conversationId + ":d"`.

---

## Bridge: ChatResponseAccumulator

In-memory буфер `ConcurrentHashMap<String, List<BotResponse>>`.
Ключ — `conversationId`. Дизайн: один `chat()` call за раз на conversationId.

```
begin(id)    — очистити stale стан з попереднього call
push(id, r)  — додати один BotResponse
pushAll(id)  — додати список
drain(id)    — забрати всі і очистити буфер
```

Sub-агенти push-ають в accumulator під час виконання tool.
Після `chat()` orchestrator drain-ає і надсилає все в Telegram.

### ProgressNotifier
Обходить accumulator — надсилає через `TelegramClient` напряму.
Використовується для `"🔍 шукаю..."` до початку tool execution.

---

## Async Events

Для операцій що не повертають результат синхронно (завантаження, теги):

```
Publisher                Event                          Listener (mainagent)
─────────────────────────────────────────────────────────────────────────────
mainagent     → FilesSearchTaskEvent      → downloadagent
downloadagent → FileSearchResultEvent     → SearchFilesResultListener
mainagent     → FilesDownloadTaskEvent    → downloadagent
downloadagent → DownloadCompleteEvent     → DownloadCompleteListener
downloadagent → DownloadBatchCompleteEvent→ DownloadBatchCompleteListener
downloadagent → DownloadErrorEvent        → DownloadErrorListener
mainagent     → ProcessLibraryTaskEvent   → libraryagent
libraryagent  → LibraryProcessingCompleteEvent → LibraryProcessingCompleteListener
mainagent     → ReprocessReleaseTaskEvent → libraryagent
libraryagent  → ReprocessReleaseCompleteEvent  → ReprocessReleaseResultListener
mainagent     → RateTrackTaskEvent        → libraryagent
mainagent     → SetEnergyTaskEvent        → libraryagent
mainagent     → SetFunctionTaskEvent      → libraryagent
mainagent     → AddCommentTaskEvent       → libraryagent
libraryagent  → TrackUpdateResultEvent    → TrackUpdateResultListener
libraryagent  → TagChangesNotificationEvent → TagChangesNotificationListener
```

**Правило:** всі DTO що несуть "куди відповісти" використовують `String conversationId`.
Listener реконструює: `ConversationContext.from(dto.conversationId())` → правильний топік.

Всі `@EventListener` ПОВИННІ бути `@Async` — не блокувати publisher thread.

---

## Session State (ChatStateStore)

```
chat_state
  conversation_id TEXT
  flow_key        TEXT
  payload         JSONB
  PRIMARY KEY (conversation_id, flow_key)
```

| flow_key   | Holder                | Що зберігає                      |
|------------|-----------------------|----------------------------------|
| `dj_tag`   | `DjTagContextHolder`  | trackId + waitingForComment flag |
| `download` | `DownloadContextHolder`| options per releaseId            |
| `search`   | `SearchContextService`| *(in-memory поки що)*            |
| `process`  | `ProcessFolderContextHolder` | pending folder selection  |

Персистується через `JpaChatStateStore` (Postgres). Виживає після рестарту.

---

## Topics (Telegram Forum Mode)

```
conversation_topics
  chat_id     BIGINT
  topic_id    INTEGER
  name        TEXT
  created_at  TIMESTAMPTZ
  PRIMARY KEY (chat_id, topic_id)
```

При `/newtopic [name]`:
1. `CreateForumTopic` API → `messageThreadId`
2. Зберегти в `conversation_topics`
3. Скопіювати history з source до нового `conversationId` (тільки USER+AI без tool calls)
4. Очистити source memory (щоб наступний `/newtopic` не змішував контексти)
5. Надіслати AI-generated summary в новий топік
6. Відповісти в поточному: `"✅ створив «name» — продовжуй там"`

---

## Rendering: TelegramHtmlFormatter

Всі повідомлення проходять через `TelegramHtmlFormatter.format()` перед надсиланням.
Конвертує standard markdown (який генерує Claude) у Telegram HTML:

| Input        | Output              |
|--------------|---------------------|
| `**bold**`   | `<b>bold</b>`       |
| `_italic_`   | `<i>italic</i>`     |
| `` `code` `` | `<code>code</code>` |
| ` ```...``` `| `<pre>...</pre>`    |
| `<`, `>`, `&`| `&lt;`, `&gt;`, `&amp;` |

`parseMode("HTML")` у всіх `SendMessage` / `SendPhoto`.
Fallback при помилці: plain text без parseMode.
