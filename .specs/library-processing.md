# Library Processing Feature

## Purpose
Обробка папок з музикою: розпізнати реліз через LLM, знайти метадані в 3 джерелах, вибрати правильні теги, перейменувати і організувати файли, записати в БД, відправити на аудіо-аналіз.

---

## /process command

### Flow
```
User: /process /music/downloads/Burial - Untrue
  └─ ProcessFolderFlowService.handleProcessCommand(ctx, "/music/downloads/Burial - Untrue")
       ├─ FolderAudioScanner.scan(path) → List<AudioFile>
       │    └─ якщо порожня папка → "😕 нема аудіо файлів"
       ├─ ReleaseIdentifierService.identify(folderName)
       │    └─ FolderNameParser (LangChain4j, Haiku LLM)
       │         └─ "Burial - Untrue" → MetadataSearchRequest {artist:"Burial", release:"Untrue"}
       ├─ ProcessFolderSearcher.search(request)
       │    ├─ MusicBrainzClient.search(request)
       │    ├─ DiscogsClient.search(request)
       │    └─ BandcampSearchClient.search(request)
       ├─ ProcessFolderContextHolder.save(conversationId, ProcessFolderContext {
       │      path, audioFiles, searchResults: [mb_result, discogs_result, bandcamp_result]
       │    })
       └─ показує 3 картки метаданих для вибору юзера
```

### Selection (ProcessFolderSelectionOngoingFlow)
```
User вводить "1", "2", або "3" (або уточнення):
  └─ ProcessFolderFlowService.handleMetadataSelection(ctx, "1")
       ├─ ProcessFolderContextHolder.get(conversationId) → context
       ├─ selectedMetadata = context.searchResults[0]   // MusicBrainz result
       ├─ publishEvent(new ProcessLibraryTaskEvent(new ProcessLibraryTask(
       │      conversationId, path, releaseMetadata
       │    })
       ├─ ProcessFolderContextHolder.clear(conversationId)
       └─ return "⏳ обробляю..."
```

### libraryagent processing (async)
```
ProcessLibraryTaskEvent → ProcessFilesListener.handleProcessLibrary()
  └─ LibraryProcessingService.processLibrary(task)
       ├─ validate audio files
       ├─ AudioTagger.applyTags(files, metadata)   // JAudioTagger
       ├─ CoverArtService.embed(files)
       ├─ FileRenamer.rename(files, pattern)       // "01 - Track Title.flac"
       ├─ FileOrganizer.organize(files, libraryRoot) // Artist/Album/files
       ├─ ReleaseMetadataWriter.write(path, metadata) // .release-metadata.json
       ├─ upsert to DB: Track, Release, Artist, Label
       ├─ AudioAnalyzerBridge.analyze(files)       // fire-and-forget → Python REST
       └─ publishes LibraryProcessingCompleteEvent

LibraryProcessingCompleteEvent → LibraryProcessingCompleteListener
  └─ chatBot.sendMessage(ctx, "✅ оброблено: 10 треків")
```

---

## /reprocess command

### Flow
```
User: /reprocess "Burial - Untrue"
  └─ ReprocessReleasesFlowService.handle(ctx, "Burial - Untrue")
       ├─ знаходить реліз в БД по назві
       ├─ публікує ReprocessReleaseTaskEvent
       └─ return "⏳ репроцесую..."

ReprocessReleaseTaskEvent → libraryagent ReprocessReleaseListener
  └─ перечитує .release-metadata.json
  └─ переприкладає теги + перейменовує якщо треба
  └─ publishes ReprocessReleaseCompleteEvent → mainagent
```

---

## ReleaseIdentifierService (LLM folder parsing)

`FolderNameParser` — LangChain4j `@AiService` на Haiku.
Input: folder name string (cleaned, without path)
Output: `MetadataSearchRequest { artist, release, year?, label?, format? }`

Живе в `libraryagent` (файлова операція) але використовується і з `mainagent/process`.

---

## .release-metadata.json

Зберігається поряд з аудіо файлами реліза. Містить:
```json
{
  "releaseId": "mb-xxxx",
  "artist": "Burial",
  "title": "Untrue",
  "year": 2007,
  "label": "Hyperdub",
  "format": "FLAC",
  "tracks": [...]
}
```

`ReleaseMetadataReader` — читає при `/reprocess` і при file watcher pickup.
`ReleaseMetadataWriter` — пише після успішного `/process`.

---

## File watcher (background)

`LibraryWatcherService` — WatchService на `library.root.path`.
При виявленні нового аудіо файлу (не з `/process` flow):
1. Читає `.release-metadata.json` якщо є поряд
2. Якщо нема — розпізнає по папці через `ReleaseIdentifierService`
3. Upserts до БД

---

## Розміщення релізу і запуск фонових задач

Після `/process` реліз лягає в підбібліотеку за замовчуванням (`working`), а бот питає "📦 куди покласти?".
Аудіоаналіз і Apple Music sync **не стартують одразу**: обидва читають файли за шляхом, а вибір папки
переносить реліз, і задача, що вже читає старий шлях, падає (`Could not open file …/working/…`,
`ffmpeg … No such file or directory`). Тому вони запускаються на фінальному шляху, після першого вибору.

```
saveRelease(awaitPlacement=true)   ← LibraryProcessingService (новий реліз)
  └─ releases.placement_pending = true, аналіз НЕ запускається
LibraryProcessingCompleteEvent → повідомлення з кнопками LIB_ASSIGN:<releaseId>:<sublibrary>

вибір папки (SublibraryAssignmentHandler)
  ├─ інша папка      → MoveReleaseTaskEvent  → MoveReleaseListener → move() → успіх → ReleasePlacementService.confirm
  └─ та сама ("✅")  → PlaceReleaseTaskEvent → PlaceReleaseListener              ──→ ReleasePlacementService.confirm
                         (лише якщо placement_pending; інакше нічого не робиться)

ReleasePlacementService.confirm(conversationId, releaseId)
  └─ UPDATE releases SET placement_pending = false WHERE id = ? AND placement_pending = true
       0 рядків → вже розміщено (або старий реліз): нічого не робити
       1 рядок  → ReleasePlacedEvent(conversationId, releaseId, title, directoryPath)

ReleasePlacedEvent
  ├─ libraryagent: ReleasePlacedAnalysisListener → ReleaseService.requestAnalysis(releaseId)
  └─ mainagent:    AppleMusicSyncListener        → AppleMusicSyncService.submit(...)
```

- **Лише перший вибір.** Позначка `placement_pending` знімається одним атомарним `UPDATE`, тому подальші переноси
  (`working` → `vault` через місяць) не запускають аналіз і Apple sync вдруге (інакше iTunes отримав би дублі),
  а подвійний клік не запускає їх двічі.
- **`/reprocess`** (`ReprocessingService`, `saveRelease(awaitPlacement=false)`) питання про папку не показує,
  а реліз уже лежить на своєму місці, тому аналіз стартує одразу, як і раніше.
- **Старі релізи** мають `placement_pending = false` (міграція `V20`), тож їх переноси нічого не запускають.
- **Вибір не зроблено** → ні аналізу, ні Apple sync, доки реліз не розміщено (будь-який перенос або "залишити").
  Це свідомий компроміс: вибір папки — штатна частина процесу.
- Якщо після обробки не вдалося знайти реліз у БД за шляхом, кнопок нема, і позначка лишається, поки реліз не
  перенесуть уручну.

---

## Audio analyzer (Python, async)

```
ReleasePlacedAnalysisListener (після розміщення) / ReprocessingService (одразу)
  └─ POST {AUDIO_ANALYZER_URL}/analyze { filePath, releaseId, conversationId }
       (fire-and-forget, WebClient; filePath = Track.localPath на момент запиту)

Python sm-audio-analyzer:
  └─ Essentia: BPM, MFCC, danceability, loudness
  └─ POST /internal/audio-analysis-complete { releaseId, conversationId, features }

TrackAnalysisCompleteEvent → TrackAnalysisCompleteListener
  └─ upserts to track_analysis table
```

---

## Context persistence

`ProcessFolderContextHolder` — persisted via `ChatStateStore` (flow_key: `"process"`).

```java
ProcessFolderContext {
  String folderPath,
  List<AudioFile> files,
  List<ReleaseMetadata> searchResults   // [mb, discogs, bandcamp]
}
```

---

## DB tables affected

- `track` — один рядок на файл
- `release` — один рядок на реліз
- `artist` — знайти або створити
- `label` — знайти або створити
- `track_analysis` — BPM/MFCC/etc після аудіо-аналізу

---

## Error cases

| Situation | Response |
|-----------|----------|
| Папка порожня / нема аудіо | Immediate error message |
| LLM не розпізнав папку | Shows raw folder name, asks to specify |
| 0 результатів у всіх 3 джерелах | "😔 нічого не знайшов, спробуй уточнити" |
| Processing failure | `LibraryProcessingCompleteEvent` з помилкою → повідомлення юзеру |
