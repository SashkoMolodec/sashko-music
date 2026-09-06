# mainagent/process — Spec

## Purpose
Оркестрація `/process` воркфлоу на стороні mainagent: сканування папки → ідентифікація релізу →
пошук метаданих по 3 джерелах → представлення варіантів кнопками → відправка `ProcessLibraryTaskEvent` до libraryagent.
Не LLM-агент. Важка логіка делегована до libraryagent і ProcessFolderSearcher.

**Примітка:** `/process` і `/reprocess` **видалені** з Telegram command menu. Функціонал FlowService існує і
запускається внутрішньо (напр. з LibraryAgent або через майбутні entry points) — але не через slash-команду.

---

## Місце в потоці

```
UserInteractionOrchestrator
  └─ ProcessFolderFlowService.handleProcessCommand()   ← (внутрішній виклик)

CallbackDispatcher
  └─ "PROC_SEL:" → ProcessFolderFlowService.handleMetadataSelectionByIndex()

ProcessFolderFlowService.process()
  ├─ FolderAudioScanner.resolve() + listAudioFiles()
  ├─ ReleaseIdentifierService.identifyFrom*(audioFile / folderName) → MetadataSearchRequest
  ├─ ProcessFolderSearcher.searchAll() → SearchResults(knownResults, mbResults, discogsResults, bandcampResults)
  ├─ (опційно) known release із SearchContextService → results.withKnownRelease() → варіант 1
  ├─ MetadataSuggester.suggest()       → AI-підказка (Haiku, best-effort)
  ├─ ProcessOptionsFormatter.format()  → BotResponse з кнопками PROC_SEL:0..N + PROC_SEL:cancel
  └─ ProcessFolderContextHolder.save() → ChatStateStore
```

---

## Known-release pre-fill (после скачування)

`DownloadBatchCompleteListener` викликає `process(ctx, directoryPath, "", dto.releaseId())` —
`dto.releaseId()` це той самий реліз, який юзер обрав кнопкою `DL:` на самому початку (до пошуку
файлів і скачування). У 90% випадків це саме той реліз, що й треба для тегування — тому замість
змушувати юзера повторно копіювати/шукати те саме, `process()` резолвить `knownReleaseId` через
`SearchContextService.getReleaseMetadata(releaseId, conversationId)` (той самий кеш, що заповнює
`DL:`-пошук і `mirrorReleaseForDownload` при topic-роутингу) і кладе результат у
`SearchResults.knownResults` — рендериться `ProcessOptionsFormatter` **першою** секцією
("✅ знайдено при пошуку:"), тобто завжди варіант `1️⃣`.

- Якщо `SearchContextService` вже не містить цей releaseId (сесія протухла / юзер щось інше шукав
  у той самий conversationId, поки йшло скачування) — `getReleaseMetadata` повертає `null`,
  `knownResults` лишається пустим, і флоу деградує до звичайного 3-джерельного пошуку по назві
  папки, як і раніше.
- Якщо назва папки взагалі не парситься (`validateSearchRequest` fail) **але** known release
  резолвився — пошук по 3 джерелах пропускається повністю, юзер бачить лише один варіант
  (той самий known release) замість помилки "не вдалося розпізнати назву релізу".
- `handleProcessCommand` (ручний `/process`) і `ProcessFolderSelectionOngoingFlow`/
  `PendingProcessCallbackHandler`/`LibraryAgentTools` (ручний реprocess) **не передають**
  `knownReleaseId` — цей механізм працює тільки на автоматичному post-download шляху.

---

## Callback-based вибір варіанту

Замість text-based OngoingFlow — inline keyboard кнопки безпосередньо в картці результатів.
Кнопки персистовані (ChatStateStore) — переживають рестарт JVM.

### `handleMetadataSelectionByIndex(ctx, "PROC_SEL:<payload>")`
- `payload = "cancel"` → `contextHolder.clear(conversationId)`, `"❌ скасовано"`.
- `payload = "<N>"` → `contextHolder.getReleaseIdByOption(conversationId, N)` →
  `searchContextService.getMetadataWithTracks(releaseId, conversationId)` →
  `ProcessLibraryTaskProducer.send(ProcessLibraryTaskDto)` → `contextHolder.clear()` → `"🚀 опрацьовую..."`.
- Невідомий index → `"❌ невірний варіант"`.
- Протухла сесія → `"❌ сесія закінчилась. спробуй ще раз"`.

### `handleMetadataSelection(ctx, rawInput)` — text fallback
- URL → `handleUrlMetadataSelection` (Discogs / MusicBrainz / Bandcamp URL → fetch metadata → send task).
- `+<text>` → `handleAdditionalContext` — rerun `process()` з додатковим контекстом.
- `-` → скасувати.
- Інше → `"обери варіант кнопкою вище або скинь посилання на реліз"`.

---

## ProcessFolderContextHolder

Персистується через `ChatStateStore` (flow_key: `"proc_sel"`). Переживає рестарт JVM.

```java
record ProcessFolderState(
    String directoryPath,
    List<String> audioFiles,
    List<String> releaseIds      // індекс відповідає PROC_SEL:N
) {}
```

| Метод | Дія |
|-------|-----|
| `save(conversationId, path, files, releaseIds)` | Зберігає стан пошуку |
| `get(conversationId)` | Читає `Optional<ProcessFolderState>` |
| `getReleaseIdByOption(conversationId, index)` | `releaseIds.get(index)` або `null` |
| `hasActiveContext(conversationId)` | Перевіряє чи є активна сесія |
| `clear(conversationId)` | Після вибору або скасування |
| `clearAll()` | По "стоп" або `clearAllCaches()` |

---

## ProcessOptionsFormatter

Будує `BotResponse.withMultiRowButtons()` з:
- Markdown текст з секціями по джерелах (🎵 musicbrainz / 💿 discogs / 📼 bandcamp)
- Emoji-numbered кнопки `PROC_SEL:0`..`PROC_SEL:N-1` (5 per row)
- `PROC_SEL:cancel` (❌ скасувати)

---

## Hard rules

1. `ProcessFolderFlowService` — orchestration only. Жодного DB access, жодних прямих AI-викликів.
2. `ProcessFolderContextHolder` персистується через `ChatStateStore` — не in-memory Map.
3. Кнопка `PROC_SEL:N` безпосередньо енкодить індекс; mapping до releaseId — через `contextHolder`.
4. `processFolder` і `reprocessRelease` **не виставлені** як `@Tool` в LibraryAgent.
5. Форматування варіантів — тільки в `ProcessOptionsFormatter`.
6. `MetadataSuggester` — best-effort; помилки логуються, не кидаються.

---

## SDD checkpoints

- Нове джерело метаданих → `ProcessFolderSearcher.searchAll()` + секція в `ProcessOptionsFormatter`.
- Зміна формату кнопок → оновити `ProcessOptionsFormatter.buildSelectionButtons()` і `handleMetadataSelectionByIndex` parsing.
- Нове поле в `ProcessFolderState` → оновити record + Jackson deserialization (сумісність з існуючими рядками в ChatStateStore).
