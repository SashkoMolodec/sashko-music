# Download Feature

## Purpose
Завантаження музики з 5 джерел. Завжди вимагає підтвердження від юзера (вибір числом) — автоматичного скачування немає.

---

## Full flow

```
1. User clicks [DL] on release card
      └─ MusicDownloadFlowService.handleDownload(ctx, "DL:<releaseId>")
           ├─ getReleaseMetadata(releaseId, conversationId)   ← lazy-load з DB якщо кеш порожній
           ├─ default engine: QOBUZ
           └─ SearchFilesTaskProducer.send() → FilesSearchTaskEvent → downloadagent

2. downloadagent шукає файли (async):
      └─ MusicSourcePort.search(artist, release)
      └─ SearchResultProducer.send() → FileSearchResultEvent → mainagent

3. MusicDownloadFlowService.handleSearchResults(dto)
      ├─ flowHandler.analyzeAll(options) → AnalysisResult { reports, aiSummary }
      ├─ DownloadContextHolder.saveDownloadOptions(conversationId, releaseId, reports)
      └─ показує картку + ALT-кнопки

4. User type "1" (або іншу цифру)
      └─ DownloadOptionSelectionOngoingFlow.handle(ctx, "1")
           ├─ reports.get(0) → обраний DownloadOption
           ├─ DownloadTaskProducer.send() → FilesDownloadTaskEvent → downloadagent
           └─ DownloadContextHolder.clearSession(conversationId)

5. downloadagent quality (async):
      └─ client.initiateDownload(option, releaseId)
      └─ client.handleDownloadCompletion() → monitoring starts

6. DownloadBatchCompleteEvent → mainagent:
      └─ ProcessLibraryTaskProducer.send() → ProcessLibraryTaskEvent → libraryagent
           └─ auto-processes downloaded files into library
```

---

## Download engines

| Engine | Handler | Quality | ALT buttons | Options count |
|--------|---------|---------|-------------|---------------|
| `QOBUZ` | `QobuzDownloadFlowHandler` | 💎 PERFECT (lossless) | ▶️ YTM, 🍏 Apple, 📼 Bandcamp, ⛏️ Soulseek | 1 (API) |
| `APPLE_MUSIC` | `AppleMusicDownloadFlowHandler` | 🟢 GOOD (AAC 256) | — | 1 (API) |
| `BANDCAMP` | `BandcampDownloadFlowHandler` | 🟢 GOOD | — | 1 (API) |
| `YOUTUBE_MUSIC` | `YouTubeMusicDownloadFlowHandler` | 🟢 GOOD (AAC 128/256 залежно від cookies) | 🎵 Qobuz, 🍏 Apple, 📼 Bandcamp, ⛏️ Soulseek | 1–N |
| `SOULSEEK` | `SoulseekDownloadFlowHandler` | PERFECT/GOOD/WARNING/BAD | — | 0–N (P2P) |

### YTM: album vs single fallback
- Спочатку шукає `filter="albums"` через `ytmusicapi`
- Якщо 0 результатів — fallback на пошук пісень, фільтрує по точному імені артиста
- Singles: `id = "ytm-v-<videoId>"`, URL = `https://music.youtube.com/watch?v=<videoId>`
- Albums: `id = "ytm-<playlistId>"`, URL = `https://music.youtube.com/playlist?list=<playlistId>`
- Output template для singles: `%(uploader)s/%(album,title)s/%(title)s.%(ext)s`
- Output template для albums: `%(uploader)s/%(album)s/%(playlist_index)02d - %(title)s.%(ext)s`

### Soulseek: scoring
`SoulseekDownloadFlowHandler.resolveSuitabilityLevel`:
- Lossless (FLAC/WAV/AIFF/ALAC > 90% аудіо файлів) + track count diff = 0 → `PERFECT 💎`
- Lossless + зайві треки → `GOOD 🟢`
- Lossy або ≤2 треки різниця → `WARNING 🟡`
- Інше → `BAD 🔴`

LLM summary (DownloadBatchAnalyzer, Haiku): порівнює tracklist реліза з файлами в батчах, рекомендує найкращий варіант.

---

## Callbacks

### `DL:<releaseId>`
Запускає пошук з дефолтним движком (Qobuz). Метадані підтягуються lazy з `SearchContextService` — кнопки на старих картках після рестарту JVM **не ламаються**.

### `SEARCH_ALT:<releaseId>:<ENGINE>`
Перезапускає пошук на іншому движку. `ENGINE` = точна назва `DownloadEngine.name()` (e.g. `YOUTUBE_MUSIC`).

### `CANCEL_DL:<releaseId>`
1. `DownloadCancelTaskProducer.send()` → async → `DownloadCancelListener` → `DownloadService.cancelDownload()`
2. `cancelDownload`: знаходить batch по releaseId → `client.cancelDownload(releaseId)` → `ActiveDownloadRegistry.cancel(releaseId)` (kills process/HTTP)
3. Якщо batch не знайдено → логується, юзеру нічого не шлеться (download вже завершився)
4. Callback одразу повертає `"❌ скасовано"` — async підтвердження НЕ дублюється

---

## DownloadOption model

```java
record DownloadOption(
    String id,               // "ytm-<playlistId>", "ytm-v-<videoId>", "qobuz-<albumId>", slskd username
    DownloadEngine source,
    String displayName,      // "Artist - Title (Year) [type, Format]"
    int totalSize,           // MB (0 для API-джерел)
    List<FileItem> files,    // порожній для API-джерел
    Map<String, String> technicalMetadata
)
```

---

## DownloadContextHolder (in-memory, не персистується)

```
Map<conversationId, DownloadContext { releaseId, List<OptionReport> }>
```
Очищається при: виборі варіанту, `-` (cancel), `стоп`.
Втрачається при рестарті JVM — юзер бачить `"😔 варіанти пропали"`.

---

## DownloadOptionsCardFormatter

- API-джерела (empty files): `"1️⃣ **displayName** (suitability) [🔗](url)"`
- Soulseek (files present): формат + кількість файлів + перші 7 файлів + AI summary
- Source link: Qobuz → `albumUrl`, Bandcamp/Apple → `url`, YTM album → playlist URL, YTM single → watch URL

---

## Infrastructure

| Client | Transport | Notes |
|--------|-----------|-------|
| `QobuzClient` | REST API | Direct lossless download |
| `AppleMusicClient` | iTunes API | Scraper-based |
| `BandcampDownloadClient` | sm-scraper `/bandcamp/release` | PlaywrightBrowser scraping |
| `YouTubeMusicClient` | sm-scraper `/ytmusic/search` + yt-dlp | Cookies optional (256 vs 128 kbps) |
| `SlskdClient` | Slskd REST API | P2P daemon, monitors transfers |

`ActiveDownloadRegistry` — зберігає cancel-handles (`CancellationHandle` lambda) per releaseId.
`DownloadContext` — зберігає батчі файлів per releaseId, трекає progress (для Soulseek).
`DownloadMonitorService` — слідкує за файлами на диску, детектує completion.
