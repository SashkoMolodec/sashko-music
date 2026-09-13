# Search Feature

## Purpose
Пошук музичних релізів по 3 джерелах. Результати зберігаються в сесії і є основою для завантаження, стрімінгу, і обговорення.

---

## Flows

### 1. Free-text → MainAgent → `findMusic` tool
```
User: "знайди Burial Untrue"
  └─ MainAgent.chat()
       └─ findMusic("Burial Untrue", conversationId)
            ├─ ProgressNotifier → "🔍 шукаю..."
            ├─ DiscoveryAgentService.handle(DiscoverRequest(query=..., engine=null))
            │    └─ [LLM path] DiscoveryAgent.chat()
            │         └─ calls search() tool
            │              ├─ SearchRequestExtractor: "Burial Untrue" → {artist:"Burial", release:"Untrue"}
            │              ├─ tries engines sequentially: MUSICBRAINZ → first hit → stop
            │              └─ SearchContextService.saveSearchContext(conversationId+":d", ...)
            ├─ copySearchContext(conversationId+":d", conversationId)
            ├─ ReleaseSearchFlowService.buildPageResponse() → cards → accumulator
            └─ return DiscoverResult(summary, releases, engine)
```

### 2. Explicit engine — `findMusicOnDiscogs / Bandcamp / MusicBrainz` tool
```
findMusicOnDiscogs("query", conversationId)
  └─ DiscoveryAgentService.handle(DiscoverRequest(query, preferredEngine=DISCOGS))
       └─ [direct path, NO LLM] DiscoveryAgentTools.runSearch(DISCOGS, query)
            └─ same save + page build
```

### 3. `DIG_DEEPER` button (callback)
```
User clicks "⛏️ глибше" on search results
  └─ ReleaseSearchFlowService.switchStrategyAndSearch(ctx, msgId)
       ├─ reads current engine from SearchContextService
       ├─ picks next engine (MUSICBRAINZ → DISCOGS → BANDCAMP → cycle)
       └─ runs search on that engine
```

### 4. `CARD:` button — expand card
Показує full card для конкретного releaseId з поточної сесії. Підтягує треки через `getMetadataWithTracks`.

### 5. `PAGE:` button — pagination
Navigates between result pages. Results stay in SearchContextService.

### 6. `discussRelease(question)` tool
```
User: "які треки на цьому альбомі?"
  └─ discussRelease("які треки", conversationId)
       ├─ SearchContextService.getSearchResults(conversationId) → first release
       ├─ getMetadataWithTracks(releaseId) → fetches tracks if not cached
       └─ return formatted track listing
```

---

## Search engines

| Engine | Class | API | Used for |
|--------|-------|-----|---------|
| `MUSICBRAINZ` | `MusicBrainzClient` | musicbrainz.org API v2 | Default first try, повна дискографія; `/release-group` for style+year browse queries |
| `DISCOGS` | `DiscogsClient` | discogs.com API | Vinyl/collector, obscure releases, per-release label data |
| `BANDCAMP` | `BandcampSearchClient` | sm-scraper `/bandcamp/search` | Independent artists, прямі посилання |

**Priority order depends on query shape** (`MetadataSearchRequest.isBrowseQuery()`):
- **LOOKUP** (has artist/release/recording): MUSICBRAINZ → DISCOGS → BANDCAMP (stop at first hit)
- **BROWSE** (style/year only, no title — e.g. "trance 1994"): DISCOGS → MUSICBRAINZ → BANDCAMP —
  Discogs carries per-release `label` data that MusicBrainz's `/release-group` browse endpoint doesn't.

Показ результатів: до `search.cards.max` (дефолт 4) окремих карток-повідомлень (`ReleaseSearchFlowService.buildTopCardsResponse`)
замість однієї картки з пагінацією — кожна зі своїми ⬅️/➡️/🎧/⬇️ що й далі гортають повний список.

Similarity ("хочу схоже на X"): `DiscoveryAgentTools.findSimilar()` via ListenBrainz co-listen data → related artists → MusicBrainz releases. See `agents/discovery/spec.md`.

---

## Key classes

| Class | Responsibility |
|-------|---------------|
| `SearchContextService` | Головний state: cache metadata по releaseId, persist search per conversationId через ChatStateStore (flow_key: `"search"`) |
| `ReleaseSearchFlowService` | Build release cards, pagination, DIG_DEEPER logic |
| `SearchRequestExtractor` | Haiku LLM: free-text query → `MetadataSearchRequest` {artist, release, year, label, country, format} |
| `MetadataSearchRequest` | Structured search params |
| `ReleaseMetadata` | One release result: id, title, artist, year, label, trackCount, source URL |

---

## State after search

`SearchContextService` persists to `chat_state` table:
```
flow_key = "search"
payload  = SearchState {
  context: SearchContext { source, request, rawInput, releaseIds[] }
  releases: ReleaseMetadata[]
}
```
In-memory `releaseMetadataCache: Map<releaseId, ReleaseMetadata>` — перебудовується з БД при промаху.

**getReleaseMetadata(releaseId, conversationId)** — при промаху кешу підтягує всю SearchState з БД і заповнює кеш. Завдяки цьому кнопки `DL:` на старих картках після рестарту JVM знову працюють.

---

## Release card format (Telegram)

```
🎵 Artist — Title (Year)
Label · Format · Country
[🔗 посилання]
[1] Track 1
[2] Track 2
...

[📋 Деталі]  [DL]  [▶ Stream]  [⛏ Глибше]
```

---

## Out of scope
- Локальна бібліотека — `/process` в [library-processing.md](library-processing.md)
- Завантаження — [download.md](download.md)
