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

| Holder | flow_key | Package |
|---|---|---|
| `SearchContextService` | `search` | mainagent/search |
| `DownloadContextHolder` | `dl_ctx` | mainagent/download |
| `SoulseekDirectoryConfirmContextHolder` | `slsk_dir_confirm` | mainagent/download |
| `SoulseekCustomSearchFlowService` | `slsk_custom` | mainagent/download |
| `DjTagContextHolder` | `dj_tag` | mainagent/library |
| `AlbumCommentContextHolder` | `np_album_comment` | mainagent/library |
| `LastReleaseContextHolder` | `last_release` | mainagent/library |
| `TrackRemovalContextHolder` | `track_removal_select` | mainagent/library |
| `SmartlistLabelContextHolder` | `sml_label` | mainagent/library |
| `MarkersFlowService` | `markers_creating` / `markers_removing` | mainagent/library |
| `SmartlistDraft (SmartlistCreationFlowService)` | `smartlist_create` | libraryagent/domain/smartlist |
| `ProcessFolderContextHolder` | `proc_sel` | mainagent/process |
| `PendingProcessContextHolder` | `pending_process` | mainagent/process |
| `StreamingFlowService` | `listen_msg` | mainagent/streaming |

Усі — `ChatStateStore` (Postgres, `chat_state`), переживають рестарт. Константа `FLOW_KEY` у класі — джерело правди.

---

## SearchContextService (flow_key: "search")

### Payload
```java
SearchState {
  SearchContext context {              // активний (останній) стос
    SearchEngine source,
    MetadataSearchRequest request,
    String rawInput,
    List<String> releaseIds,           // порядок результатів
    int currentPage
  },
  List<ReleaseMetadata> releases,      // активний стос, full objects
  List<SearchStack> stacks {           // усі стоси поточного ходу
    String id,                         // "s1", "s2" — їде в CARD:-колбеку
    String label,                      // запит, що побудував стос
    String group,                      // чому стос обрано (findSimilar); null, якщо групи нема; читає лише вступ
    List<ReleaseMetadata> releases,
    int currentPage
  }
}
```

`stacks` з'явився разом із мульти-стосовими відповідями (рекомендація = стос на кожен реліз). У payload'ах,
записаних раніше, поля немає → compact constructor нормалізує в порожній список, тому старі сесії
десеріалізуються без помилки.

### In-memory cache
`Map<String releaseId, ReleaseMetadata>` — прискорює lookup. Перебудовується з DB при промаху через `loadContext(conversationId)`.

### Lazy reload after restart
`getReleaseMetadata(releaseId, conversationId)` — якщо кеш порожній, завантажує SearchState з DB → заповнює кеш → повертає метадані. Без цього `DL:` кнопки ламались би після рестарту JVM.

### Lookup across stacks
`getReleaseMetadata(releaseId, conversationId)` шукає і в активному стосі, і в усіх `stacks` — юзер тисне
⬇️/🎧 на картці будь-якого зі стосів, у будь-якому порядку.

### Межа ходу
Прямий пошук (`searchDefault`) перед своїм стосом викликає `beginStacks(conversationId)`: чистить `stacks`, але
**лишає** активний стос — кнопки на вже відправлених картках мають працювати й після наступного запиту.
Агентний хід стоси не скидає під час роботи тулів: після ходу `MainAgentService` викликає `retainStacks` (вдався —
лишаються тільки стоси ходу) або `discardStacks` (впав — його стоси прибираються, старі живі). Новий id — `s<max+1>`
серед живих стосів, тож після `retainStacks` два стоси не отримають однаковий id.
`rememberQuery()` записує
запит без результатів (порожній точковий пошук), щоб ⛏️ мав що розширювати.

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

## ProcessFolderContextHolder (flow_key: "proc_sel")

```java
ProcessFolderContext {
  String folderPath,
  List<AudioFile> audioFiles,
  List<ReleaseMetadata> searchResults   // [mb_result, discogs_result, bandcamp_result]
}
```

Активний під час `ProcessFolderSelectionOngoingFlow`. Очищується після вибору метаданих.

---

## DownloadContextHolder (flow_key: "dl_ctx")

Обраний реліз (`chosenReleaseId`) і `OptionReport`-и по кожному releaseId. Персистується через `ChatStateStore`,
тож варіанти завантаження переживають рестарт. Чиститься на `ChatHardResetEvent` ("стоп").

---

## LLM Memory (окремо від ChatStateStore)

`PostgresChatMemoryStore` — зберігає LLM conversation history.

| Agent | Table | Window | Memory ID |
|-------|-------|--------|-----------|
| MainAgent | `conversation_messages` | 32 msgs (+ `compact` перед кожним ходом) | `conversationId` |
| LibraryAgent | `conversation_messages` | 16 msgs | `conversationId + ":lib"` |

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
  └─ LLM memory.clear(conversationId)         // MainAgent + LibraryAgent (":lib")
```
