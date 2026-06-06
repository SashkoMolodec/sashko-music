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
  ├─ "SEARCH_ALT:" → MusicDownloadFlowService.handleSearchAlternative()
  └─ "CANCEL_DL:"  → MusicDownloadFlowService.handleDownloadCancel()

DownloadAgentService (через MainAgentTools.downloadMusic)
  └─ MusicDownloadFlowService.getDownloadOptions()   ← пошук без попереднього DL: кліку

FileSearchResultEvent (async, від downloadagent)
  └─ SearchFilesResultListener
       └─ MusicDownloadFlowService.handleSearchResults()

DownloadOptionSelectionOngoingFlow (text-based multi-turn, всі движки)
  └─ MusicDownloadFlowService.handleDownloadOption()
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
           └─ AnalysisResult(reports, aiSummary)
  → downloadContextHolder.saveDownloadOptions(conversationId, releaseId, reports)
  → DownloadOptionsCardFormatter.format(reports, aiSummary) + flowHandler.buildSearchResultsResponse()
```

Результати завжди показуються юзеру — автоматичного скачування без підтвердження **немає**.
`DownloadOptionSelectionOngoingFlow` стає активним для conversationId після збереження опцій.

---

## Multi-turn: вибір варіанту (всі движки)

`DownloadOptionSelectionOngoingFlow` активний поки `DownloadContextHolder` має опції для `conversationId`.
Використовується для **всіх** движків — навіть якщо є тільки 1 варіант (API-джерела).

### `handleDownloadOption(ctx, rawInput)`
- `-` → `clearSession`, `"❌ скасовано"`.
- Число `N` → `reports.get(N-1)`, `DownloadTaskProducer.send(...)`, `clearSession`.
- Інше → `"🤔 незрозумілий зроз."`.

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

## DownloadContextHolder (стан сесії)

In-memory `ConcurrentHashMap<conversationId, DownloadContext>`.

**Увага:** не персистується через `ChatStateStore` — втрачається при рестарті JVM.
Якщо юзер обирав варіант до рестарту — побачить `"варіанти пропали"`.

| Метод                                       | Дія                                  |
|---------------------------------------------|--------------------------------------|
| `saveDownloadOptions(conversationId, releaseId, reports)` | Зберігає результати пошуку |
| `getDownloadOptions(conversationId)`        | Читає для `OngoingFlow.appliesTo()`  |
| `getChosenRelease(conversationId)`          | `releaseId` для `handleDownloadOption` |
| `clearSession(conversationId)`              | Після вибору або скасування          |

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
2. `DownloadContextHolder` — не `ChatStateStore`. Якщо потрібна персистентність після рестарту — мігрувати на `ChatStateStore` (flow_key: `"download"`).
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
- Зробити `DownloadContextHolder` персистентним → описати нову схему `ChatStateStore` тут.
- Зміна lazy-reload логіки `getReleaseMetadata` → оновити опис `handleDownload`.
