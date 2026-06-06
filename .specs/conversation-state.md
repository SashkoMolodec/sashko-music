# Conversation State & Persistence

## ChatStateStore

Єдиний інтерфейс для персистентного per-chat стану. Impl: `JpaChatStateStore` (Postgres).

```java
<T> Optional<T> get(long chatId, String flowKey, Class<T> type);
       void     put(long chatId, String flowKey, Object payload);
       void     remove(long chatId, String flowKey);
       int      clearAll(String flowKey);
```

Насправді `chatId` = рядок `conversationId` (`"chatId"` або `"chatId:topicId"`).

### DB table
```sql
chat_state (
  chat_id    BIGINT,
  flow_key   TEXT,
  payload    JSONB,
  updated_at TIMESTAMPTZ,
  PRIMARY KEY(chat_id, flow_key)
)
```

---

## Context Holders

| Holder | flow_key | Persisted | Package |
|--------|----------|-----------|---------|
| `SearchContextService` | `"search"` | ✅ PostgreSQL | mainagent/search |
| `DjTagContextHolder` | `"dj_tag"` | ✅ PostgreSQL | mainagent/library |
| `ProcessFolderContextHolder` | `"process"` | ✅ PostgreSQL | mainagent/process |
| `DownloadContextHolder` | — | ❌ in-memory only | mainagent/download |

---

## SearchContextService (flow_key: "search")

### Payload
```java
SearchState {
  SearchContext context {
    SearchEngine source,
    MetadataSearchRequest request,
    String rawInput,
    List<String> releaseIds   // порядок результатів
  },
  List<ReleaseMetadata> releases  // full objects
}
```

### In-memory cache
`Map<String releaseId, ReleaseMetadata>` — прискорює lookup. Перебудовується з DB при промаху через `loadContext(conversationId)`.

### Lazy reload after restart
`getReleaseMetadata(releaseId, conversationId)` — якщо кеш порожній, завантажує SearchState з DB → заповнює кеш → повертає метадані. Без цього `DL:` кнопки ламались би після рестарту JVM.

### Merge strategy
`saveSearchContext` зливає нові результати з існуючими (deduplicate by releaseId, нові перезаписують). Це дозволяє `DIG_DEEPER` акумулювати результати з різних джерел.

---

## DjTagContextHolder (flow_key: "dj_tag")

```java
DjTagContext {
  TrackInfo track {
    String trackId,
    String title,
    String artist,
    String album
  },
  boolean waitingForComment
}
```

Встановлюється при `/np`. `waitingForComment = true` активує `CommentInputOngoingFlow`.

---

## ProcessFolderContextHolder (flow_key: "process")

```java
ProcessFolderContext {
  String folderPath,
  List<AudioFile> audioFiles,
  List<ReleaseMetadata> searchResults   // [mb_result, discogs_result, bandcamp_result]
}
```

Активний під час `ProcessFolderSelectionOngoingFlow`. Очищується після вибору метаданих.

---

## DownloadContextHolder (in-memory, не persisted)

```java
Map<String conversationId, DownloadContext {
  String chosenReleaseId,
  List<OptionReport> optionReports
}>
```

Активний під час `DownloadOptionSelectionOngoingFlow`. Втрачається при рестарті → `"😔 варіанти пропали, нич нема, давай ше раз"`.

**Рішення: мігрувати на ChatStateStore з flow_key `"download"` коли знадобиться.**

---

## LLM Memory (окремо від ChatStateStore)

`PostgresChatMemoryStore` — зберігає LLM conversation history.

| Agent | Table | Window | Memory ID |
|-------|-------|--------|-----------|
| MainAgent | `ai_message` | 32 msgs | `conversationId` |
| DiscoveryAgent | `ai_message` | 16 msgs | `conversationId + ":d"` |

---

## ConversationContext

Ідентифікує розмову:

```java
record ConversationContext(String conversationId) {
  long chatId()   // "chatId:topicId" → chatId; "chatId" → chatId
  Long topicId()  // "chatId:topicId" → topicId; "chatId" → null
}
```

Telegram group topics отримують `topicId`, DMs — тільки `chatId`.
`conversationId` передається через всі event DTOs щоб async відповіді потрапляли в правильний topic.

---

## /newtopic command

```
/newtopic [optional name]
  └─ NewTopicFlowService.handle(ctx, name?)
       ├─ Telegram API: createForumTopic(name) → новий topicId
       ├─ SearchContextService.copySearchContext(fromId=chatId, toId=chatId:topicId)
       │    └─ переносить поточний search context в новий топік
       ├─ ChatMemoryStore.copy(chatId, chatId:topicId)   // LLM memory
       └─ ChatStateStore.clearAll("search")              // очищує батьківський контекст
```

Дозволяє продовжити розмову про знайдений реліз в новому topic.

---

## стоп keyword

```
стоп → UserInteractionOrchestrator.clearAllCaches(ctx)
  ├─ DownloadContextHolder.clearAllSessions()
  ├─ ProcessFolderContextHolder.clearAll()
  ├─ DjTagContextHolder.clear(conversationId)
  ├─ SearchContextService.clearAllCaches()     // в-пам'яті кеш + DB для conversationId
  └─ LLM memory.clear(conversationId)         // MainAgent + DiscoveryAgent
```
