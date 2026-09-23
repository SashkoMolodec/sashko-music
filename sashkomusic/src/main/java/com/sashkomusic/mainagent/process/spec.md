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
  ├─ "PROC_SEL:"      → ProcessFolderFlowService.handleMetadataSelectionByIndex()   (крок 1: треки)
  └─ "PROC_SEL_TAGS:" → ProcessFolderFlowService.handleTagsSelectionByIndex()       (крок 2: теги)

ProcessFolderFlowService.process()
  ├─ FolderAudioScanner.resolve() + listAudioFiles()
  ├─ ReleaseIdentifierService.identifyFrom*(audioFile / folderName) → MetadataSearchRequest
  ├─ ProcessFolderSearcher.searchAll() → SearchResults(knownResults, mbResults, discogsResults, bandcampResults)
  ├─ (опційно) known release із SearchContextService → results.withKnownRelease() → варіант 1
  ├─ MetadataSuggester.suggest()       → AI-підказка (Haiku, best-effort)
  ├─ ProcessOptionsFormatter.format()  → BotResponse з кнопками PROC_SEL:0..N + PROC_SEL:cancel
  └─ ProcessFolderContextHolder.save() → ChatStateStore
```

### Двокроковий вибір: треки vs теги

Різні джерела сильні в різному — MusicBrainz дає точну кількість треків, Discogs/Bandcamp
часто мають найбагатші genre-теги, але кількість треків у них менш надійна. Тому вибір
розбитий на два кроки замість одного:

1. **Крок 1** (`PROC_SEL:N`) — юзер обирає, з якого варіанта брати трек-лист (для
   `TrackMatcher`). Якщо `releaseIds.size() <= 1` — вибирати нема з чого, флоу одразу
   публікує `ProcessLibraryTaskEvent` як раніше, без кроку 2.
2. Інакше `handleMetadataSelectionByIndex` зберігає крок-1 pick у
   `ProcessFolderContextHolder.saveTracksPick()` (поле `tracksReleaseId`) і надсилає
   **другу картку** — ті самі кандидати, той самий нумерований порядок, але
   `ProcessOptionsFormatter.formatTagsSelection()` замість `format()`: акцент на
   `getTagsDisplay()` замість кількості треків, кнопки `PROC_SEL_TAGS:0..N` +
   `PROC_SEL_TAGS:same` (не міняти — теги з того самого варіанта, що й треки) +
   `PROC_SEL_TAGS:cancel`.
3. **Крок 2** (`PROC_SEL_TAGS:<payload>`) — `handleTagsSelectionByIndex` бере
   крок-1-метадані (`getMetadataWithTracks(tracksReleaseId)`) і:
   - `payload="same"` → публікує як є, без злиття;
   - `payload="cancel"` → скасовує весь `/process`;
   - `payload="<N>"` → резолвить крок-2 реліз (`getReleaseMetadata`, теги не потребують
     lazy-tracks), викликає `tracksMetadata.withTags(tagsMetadata.tags())` — **тільки**
     поле `tags` заміщується, `tracks`/`years`/`types`/`label` лишаються від крок-1 pick —
     і публікує злиту `ReleaseMetadata`.

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

### `handleMetadataSelectionByIndex(ctx, "PROC_SEL:<payload>")` — крок 1 (треки)
- `payload = "cancel"` → `contextHolder.clear(conversationId)`, `"❌ скасовано"`.
- `payload = "<N>"`, `releaseIds.size() <= 1` → одразу `publishEvent(...)` → `"🚀 опрацьовую..."` (без кроку 2).
- `payload = "<N>"`, `releaseIds.size() > 1` → `contextHolder.saveTracksPick(conversationId, releaseId)` →
  `optionsFormatter.formatTagsSelection(candidates)` (картка кроку 2, кнопки `PROC_SEL_TAGS:`).
- Невідомий index → `"❌ невірний варіант"`.
- Протухла сесія → `"❌ сесія закінчилась. спробуй ще раз"`.

### `handleTagsSelectionByIndex(ctx, "PROC_SEL_TAGS:<payload>")` — крок 2 (теги)
- `payload = "cancel"` → `contextHolder.clear(conversationId)`, `"❌ скасовано"`.
- `payload = "same"` → публікує крок-1-метадані без змін.
- `payload = "<N>"` → `tracksMetadata.withTags(tagsMetadata.tags())` → `publishEvent(...)` →
  `contextHolder.clear()` → `"🚀 опрацьовую..."`.
- Немає `tracksReleaseId` у стейті (сесія протухла між кроками) → `"❌ невідома команда"`.

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
    List<String> releaseIds,     // індекс відповідає PROC_SEL:N і PROC_SEL_TAGS:N
    String tracksReleaseId       // null до кроку 1; крок-1 pick, поки чекаємо крок 2
) {}
```

| Метод | Дія |
|-------|-----|
| `save(conversationId, path, files, releaseIds)` | Зберігає стан пошуку (`tracksReleaseId=null`) |
| `saveTracksPick(conversationId, releaseId)` | Записує крок-1 pick, чекаючи крок 2 |
| `get(conversationId)` | Читає `Optional<ProcessFolderState>` |
| `getReleaseIdByOption(conversationId, index)` | `releaseIds.get(index)` або `null` |
| `hasActiveContext(conversationId)` | Перевіряє чи є активна сесія |
| `clear(conversationId)` | Після вибору або скасування |
| `clearAll()` | По "стоп" або `clearAllCaches()` |

---

## ProcessOptionsFormatter

`format()` (крок 1, вибір треків) будує `BotResponse.withMultiRowButtons()` з:
- Markdown текст з секціями по джерелах (🎵 musicbrainz / 💿 discogs / 📼 bandcamp)
- Emoji-numbered кнопки `PROC_SEL:0`..`PROC_SEL:N-1` (5 per row)
- `PROC_SEL:cancel` (❌ скасувати)

`formatTagsSelection()` (крок 2, вибір тегів) — та сама нумерація/порядок кандидатів,
але рядок кожного варіанта показує `getTagsDisplay()` замість `getTrackCountDisplay()`.
Кнопки: `PROC_SEL_TAGS:0`..`PROC_SEL_TAGS:N-1`, `PROC_SEL_TAGS:same` (🔁 ті самі —
без злиття), `PROC_SEL_TAGS:cancel` (❌).

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
