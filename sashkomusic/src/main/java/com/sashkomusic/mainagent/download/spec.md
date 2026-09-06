# MusicDownloadFlowService — Spec

> Feature flow: [/.specs/download.md](../../../../../../.specs/download.md)
> Events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Оркестрація download-воркфлоу на стороні mainagent: обробка callback-кнопок (`DL:`, `SEARCH_ALT:`, `CANCEL_DL:`),
відображення результатів пошуку, вибір опції завантаження. Не містить бізнес-логіки завантаження —
вся реальна робота в `downloadagent`. Не є LLM-агентом.

---

## Місце в потоці

```
CallbackDispatcher
  ├─ "DL:"         → MusicDownloadFlowService.handleDownload()
  ├─ "DLOPT:"      → MusicDownloadFlowService.handleDownloadOptionCallback()
  ├─ "SEARCH_ALT:" → MusicDownloadFlowService.handleSearchAlternative()
  └─ "CANCEL_DL:"  → MusicDownloadFlowService.handleDownloadCancel()

DownloadAgentService (через MainAgentTools.downloadMusic)
  └─ MusicDownloadFlowService.getDownloadOptions()   ← пошук без попереднього DL: кліку

FileSearchResultEvent (async, від downloadagent)
  └─ SearchFilesResultListener
       └─ MusicDownloadFlowService.handleSearchResults()
            └─ mergeWithSelectionButtons() → кнопки DLOPT:0, DLOPT:1, ..., DLOPT:cancel
```

---

## Callback handlers

### `handleDownload(ctx, "DL:<releaseId>")`
Запускає пошук з дефолтним движком (Qobuz).
1. `contextService.getReleaseMetadata(releaseId, ctx.conversationId())` — спочатку in-memory кеш,
   при промаху підтягує `SearchState` з `ChatStateStore` і перебудовує кеш.
   Завдяки цьому кнопки DL на старих картках працюють після перезапуску JVM.
2. `SearchFilesTaskProducer.send(SearchFilesTaskDto)` → `FilesSearchTaskEvent` → downloadagent.
3. Повертає `"🔎 шукаю опції завантаження (qobuz): ..."`.

### `handleSearchAlternative(ctx, "SEARCH_ALT:<releaseId>:<ENGINE>")`
Перезапускає пошук на іншому движку.
1. Парсить `releaseId` і `sourceName` (останнє поле після `:`)
2. `getReleaseMetadata(releaseId, ctx.conversationId())` — з тим самим lazy-reload.
3. `DownloadEngine.valueOf(sourceName)` → `initiateDownloadSearch(ctx, metadata, source)`.
4. Повертає той самий прогрес-текст що і `handleDownload`, але з назвою обраного движка.

### `handleDownloadCancel(ctx, "CANCEL_DL:<releaseId>")`
1. `DownloadCancelTaskProducer.send(...)` → `DownloadCancelTaskEvent` → downloadagent.
2. Повертає `"⏳ скасовую..."`.

---

## Async результати пошуку

### `handleSearchResults(SearchFilesResultDto)`
Викликається з `SearchFilesResultListener` при `FileSearchResultEvent`.

```
dto → flowHandler.analyzeAll(options, releaseId, conversationId)
           └─ AnalysisResult(allReports, aiSummary)
  → downloadContextHolder.saveDownloadOptions(conversationId, releaseId, allReports, source)
  → firstPage = allReports.limit(PAGE_SIZE), offset = 0
  → DownloadOptionsCardFormatter.format(firstPage, aiSummary, offset) + flowHandler.buildSearchResultsResponse()
  → mergeWithSelectionButtons(sourceCard, firstPage, appendCancelRow, offset)
       ├─ buttons from sourceCard converted to row
       ├─ DLOPT:<offset+0>, DLOPT:<offset+1>, ... (глобальний індекс, 5 per row, emoji-numbered)
       └─ DLOPT:cancel (❌ скасувати)
  → якщо є наступна сторінка → DLNEXT:<releaseId> кнопка (advancePage при кліку, offset росте далі)
```

Результати завжди показуються юзеру — автоматичного скачування без підтвердження **немає**.

---

## Button-based вибір варіанту

Замість text-based `OngoingFlow` (видалений) — кнопки `DLOPT:<index>` прямо в картці результатів.
Кнопки персистовані (ChatStateStore) — переживають рестарт JVM.

### `handleDownloadOptionCallback(ctx, "DLOPT:<payload>")`
- `payload = "cancel"` → `clearSession`, `"❌ скасовано"`.
- `payload = "<N>"` → `reports.get(N)`, `DownloadTaskProducer.send(...)`, `clearSession`, повертає `formatDownloadConfirmation(option)`.
- Невідомий index → `"❌ невідомий варіант"`.

---

## DownloadFlowHandler стратегії

| Engine          | Handler                           | Suitability           | Кнопки ALT в результаті                          | Кількість варіантів   |
|-----------------|-----------------------------------|-----------------------|--------------------------------------------------|-----------------------|
| `QOBUZ`         | `QobuzDownloadFlowHandler`        | `PERFECT`             | ▶️ YTM, 🍏 Apple, 📼 Bandcamp, ⛏️ Soulseek      | 1 (API)               |
| `APPLE_MUSIC`   | `AppleMusicDownloadFlowHandler`   | `GOOD`                | —                                                | 1 (API)               |
| `BANDCAMP`      | `BandcampDownloadFlowHandler`     | `GOOD`                | —                                                | 1 (API)               |
| `YOUTUBE_MUSIC` | `YouTubeMusicDownloadFlowHandler` | `GOOD`                | 🎵 Qobuz, 🍏 Apple, 📼 Bandcamp, ⛏️ Soulseek    | 1–N (album або single)|
| `SOULSEEK`      | `SoulseekDownloadFlowHandler`     | `PERFECT/GOOD/WARNING/BAD` | —                                           | 0–N (P2P)             |

### `DownloadFlowHandler` interface

```java
AnalysisResult analyzeAll(List<DownloadOption> options, String releaseId, String conversationId);
BotResponse buildSearchResultsResponse(String formattedText, String releaseId, DownloadEngine currentSource);
String formatDownloadConfirmation(DownloadOption option);
```

- `analyzeAll` — присвоює `Suitability` кожному варіанту, сортує, може викликати LLM (тільки Soulseek через `DownloadBatchAnalyzer`).
- `buildSearchResultsResponse` — обгортає `formattedText` в `BotResponse` з кнопками альтернативних движків (тільки Qobuz і YTM мають ALT-кнопки).

---

## Soulseek search — детально

Soulseek — єдиний P2P-движок, що потребує активного пошуку і аналізу варіантів. Решта (Qobuz, Apple, Bandcamp, YTM) — API, один результат, без LLM.

### Фаза 1 — пошук файлів (`SlskdClient.search()`, downloadagent)

```
query = artist + " " + release
POST /api/v0/searches { searchText, searchTimeout=20s, responseLimit=70, filterResponses=true, minimumResponseFileCount=1 }
  → polling GET /api/v0/searches/{id} кожні 3s до isComplete=true або 20s timeout
       └─ кожна зміна fileCount → DownloadLogLineEvent (прогрес у логи-канал Telegram)
  → після complete: 10s stabilization delay (якщо fileCount > 0)
GET /api/v0/searches/{id}/responses → List<SlskdSearchEntryResponse>
```

**Фільтри (у `toDomain()`):**
1. Є аудіо-файли (`files` not empty)
2. Нема заблокованих (`lockedFileCount == 0`)
3. Доступний для завантаження (`hasFreeUploadSlot == true`)

**Групування (`splitByAlbumFolder`):** файли одного peer групуються по батьківській папці шляху → кожна папка = окремий `DownloadOption`. Один peer може дати кілька опцій (кілька альбомів).

**Ліміт:** safety cap 200 папок після всіх фільтрів (`.limit(200)` на stream). `responseLimit` у запиті до slskd — 150 пірів.

**Fallback:** якщо 0 результатів → `NoSearchResultsException` → Resilience4j retry. Якщо retry вичерпані → circuit breaker → `searchFallback()` повертає `List.of()`.

### Фаза 2 — аналіз варіантів (`SoulseekDownloadFlowHandler.analyzeAll()`, mainagent)

```
SearchContextService.getMetadataWithTracks(releaseId, conversationId)
  → ReleaseMetadata { artist, title, minTracks, trackTitles[] }   // ground truth з MusicBrainz

для кожного DownloadOption:
  resolveSuitabilityLevel(opt, expected)
    ├─ isLossless: >90% аудіо-файлів мають розширення flac/wav/aiff/alac
    ├─ diff = audioFilesCount - expected.minTracks()
    └─ PERFECT   : lossless && diff == 0
       GOOD      : lossless && diff > 0  (бонус-треки)
       WARNING   : |diff| ≤ 2  або  !lossless
       BAD       : !lossless && diff > 2

сортування: PERFECT → GOOD → WARNING → BAD
.limit(10)  ← ріжемо тут, після sort — Haiku бачить топ-10 по якості

DownloadBatchAnalyzer.analyze(artist, album, tracklist, optionsText)   // Haiku LLM
  → 2-3 речення укр., plain text, без markdown
  → відмічає: бонус-треки, неповні, remaster, vinyl rips
  → ігнорує стандартні/повні видання — пише тільки про відмінності
  → завершує рекомендацією ("беріть опцію 1 або 2")
```

`DownloadBatchAnalyzer` отримує **всі 10 опцій одним викликом** — не по одній. `buildOptionsText()` конкатенує їх у єдиний текст (`Option 1:\n...\nOption 2:\n...`), Haiku пише один загальний summary. Це єдиний LLM-виклик у всьому download-потоці. Всі інші движки детерміновані.

### Де відображається результат

`DownloadOptionsCardFormatter` → якщо `files` не порожні (Soulseek):
- `"1️⃣ **displayName** (suitability)"` + кількість файлів + список перших 7
- `aiSummary` від `DownloadBatchAnalyzer` — `"💡 _summary_"` в кінці картки

`SoulseekDownloadFlowHandler.buildSearchResultsResponse()` не додає ALT-кнопок (Soulseek — вже останній резерв).

---

## DownloadContextHolder (стан сесії)

Персистується через `ChatStateStore` (flow_key: `"dl_ctx"`). Переживає рестарт JVM — кнопки DLOPT на старих картках залишаються робочими.

```java
record DownloadContext(String chosenReleaseId, List<DownloadFlowHandler.OptionReport> allReports, int currentPage, DownloadEngine source) {}
```

Зберігається **тільки** повний відсортований список (`allReports`) + номер поточної сторінки —
без окремого "поточна сторінка" списку. `DLOPT:<i>` кодує **глобальний** індекс в `allReports`
(не локальний індекс на сторінці), тому кнопка, показана на 1-й сторінці, лишається робочою і
після того як юзер перегорнув на 3-тю — не інвалідиться, бо ніколи не перевикористовується.
Нумерація в тексті (`DownloadOptionsCardFormatter.format(reports, aiSummary, offset)`) і кнопки
(`mergeWithSelectionButtons(..., offset)`) використовують той самий `offset = page * PAGE_SIZE`.

| Метод                                          | Дія                                                       |
|-------------------------------------------------|------------------------------------------------------------|
| `saveDownloadOptions(conversationId, releaseId, allReports, source)` | Зберігає повний список результатів, `currentPage = 0`     |
| `getDownloadOptions(conversationId)`            | Повертає `allReports` — глобальний lookup для `DLOPT:<globalIndex>` |
| `advancePage(conversationId)`                   | Зсуває `currentPage`, повертає підсписок для показу (без збереження підсписку) |
| `clearSession(conversationId)`                  | Після вибору або скасування                               |
| `clearAllSessions()`                            | По "стоп" або `clearAllCaches()`                          |

---

## Download topic routing (опційно)

Якщо задано `telegram.download-topic-id` (env `TGBOT_DOWNLOAD_TOPIC_ID`), увесь процес
**фактичного** скачування — від моменту `DL:`/`SEARCH_ALT:`/direct-query кліку і до
"✅ додано в лібку!" (`LibraryProcessingCompleteListener`) — переїжджає в один фіксований
форум-топік, незалежно від того, де юзер шукав реліз. Пошук/дискавері релізу (`ReleaseSearchFlowService`,
`/search`, метадата-картка) **не** зачіпається — лишається там, де почався.

Механізм — `MusicDownloadFlowService.resolveDownloadCtx(ctx)`:
- якщо `telegram.download-topic-id` не задано → no-op, `ctx` як і раніше (backward-compatible,
  той самий патерн, що й `TelegramDownloadLogStreamer`/`telegram.logs-topic-id`);
- якщо задано → повертає `ConversationContext.topic(defaultChatId, downloadTopicId)`.

Це спрацьовує "безкоштовно" для всього, що йде після першого кліку, бо:
1. `conversationId` наскрізно проходить рядком через увесь event-пайплайн
   (`SearchFilesTaskDto` → `FileSearchResultEvent`/`DownloadCompleteEvent`/`DownloadBatchCompleteEvent`
   → `ConversationContext.from(dto.conversationId())` у відповідних listener-ах);
2. колбеки (`DLOPT:`, `DLNEXT:`, `SLSK_*`) отримують `ctx` від Telegram — з того топіку, де
   фізично лежить повідомлення з кнопками, тобто вже download-топік, без додаткової маршрутизації.

Єдиний нюанс: `SearchContextService` кешує `ReleaseMetadata` по `conversationId` (з моменту пошуку
релізу). Коли downstream-lookup (`SoulseekDownloadFlowHandler.analyzeAll`, `handleSearchAlternative`)
починає читати по download-топіковому `conversationId`, оригінального запису там нема — тому
`initiateDownloadSearch` при першому редиректі (`!downloadCtx.equals(ctx)`) віддзеркалює вибраний
реліз через `SearchContextService.mirrorReleaseForDownload(downloadCtx.conversationId(), metadata)`.

Свідомо **не** зберігається "куди повернутись" — фінальне "✅ додано в лібку!" теж лишається в
download-топіку (простіше, і `ProcessFolderFlowService.process()` все одно робить власний
незалежний re-lookup метаданих по тому ж `conversationId`, без залежності від оригінальної розмови).

Наслідок: топік по суті одно-сесійний — два паралельні завантаження, стартовані з різних
розмов, писатимуть в один і той же `dl_ctx`/`search`-стан і будуть переплітатись. Прийнятно для
одно-користувацького бота; якщо колись знадобиться паралельність — треба буде тегувати сесії
окремим id замість того щоб покладатись на єдиний спільний `conversationId`.

`DirectSoulseekSearchFlowService` (команда `копай`) через цей механізм **не** проходить — це
окремий, самодостатній entry point, який зберігає й читає метадані в одній і тій самій розмові
за один виклик, без залежності від download-топіку.

---

## DownloadOption (модель)

```java
record DownloadOption(
    String id,               // "ytm-<playlistId>", "ytm-v-<videoId>", "qobuz-<albumId>", slskd username
    DownloadEngine source,
    String displayName,      // "Artist - Title (Year) [type, Format]"
    int totalSize,           // MB (0 для API-джерел без файлового listing)
    List<FileItem> files,    // порожній для API-джерел (Qobuz, Apple, YTM, Bandcamp)
    Map<String, String> technicalMetadata  // source-specific: quality, playlistId, videoId, url тощо
)
```

`files` порожній для всіх API-джерел — `DownloadOptionsCardFormatter` відображає `displayName` напряму.

---

## DownloadOptionsCardFormatter

Форматує `List<OptionReport>` + `aiSummary` в Markdown для Telegram.

- Якщо `files` порожні → `"1️⃣ **displayName** (suitability) [🔗](url)"`.
- Якщо `files` є (Soulseek) → формат + кількість файлів + список перших 7.
- `buildSourceLink` будує URL для: Qobuz (`albumUrl`), Bandcamp/Apple (`url`),
  YouTube Music — `playlistId` якщо album, або `videoId` якщо single (prefix `watch?v=`).
- `aiSummary` від `DownloadBatchAnalyzer` відображається як `"💡 _summary_"` в кінці (тільки Soulseek).

---

## Hard rules
1. `MusicDownloadFlowService` — orchestration only. Жодного DB access, жодних AI-викликів.
2. `DownloadContextHolder` персистується через `ChatStateStore` (flow_key: `"dl_ctx"`). Кнопки `DLOPT:` переживають рестарт JVM.
3. Новий движок → новий `DownloadFlowHandler` + реєстрація в `DownloadSourceConfig` + оновити таблицю вище.
4. Кнопки ALT-джерел — в `buildSearchResultsResponse` відповідного handler. Не в `MusicDownloadFlowService`.
5. `SEARCH_ALT:` парсить `ENGINE` через `DownloadEngine.valueOf()` — значення enum має точно збігатись з `name()`.
6. Автоматичного скачування без підтвердження юзером **немає** — `handleSearchResults` завжди тільки показує картку.

---

## Out of scope
- Реальне завантаження файлів → `downloadagent`
- Моніторинг прогресу → `DownloadMonitorService` в downloadagent
- Тегування після завантаження → `libraryagent`
- LLM-вибір движка → може бути додано через `engine` параметр у `MainAgentTools.downloadMusic`

---

## SDD checkpoints (змінювати spec ДО коду)
- Новий `DownloadEngine` → додати рядок у таблицю handlers + описати кнопки.
- Змінити кнопки у якомусь handler → оновити колонку "Кнопки ALT в результаті".
- Зміна lazy-reload логіки `getReleaseMetadata` → оновити опис `handleDownload`.
- Додати нову кнопку до результатів → оновити `mergeWithSelectionButtons` та документацію вище.
