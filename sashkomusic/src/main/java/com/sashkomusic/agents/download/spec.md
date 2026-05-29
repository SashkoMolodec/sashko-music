# DownloadAgentService — Spec

## Purpose
Initiate a download. **Not an LLM.** Thin contract over
`mainagent.download.MusicDownloadFlowService` — exists so that:
1. The MainAgent has a deterministic, predictable tool with a typed contract.
2. The transport (in-process today, A2A HTTP later) can swap without changing
   callers.

## Inputs (contract — `DownloadAgentService.handle`)
- `DownloadRequest`
  - `chatId`
  - `releaseId` — when the user is downloading something from a prior search
    result. Resolved against `SearchContextService.getReleaseMetadata`.
  - `artist + album` — when the user typed "скачай Burial Untrue" without a
    prior search.
  - `engine` (optional `DownloadEngine`) — usually `null`; engine selection
    is the FlowService's job (currently Qobuz-first).

Exactly one of `releaseId` or `(artist, album)` MUST be non-empty.

## Outputs (contract)
- `DownloadResult(success, summary)`
  - `summary` is a short human-friendly message (joined from FlowService
    responses), used as the LLM-facing tool return.
- Side-effect: actual `BotResponse`s from the FlowService are pushed into
  `ChatResponseAccumulator` so MainAgent can render them.

## Behavior

| Input case               | Path                                                              | Event published          |
|--------------------------|-------------------------------------------------------------------|--------------------------|
| `releaseId` present      | `musicDownloadFlowService.handleDownload(chatId, "DL:" + id)`     | `FilesSearchTaskEvent`   |
| `artist + album` present | `musicDownloadFlowService.getDownloadOptions(chatId, "$a $b")`    | `FilesSearchTaskEvent`   |
| neither present          | `DownloadResult.failed("нема що качати")`, no event               | —                        |

The default download engine for the first round-trip is `Qobuz` (see
`MusicDownloadFlowService.initiateDefaultDownloadSearch`). Fallback /
alternative sources are chosen later via the `SEARCH_ALT:` callback and the
`Map<DownloadEngine, DownloadFlowHandler>` strategy map.

## Async semantics
- This call is **fire-and-forget**: it publishes a Spring event and returns
  immediately. Download completion comes back as a separate
  `DownloadCompleteEvent` / `DownloadBatchCompleteEvent` flowing back to
  mainagent listeners — NOT through this agent's return value.

## Hard rules
1. No LLM. Reasoning about engine choice, fallback, or quality belongs in
   `MusicDownloadFlowService` and its `DownloadFlowHandler` strategies, not here.
2. Never construct Telegram-facing `BotResponse`s in this layer that are not
   already pushed into the accumulator by the FlowService.
3. Stable contract: if a remote A2A endpoint replaces this in-process service,
   the wire format is `DownloadRequest` → `DownloadResult`. Both are pure JSON
   records — no Spring or Java-only types leak.

## Out of scope
- File system writes (handled by `downloadagent`).
- Progress UI updates (handled by event listeners + accumulator).
- Tag writing post-download (handled by `libraryagent`).

## SDD checkpoints (change here BEFORE code)
- New download source → add a `DownloadEngine` enum value AND a
  `DownloadFlowHandler` impl. This spec stays the same; only the strategy map
  grows. No NL parsing here.
- Want to give the user choice between engines from NL? Add a tool-level
  override in `MainAgentTools.downloadMusic` (engine arg) — but keep the
  default deterministic.
