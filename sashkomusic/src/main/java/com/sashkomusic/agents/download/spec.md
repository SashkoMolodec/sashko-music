# DownloadAgentService — Spec

## Purpose
Ініціювати завантаження музики. **Не LLM** — детермінована обгортка над
`MusicDownloadFlowService`. Існує щоб MainAgent мав typed contract,
і щоб транспорт (in-process зараз, A2A HTTP потім) можна було змінити
без торкання MainAgentTools.

---

## Місце в потоці

```
MainAgentTools.downloadMusic()
  └─ DownloadAgentService.handle(DownloadRequest)
       ├─ якщо releaseId → musicDownloadFlowService.handleDownload(ctx, "DL:" + releaseId)
       ├─ якщо artist+album → musicDownloadFlowService.getDownloadOptions(ctx, query)
       └─ responses → accumulator.pushAll(conversationId, responses)
            └─ return DownloadResult.started(summary)

пізніше (async):
SlskdWebhookController / BandcampMonitor / ...
  └─ publishes DownloadBatchCompleteEvent(conversationId, ...)
       └─ DownloadBatchCompleteListener
            └─ chatBot.sendMessage(ConversationContext.from(dto.conversationId()), msg)
                 └─ автоматично → правильний топік
```

---

## Contract

### Input: `DownloadRequest`
```java
record DownloadRequest(String conversationId, String releaseId, String artist, String album, DownloadEngine engine)
```

| Поле            | Коли заповнений                                                        |
|-----------------|------------------------------------------------------------------------|
| `conversationId`| Завжди. `"chatId"` або `"chatId:topicId"`                              |
| `releaseId`     | Якщо завантажуємо щойно знайдений реліз (є в `SearchContextService`)   |
| `artist+album`  | Якщо користувач написав "скачай X Y" без попереднього пошуку           |
| `engine`        | Зазвичай `null` — вибір движка на стороні FlowService                  |

Рівно одне з: `releaseId` або `(artist, album)` непорожнє.

### Output: `DownloadResult`
```java
record DownloadResult(boolean success, String summary)
```

`summary` → коротке повідомлення для LLM (`"почав качати"`, `"нема що качати"`).
BotResponse-и вже в accumulator — LLM їх не бачить.

---

## Джерела завантаження (стратегії)

```
Map<DownloadEngine, DownloadFlowHandler>  ← injected by Spring
  ├─ QOBUZ         → QobuzDownloadFlowHandler        (лосслес, API)
  ├─ BANDCAMP      → BandcampDownloadFlowHandler
  ├─ SOULSEEK      → SoulseekDownloadFlowHandler      (P2P)
  ├─ APPLE_MUSIC   → AppleMusicDownloadFlowHandler
  └─ YOUTUBE_MUSIC → YouTubeMusicDownloadFlowHandler  (yt-dlp, AAC)
```

Вибір движка за замовчуванням — в `MusicDownloadFlowService.initiateDefaultDownloadSearch()`.
Альтернативні джерела обираються через `SEARCH_ALT:` callback → `CallbackDispatcher`
(не через цей агент).

---

## Async event flow

Це **fire-and-forget**: `handle()` повертається одразу після публікації event.
Реальний результат приходить через ланцюг:

```
FilesSearchTaskEvent → downloadagent → пошук файлів
  └─ FileSearchResultEvent → mainagent SearchFilesResultListener
       └─ MusicDownloadFlowService.handleSearchResults()
            └─ якщо autoDownload → FilesDownloadTaskEvent → downloadagent
                 └─ (Slskd / Bandcamp / ...) завантажує
                      └─ DownloadBatchCompleteEvent(conversationId, releaseId, directoryPath, allFiles)
                           └─ DownloadBatchCompleteListener
                                └─ ProcessLibraryTaskEvent → libraryagent
                                     └─ LibraryProcessingCompleteEvent
                                          └─ LibraryProcessingCompleteListener → повідомлення користувачу
```

Всі DTO несуть `String conversationId` — так повідомлення завжди потраплять
у правильний топік групи навіть після async виконання.

---

## Hard rules
1. **No LLM.** Логіка вибору движка, fallback, перевірка якості — в `MusicDownloadFlowService` і handlers.
2. Ніколи не конструювати `BotResponse` тут — тільки `pushAll` що повернув FlowService.
3. Stable contract: `DownloadRequest` і `DownloadResult` — чисті JSON records, без Spring типів.
4. `handle()` завжди повертається швидко — не блокується на результат завантаження.

---

## Out of scope
- Запис файлів на диск → `downloadagent`
- Progress UI → event listeners + accumulator  
- Тегування після завантаження → `libraryagent`
- Вибір альтернативного источника → `SEARCH_ALT:` callback

---

## SDD checkpoints (змінювати spec ДО коду)
- Нове джерело → `DownloadEngine` enum + `DownloadFlowHandler` impl + оновити таблицю вище.
  Також оновити `mainagent/download/spec.md` (кнопки alternate source).
- Хочеш дати LLM вибір між движками → додати `engine` параметр в `MainAgentTools.downloadMusic`.
- Зміна event chain → оновити діаграму "Async event flow" тут.
