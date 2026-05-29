# DiscoveryAgent — Spec

## Purpose
Music search reasoning. Given a free-form query, chooses one or more search
engines, calls them via tools, and returns a one-line Ukrainian summary.
Owns its own per-chat memory — basis for future RAG.

## Inputs (LangChain4j surface)
- `@MemoryId long chatId`
- `@UserMessage String userMessage` — query text (possibly with `(prefer: discogs)` hint
  appended by `DiscoveryAgentService` if the caller had a preference)

## Inputs (service contract — `DiscoveryAgentService.handle`)
- `DiscoverRequest(chatId, query, preferredEngine)`

## Outputs (service contract)
- `DiscoverResult(success, summary, releases, engineUsed)`
- Side-effect 1: search results are saved into `SearchContextService`
  (so the user can later click `DL:` / `STREAM:` / `PAGE:` buttons on the cards).
- Side-effect 2 (when results non-empty): release cards are pushed into
  `ChatResponseAccumulator` via `ReleaseSearchFlowService.buildPageResponse(chatId, 0)`.
  This is **this agent's job**, not the main agent's — main only sees the
  `summary` string.

## Model
- `claude-haiku-4-5-20251001` (overridable via `agents.discovery.model-name`)
- `maxTokens` = 1024
- Memory: `MessageWindowChatMemory.withMaxMessages(20)` per `chatId`

## Tools (every public `@Tool` on `DiscoveryAgentTools`)

| Tool                  | Backend                  | Use when                                         |
|-----------------------|--------------------------|--------------------------------------------------|
| `searchMusicBrainz`   | MusicBrainz REST         | Default / canonical metadata                      |
| `searchDiscogs`       | Discogs REST             | Vinyl, labels, catalog numbers; user says discogs |
| `searchBandcamp`      | Bandcamp (jsoup)         | Independent / electronic; user says bandcamp      |
| `getPreviousSearches` | `SearchContextService`   | User refers to earlier search ("як минулого разу")|

Each search tool persists results into `SearchContextService` via
`saveSearchContext(...)`. That side-effect is the contract: callers expect
`SearchContextService` to reflect the latest search after `handle()` returns.

## Hard rules
1. Default to `searchMusicBrainz` first. Honor explicit hints (`discogs`,
   `bandcamp`) when present in the query.
2. On empty result, try **at most one** other source.
3. On user phrases like "ще / копай / дай ще" — switch to a source different
   from the last one used.
4. Never list each release in the reply — the calling tool renders cards.
   Reply with one short Ukrainian line (e.g. "знайшов 5 релізів на musicbrainz").
5. Reply ≤120 chars, lowercase, no markdown.
6. Never translate or transliterate artist / release names byte-for-byte —
   the underlying extractors (`SearchRequestExtractor`) already enforce this.

## Out of scope
- Talking to the user (only main does).
- Choosing a download source — that is the download agent's domain.
- Fetching tracklists / cover art — search engines do that, not the agent.

## SDD checkpoints (change here BEFORE code)
- New search engine → add a tool in `DiscoveryAgentTools` AND a
  `SearchEngineService` impl. Register in `SearchEngine` enum.
- Future RAG (vector store over past searches) → likely belongs as a new tool
  here (e.g. `searchSimilarToHistory`); the memory window already isolates
  per-chat context.
- Token spend → check `[agent=discovery]` trace logs.
