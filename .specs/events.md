# Spring Application Events

Всі міжпакетні async комунікації через Spring `ApplicationEventPublisher` + `@EventListener @Async`.

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
mainagent       →  DownloadCancelTaskEvent       →  downloadagent

mainagent       →  ProcessLibraryTaskEvent       →  libraryagent
libraryagent    →  LibraryProcessingCompleteEvent→  mainagent
mainagent       →  ReprocessReleaseTaskEvent     →  libraryagent
libraryagent    →  ReprocessReleaseCompleteEvent →  mainagent

mainagent       →  RateTrackTaskEvent            →  libraryagent
mainagent       →  SetEnergyTaskEvent            →  libraryagent
mainagent       →  SetFunctionTaskEvent          →  libraryagent
mainagent       →  AddCommentTaskEvent           →  libraryagent
libraryagent    →  TrackUpdateResultEvent        →  mainagent
libraryagent    →  TagChangesNotificationEvent   →  mainagent

Python REST     →  POST /internal/audio-analysis-complete
                   → TrackAnalysisCompleteEvent  →  libraryagent
```

---

## Download events

### `FilesSearchTaskEvent`
mainagent → downloadagent. Ініціює пошук.
```java
payload: SearchFilesTaskDto {
  String conversationId,
  String releaseId,
  String artist,
  String title,
  DownloadEngine source
}
```

### `FileSearchResultEvent`
downloadagent → mainagent. Результати пошуку.
```java
payload: SearchFilesResultDto {
  String conversationId,
  String releaseId,
  DownloadEngine source,
  List<DownloadOption> results
}
```

### `FilesDownloadTaskEvent`
mainagent → downloadagent. Юзер вибрав варіант, починаємо качати.
```java
payload: DownloadFilesTaskDto {
  String conversationId,
  String releaseId,
  DownloadOption downloadOption
}
```

### `DownloadCompleteEvent`
downloadagent → mainagent. Один файл завантажено (Soulseek per-file).
```java
payload: DownloadCompleteDto { conversationId, releaseId, filename, localPath }
```

### `DownloadBatchCompleteEvent`
downloadagent → mainagent. Весь batch завантажено.
```java
payload: DownloadBatchCompleteDto {
  String conversationId,
  String releaseId,
  String directoryPath,
  List<String> allLocalFiles
}
```
Тригерить: `ProcessLibraryTaskEvent` → libraryagent (авто-обробка після завантаження).

### `DownloadErrorEvent`
downloadagent → mainagent. Помилка завантаження.
```java
payload: DownloadErrorDto { String conversationId, String errorMessage }
```
`DownloadErrorListener` показує: `"🤡 не получилосі скачати:\n{errorMessage}"`.

### `DownloadCancelTaskEvent`
mainagent → downloadagent. Юзер натиснув cancel.
```java
payload: DownloadCancelTaskDto { String conversationId, String releaseId }
```

---

## Library processing events

### `ProcessLibraryTaskEvent`
mainagent → libraryagent.
```java
payload: ProcessLibraryTaskDto {
  String conversationId,
  String releaseId,
  String folderPath,
  ReleaseMetadata metadata
}
```

### `LibraryProcessingCompleteEvent`
libraryagent → mainagent.
```java
payload: LibraryProcessingCompleteDto {
  String conversationId,
  String releaseId,
  boolean success,
  String message,         // "✅ оброблено 10 треків"
  String processedPath
}
```

### `ReprocessReleaseTaskEvent` / `ReprocessReleaseCompleteEvent`
Аналогічно, для `/reprocess` команди.

---

## Tagging events

### `RateTrackTaskEvent`
```java
payload: RateTrackTaskDto { String conversationId, String trackId, int rating }
```
rating: 1–5 (зберігається як WMP: rating * 20).

### `SetEnergyTaskEvent`
```java
payload: SetEnergyTaskDto { String conversationId, String trackId, int energy }
```
energy: 1–5.

### `SetFunctionTaskEvent`
```java
payload: SetFunctionTaskDto { String conversationId, String trackId, String function }
```
function: "intro" | "tool" | "banger" | "closer".

### `AddCommentTaskEvent`
```java
payload: AddCommentTaskDto { String conversationId, String trackId, String comment }
```

### `TrackUpdateResultEvent`
libraryagent → mainagent. Підтвердження збереження тегу.
```java
payload: TrackUpdateResultDto { String conversationId, boolean success, String summary }
```

### `TagChangesNotificationEvent`
libraryagent → mainagent. Diff тегів коли зміни батчуються.
```java
payload: TagChangesNotificationDto {
  String conversationId,
  String trackId,
  Map<String, String> oldTags,   // tagName → oldValue
  Map<String, String> newTags    // tagName → newValue
}
```

---

## Audio analysis (REST, не Spring event)

**Java → Python:**
```
POST {AUDIO_ANALYZER_URL}/analyze
{
  "filePath": "/library/Burial/Untrue/01 - Archangel.flac",
  "releaseId": "mb-xxxx",
  "conversationId": "-1003551198668"
}
```
Fire-and-forget (WebClient, non-blocking).

**Python → Java:**
```
POST /internal/audio-analysis-complete
{
  "releaseId": "mb-xxxx",
  "conversationId": "-1003551198668",
  "results": [{ "filePath": "...", "bpm": 138.5, "loudness": -8.2, "danceability": 0.7 }]
}
```
→ `TrackAnalysisCompleteEvent` → `TrackAnalysisCompleteListener` → upsert `track_analysis`.

---

## Rules for all events

1. Всі `@EventListener` методи **обов'язково** `@Async` — не блокують publisher
2. Listener ідемпотентний — перевіряє стан перед дією
3. Payload несе `conversationId` (рядок) — async відповіді потрапляють в правильний Telegram topic
4. Нова подія → новий record-клас в `com.sashkomusic.events` + рядок в цьому файлі
