# Spring Application Events

Всі міжпакетні async комунікації через Spring `ApplicationEventPublisher` + `@EventListener @Async("asyncExecutor")`.

**Немає `*Producer`-бінів.** Publisher інжектить `ApplicationEventPublisher` і викликає
`publishEvent(new SomeEvent(...))` прямо з місця, де подія народжується.

**Події пласкі.** Поля лежать прямо на record'і події, без обгортки `XxxDto payload`.
Виняток — чотири події, чий payload споживає доменний сервіс, а не лише слухач: тоді payload
живе в `shared/task/` як самостійний контракт (див. нижче).

Події, адресовані чату, реалізують `com.sashkomusic.shared.ConversationScoped` і отримують
`chatId()` за замовчуванням (числовий префікс `conversationId` до `:`).

---

## Event map

```
Publisher            Event                          Listener (package)
──────────────────────────────────────────────────────────────────────────────
mainagent       →  FilesSearchTaskEvent          →  downloadagent
downloadagent   →  FileSearchResultEvent         →  mainagent
mainagent       →  FilesDownloadTaskEvent        →  downloadagent
downloadagent   →  DownloadCompleteEvent         →  mainagent
downloadagent   →  DownloadBatchCompleteEvent    →  mainagent
downloadagent   →  DownloadErrorEvent            →  mainagent

mainagent       →  ProcessLibraryTaskEvent       →  libraryagent
libraryagent    →  LibraryProcessingCompleteEvent→  mainagent
mainagent       →  ReprocessReleaseTaskEvent     →  libraryagent
libraryagent    →  ReprocessReleaseCompleteEvent →  mainagent

mainagent       →  RateTrackTaskEvent            →  libraryagent
mainagent       →  SetEnergyTaskEvent            →  libraryagent
mainagent       →  SetFunctionTaskEvent          →  libraryagent
mainagent       →  AddCommentTaskEvent           →  libraryagent
mainagent       →  ReplaceCommentTaskEvent       →  libraryagent
libraryagent    →  TrackUpdateResultEvent        →  mainagent
libraryagent    →  TagChangesNotificationEvent   →  mainagent

Python REST     →  POST /internal/audio-analysis-complete
                   → TrackAnalysisCompleteEvent  →  libraryagent
```

Повний перелік (включно з move/remove/smartlist/Apple Music) — таблиця Spring Event Map у `CLAUDE.md`.

---

## Download events

### `FilesSearchTaskEvent`
mainagent → downloadagent. Ініціює пошук. Payload — спільний контракт, бо його читає
`AcquisitionService` (домен downloadagent).
```java
FilesSearchTaskEvent(SearchFilesTask payload)

shared.task.SearchFilesTask {
  String conversationId, String releaseId,
  String artist, String title, DownloadEngine source
}
```

### `FileSearchResultEvent`
downloadagent → mainagent. Результати пошуку.
```java
FileSearchResultEvent(String conversationId, String releaseId,
                      DownloadEngine source, List<DownloadOption> results)
```

### `FilesDownloadTaskEvent`
mainagent → downloadagent. Юзер вибрав варіант, починаємо качати. Payload — спільний контракт,
бо його читає `DownloadService` (домен downloadagent).
```java
FilesDownloadTaskEvent(DownloadFilesTask payload)

shared.task.DownloadFilesTask {
  String conversationId, String releaseId, DownloadOption downloadOption
}
```

### `DownloadCompleteEvent`
downloadagent → mainagent. Один файл завантажено (Soulseek per-file, з webhook).
```java
DownloadCompleteEvent(String conversationId, String filename, long sizeMB)
// DownloadCompleteEvent.of(conversationId, filename, sizeBytes) конвертує байти → MB
```

### `DownloadBatchCompleteEvent`
downloadagent → mainagent. Весь batch завантажено.
```java
DownloadBatchCompleteEvent(String conversationId, String releaseId,
                           String directoryPath, List<String> allFiles)
// totalFiles() — похідний від allFiles.size(), окремо не зберігається
```
Тригерить `ProcessFolderFlowService.process(...)` → авто-обробка після завантаження.

### `DownloadErrorEvent`
downloadagent → mainagent. Помилка завантаження.
```java
DownloadErrorEvent(String conversationId, String errorMessage)
```
`DownloadErrorListener` показує: `"🤡 не получилосі скачати:\n{errorMessage}"`.

---

## Library processing events

### `ProcessLibraryTaskEvent`
mainagent → libraryagent. Payload — спільний контракт, бо його читають `LibraryProcessingService`
і `FileValidator` (домен libraryagent).
```java
ProcessLibraryTaskEvent(ProcessLibraryTask payload)

shared.task.ProcessLibraryTask {
  String conversationId, String directoryPath,
  List<String> downloadedFiles, ReleaseMetadata metadata
}
```

### `LibraryProcessingCompleteEvent`
libraryagent → mainagent.
```java
LibraryProcessingCompleteEvent(String conversationId, String masterId, String directoryPath,
                               List<ProcessedFile> processedFiles, boolean success,
                               String message, List<String> errors)
```

### `ReprocessReleaseTaskEvent`
mainagent → libraryagent. Для `/reprocess`.
```java
ReprocessReleaseTaskEvent(String conversationId, String directoryPath, ReleaseMetadata metadata,
                          int newMetadataVersion, ReprocessOptions options)
```

### `ReprocessReleaseCompleteEvent`
```java
ReprocessReleaseCompleteEvent(String conversationId, String directoryPath, boolean success,
                              String message, int filesProcessed, int errors)
```

---

## Tagging events

Усі п'ять task-подій обробляє один слухач — `libraryagent.messaging.consumer.TrackTagUpdateListener`
(спільний `apply(...)` + `RateTrackService`), і всі вони відповідають `TrackUpdateResultEvent`.

### `RateTrackTaskEvent`
```java
RateTrackTaskEvent(Long trackId, int rating, String conversationId)
```
rating: 1–5 (зберігається як WMP: rating * 20).

### `SetEnergyTaskEvent`
```java
SetEnergyTaskEvent(Long trackId, String energy, String conversationId)
```
energy: `E1`–`E5`.

### `SetFunctionTaskEvent`
```java
SetFunctionTaskEvent(Long trackId, String function, String conversationId)
```
function: `intro` | `tool` | `banger` | `closer`.

### `AddCommentTaskEvent` / `ReplaceCommentTaskEvent`
```java
AddCommentTaskEvent(Long trackId, String comment, String conversationId)
ReplaceCommentTaskEvent(Long trackId, String comment, String conversationId)
```
Різні дії, не різні поля: add дописує, replace перезаписує.

### `TrackUpdateResultEvent`
libraryagent → mainagent. Підтвердження збереження тегу.
```java
TrackUpdateResultEvent(Long trackId, String fieldUpdated, String value,
                       boolean success, String message, String conversationId)
```
`fieldUpdated`: `rating` | `energy` | `function` | `comment`.

### `TagChangesNotificationEvent`
libraryagent → mainagent. Diff тегів, коли зміни батчуються.
```java
TagChangesNotificationEvent(TagChangesNotification payload)

shared.task.TagChangesNotification {
  List<TrackChanges> tracks, int totalChanges, LocalDateTime timestamp
}
TrackChanges  { Long trackId, String trackTitle, String artistName, List<TagChangeInfo> changes }
TagChangeInfo { String tagName, String oldValue, String newValue, boolean isNew }
```
Не має `conversationId` — йде в лог-канал, не в конкретний чат.

---

## Audio analysis (REST, не Spring event)

**Java → Python** — `libraryagent.client.AudioAnalyzerClient`, fire-and-forget (WebClient):
```
POST {AUDIO_ANALYZER_URL}/analyze
{ "trackId": 42, "localPath": "/library/Burial/Untrue/01 - Archangel.flac",
  "releaseId": 7, "releaseTitle": "Untrue", "trackTitle": "Archangel" }
```

**Python → Java** — `libraryagent.api.AudioAnalyzerCallbackController`:
```
POST /internal/audio-analysis-complete
{ "trackId": 42, "jsonResultPath": "/analysis/42.json", "success": true, "errorMessage": null }
```
→ `TrackAnalysisCompleteEvent` → `TrackAnalysisCompleteListener` → upsert `track_analysis`.

Імена полів у `AnalyzeTrackRequest` / `TrackAnalysisCompleteRequest` — це дротовий контракт з
Python. Перейменування поля ламає інтеграцію.

---

## Rules for all events

1. Всі `@EventListener` методи — `@Async("asyncExecutor")`, щоб не блокувати publisher.
   Виняток: `ChatContextClearedEvent` / `ChatHardResetEvent` синхронні навмисно (відповідь
   оркестратора чекає на завершення очистки).
2. Listener ідемпотентний — перевіряє стан перед дією.
3. Подія несе `conversationId` і реалізує `ConversationScoped` — async відповіді потрапляють
   у правильний Telegram topic.
4. Нова подія → новий плаский record в `com.sashkomusic.events` + рядок у цьому файлі
   + рядок у Spring Event Map в `CLAUDE.md`. Продюсер-бін не створювати.
