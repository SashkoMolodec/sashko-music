# Agent Architecture

## Огляд

```
Telegram Update
  └─ TelegramChatBot.consume(List<Update>)   ← polling thread: routes only
       ├─ ConversationLanes: MESSAGES lane (text, photo) / CALLBACKS lane, serial per conversation,
       │  parallel across conversations and lanes (CLAUDE.md → Telegram Entry Layer)
       ├─ buildContext(chatId, messageThreadId) → ConversationContext
       │    ├─ DM / General:  ConversationContext(chatId, null)    conversationId = "chatId"
       │    └─ Group Topic:   ConversationContext(chatId, topicId) conversationId = "chatId:topicId"
       │
       └─ UserInteractionOrchestrator.handleUserRequest(ctx, text)
            ├─ "стоп"                    → clearAllCaches(ctx)
            ├─ OngoingFlow.appliesTo(ctx) → OngoingFlow.handle(ctx, text)
            ├─ /np /process /reprocess /newtopic → FlowService
            └─ runMainAgent(ctx, text) → MainAgentService.chat(ctx, text)
                 ├─ turn = new AgentTurn(ctx, progress → ProgressNotifier)
                 ├─ MainAgent.chat(conversationId, text, turn.parameters())     ← Sonnet
                 │    └─ [tool calls, each gets the turn via InvocationParameters]
                 │         ├─ search / exploreAndRecommend / findSimilar / digDeeper / getTrackList → DiscoveryTools
                 │         ├─ researchMusic → MusicResearcher (Haiku + web_search, no memory)
                 │         ├─ downloadMusic → MusicDownloadFlowService (no LLM)
                 │         └─ manageLibrary → LibraryAgentService → LibraryAgent (Haiku, memory ":lib")
                 ├─ reply = [aiText] + turn.shown() + one message per turn.openedStacks()
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

| Agent         | Memory ID                 | maxMessages | Приклад ключа            |
|---------------|---------------------------|-------------|--------------------------|
| MainAgent     | `conversationId`          | 32          | `"-1003551198668"`       |
| LibraryAgent  | `conversationId + ":lib"` | 16          | `"-1003551198668:lib"`   |

**Чому різні ID:** LibraryAgent викликається зсередини тула MainAgent, тобто поки `tool_use`
MainAgent ще не має відповіді. Спільний ключ дописав би `SYSTEM+USER` одразу після незакритого
`tool_use` — Claude API відхиляє таку послідовність. Ключ дає `LibraryAgentService.memoryId(...)`.
Колишні ключі `:d` (DiscoveryAgent) прибрані міграцією V21.

**Window semantics:** `MessageWindowChatMemory` автоматично витискає найстаріші
повідомлення коли вікно переповнюється. Кожен хід розмови:
- Без tool: 2 messages (USER + AI)
- З tool:   4 messages (USER + AI(tool_use) + TOOL_RESULT + AI(text))

**Очищення (`/clearctx`, "стоп"):** `ChatContextClearedEvent` → `MainChatMemoryProvider.clearAllForConversation`
видаляє обидва memory IDs: `conversationId` і `conversationId + ":lib"`.

---

## AgentTurn

Один хід вільного тексту — локальний об'єкт, не singleton-буфер. `MainAgentService` створює його на
повідомлення і передає в `MainAgent.chat` як `InvocationParameters`; LangChain4j прокидає їх у кожен тул
з параметром `InvocationParameters` (в JSON-схему тула він не потрапляє), а `manageLibrary` передає той самий
об'єкт у `LibraryAgent`.

```
AgentTurn.of(parameters)   — хід, у якому працює тул (поза ходом → IllegalStateException)
turn.context()             — куди відповідати (замість @ToolMemoryId і розбору суфіксів)
turn.show(responses)       — картки / підтвердження / прев'ю, повз LLM
turn.progress(text)        — "🔍 шукаю..." напряму в Telegram, кожен текст раз на хід
turn.claimStackReset()     — перший стос ходу скидає стоси попередньої відповіді
turn.stackOpened(stack)    — стос, який MainAgentService покаже після відповіді
```

Хід, що впав, забирає свої картки з собою — нема що чистити і нема "stale" стану для наступного ходу.

### ProgressNotifier
Надсилає через `TelegramClient` напряму; `AgentTurn.progress` — єдиний його споживач в агентах.

---

## Async Events

Для операцій що не повертають результат синхронно (завантаження, теги) — повний список у CLAUDE.md, Spring Event Map:

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
| `search`   | `SearchContextService`| результати, стоси, поточна сторінка |
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
