# mainagent/search — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)
> State persistence: [/.specs/conversation-state.md](../../../../../../.specs/conversation-state.md)

## Purpose
Пошук релізів і зберігання результатів між запитами. Обслуговує discovery flow і download flow. Не LLM — тільки оркестрація HTTP-клієнтів і персистентний стан сесії.

---

## `SearchContextService` — contract

Persists to `ChatStateStore` (flow_key: `"search"`):
```java
record SearchState(SearchContext context, List<ReleaseMetadata> releases)
record SearchContext(SearchEngine source, MetadataSearchRequest request, String rawInput, List<String> releaseIds, int currentPage)
```

`currentPage` — індекс поточної відкритої release картки (0-based). Оновлюється `buildPageResponse()` при кожному PAGE:/DIG_DEEPER переключенні. Читається `DiscoveryAgentTools.getTrackList()` аби знати для якого релізу показувати треклист.

| Метод | Що робить |
|-------|-----------|
| `saveSearchContext(conversationId, source, rawInput, request, results)` | Merge + persist + update cache, `currentPage=0` |
| `getReleaseMetadata(releaseId)` | In-memory cache тільки |
| `getReleaseMetadata(releaseId, conversationId)` | Cache + lazy loadContext при промаху (пережива рестарт JVM) |
| `getSearchResults(conversationId)` | З store, відновлює cache як side-effect |
| `getMetadataWithTracks(releaseId, conversationId)` | Lazy-load треків через `SearchEngineService` |
| `updateCurrentPage(conversationId, page)` | Читає SearchState, пише назад з новим `currentPage` |
| `getCurrentPage(conversationId)` | Читає `currentPage` (default 0) |
| `copySearchContext(fromId, toId)` | Копіює `:d` → основний conversationId після Discovery |
| `clearSearch(conversationId)` | Видаляє з store |
| `clearAllCaches()` | Store + in-memory (по "стоп") |

**Merge strategy:** нові результати перезаписують старі по releaseId (дедупліцирує). Дозволяє `DIG_DEEPER` акумулювати результати з різних джерел.

---

## `SearchEngineService` — interface

```java
SearchEngine getSource();
List<ReleaseMetadata> searchReleases(MetadataSearchRequest request);
List<TrackMetadata> getTracks(String releaseId);
```

Implementations: `MusicBrainzClient`, `DiscogsClient`, `BandcampClient`.

**Engine order is intent-dependent**, not a fixed `SearchEngine.values()` walk — `MetadataSearchRequest.isBrowseQuery()` (true when there's no `release`/`recording` title, only `style`/`dateRange` filters, e.g. "trance 1994") switches the order to Discogs → MusicBrainz → Bandcamp instead of the default MusicBrainz → Discogs → Bandcamp. Reason: Discogs' structured search returns per-release `label` data that MusicBrainz `/release-group` doesn't carry. Both `DiscoveryAgentTools.search()` and `ReleaseSearchFlowService.searchDefault()` compute this the same way — see each for the concrete engine list.

### `MusicBrainzClient` — BROWSE vs LOOKUP
- **LOOKUP** (has `release` or `recording`): `/release` endpoint, as before (limit **100** — MusicBrainz hard-caps at 100; 150 silently errors).
- **BROWSE** (`isBrowseQuery()`): tries `/release-group` FIRST (one row per album concept, dedup'd across pressings — far less noisy than `/release` for a bare style+year query), falls back to `/release` if empty. Lucene field for date differs: `firstreleasedate:` on `/release-group` vs `date:` on `/release`; `/release-group` has no `country`/`label`/`catno` fields.
- `findArtistMbid(artistName)` — new lookup (`/artist?query=`) used by `findSimilar`.
- **Group sort order is SCORE first, year only a tiebreaker** (`mapToGroupedDomain`). Was year-ascending first — same-name-collision releases (a different artist who happens to share/partially-match the searched name) would outrank the actual best match purely for being older, surfacing a totally unrelated result as the top card. Score (MusicBrainz's own Lucene relevance) must dominate; year only orders among equally-relevant matches.

### `DiscogsClient` — structured params
`addDiscogsParameters` maps `MetadataSearchRequest` fields onto Discogs' own structured filters (`artist`, `release_title`, `track`, `year`, `format`, `catno`, `label`, `style`, `country`) instead of concatenating everything into the free-text `q` param — `q` is relevance-ranked full-text search, so "trance 1994" in `q` matched titles containing "trance" literally, not releases tagged trance from 1994. `type=release` only (was `master,release` — `mapToDomain` already filtered to `"release"` type, so `master` was pure noise). `mapToDomain` calls `filterByRequestedArtist` right after the type filter — Discogs' `artist=` param is a relevance hint, not an exact filter, so a query like "Alpi - Discontinuity" returns releases from every unrelated Discogs artist entity disambiguated as "Alpi", "Alpi (2)", "Alpi (3)", etc; the filter keeps only results whose extracted artist string case-insensitively equals the requested artist, falling back to the unfiltered list if that empties it (loose-but-present beats exact-but-empty). Grouping in `mapToDomain` keys on **artist + title**, not title alone — a title-only key merged unrelated releases that happen to share a generic title (e.g. "Imaginary Landscapes" is both a well-known John Cage piece with a dozen reissues AND an unrelated electronic release; grouping by title alone mashed both into one release with garbage combined years/tags/label). Uses `LinkedHashMap` — a plain `groupingBy` uses a `HashMap` and silently destroys Discogs' relevance ordering.
`performSearch`/`retryWithoutArtist` route through a `@Lazy self` proxy reference so `@CircuitBreaker`/`@Retry` actually apply — calling a `@CircuitBreaker`-annotated method directly (`this.performSearch(...)`) bypasses the Spring AOP proxy entirely.
`getPrimaryVideoUrl(releaseId)` — first community-curated YouTube link from the release's `videos[]` (used by `ListenLinkResolver`).

---

## `ReleaseSearchFlowService` — ключові методи

| Метод | Призначення |
|-------|-------------|
| `searchWithFallback(query, engines...)` | Послідовний fallback по движках |
| `switchStrategyAndSearch(ctx)` | DIG_DEEPER: наступний engine по колу |
| `buildPageResponse(ctx, page)` | Одна release картка з пагінацією (⬅️/➡️ у самій картці, `CARD:`/`PAGE:` callback); зберігає `currentPage` через `searchContextService.updateCurrentPage()`. Це основний спосіб показу результатів — один пошук = одна картка + гортання, не список карток. |
| `buildReleaseDownloadCard(release, engine)` | Картка для download flow |

---

## Listen links (`ListenLinkResolver`)

`StreamingFlowService.getPlatformLinks()` prepends a resolved DIRECT listen link (🎯 button) before the generic per-platform search links, when one can be found:
- `BANDCAMP` → `release.masterId()` (the release page itself IS the direct link)
- `DISCOGS` → `DiscogsClient.getPrimaryVideoUrl()`, falls back to yt-music search if the release has no attached video
- `MUSICBRAINZ` → yt-music scraper search (`YtMusicScraperClient`, `sm-scraper` `/ytmusic/search`) by artist+title

No LLM, no free-text web search, no "verify" step — every source is either attached to the exact release or a structured artist+album match against a real catalog.

---

## Similar-music lookup (`ListenBrainzClient`)

`labs.api.listenbrainz.org/similar-artists/json` — free, no API key, CC0-licensed co-listen data. Used only by `DiscoveryAgentTools.findSimilar()` (see `agents/discovery/spec.md`) via `MusicBrainzClient.findArtistMbid()` → `ListenBrainzClient.findSimilarArtists()`. Library-internal similarity (own analyzed tracks) is a separate, unrelated path — `LibrarySimilarityService` in `libraryagent`, exposed via `LibraryAgentTools.findSimilarInLibrary` — pure audio-feature cosine similarity, no external API.

---

## Hard rules
1. `SearchContextService` — єдиний writer в `ChatStateStore` для `flow_key = "search"`.
2. `getReleaseMetadata(releaseId)` без `conversationId` → тільки in-memory cache, не ходить в store.
3. Discovery flow зберігає під `conversationId + ":d"`, потім `copySearchContext` дублює під основний ID.
4. `DownloadContextHolder` — окремий holder з flow_key `"dl_ctx"`, не тут.
5. `currentPage` читається `getTrackList` (DiscoveryAgent) щоб знати який реліз показати.
6. Engine order for a given `MetadataSearchRequest` is decided once per request (`isBrowseQuery()`) — never re-decided per engine attempt.

---

## SDD checkpoints
- Новий `SearchEngine` → `SearchEngineService` impl + реєстрація в `SearchEngineConfig`. `searchWithFallback` підхопить автоматично.
- Змінити порядок пошуку → порядок у `SearchEngine` enum, або `isBrowseQuery()` branch якщо порядок має залежати від типу запиту.
- Нове поле в `SearchContext` → оновити `SearchState` deserialization (Jackson) і всі місця де будується `SearchContext`.
