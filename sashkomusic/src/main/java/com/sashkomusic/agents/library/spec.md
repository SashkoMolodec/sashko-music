# LibraryAgentService — Spec

## Purpose
Apply a library operation (rate / energy / function / comment) to the
currently-playing track. **Not an LLM** — a deterministic regex parser
(`LibraryCommandParser`) maps natural language to one of four enum-shaped
commands. The actual mutation is delegated to existing FlowServices.

## Inputs (contract — `LibraryAgentService.handle`)
- `LibraryRequest(chatId, naturalCommand)`
  - `naturalCommand` is the user's free-form text in Ukrainian or English
    (e.g. `оціни 5`, `energy 3`, `марк банжер`, `коментар крутий бенгер`).

## Outputs (contract)
- `LibraryResult(success, summary)`
  - `summary` is a short confirmation used as the LLM-facing tool return.
- Side-effect: per-domain `BotResponse`s pushed into `ChatResponseAccumulator`.
- Side-effect: Spring event published — `RateTrackTaskEvent`,
  `SetEnergyTaskEvent`, `SetFunctionTaskEvent`, or `AddCommentTaskEvent`.

## Parser grammar (`LibraryCommandParser`)

| Command         | Triggers (any one match)                          | Yields                              |
|-----------------|---------------------------------------------------|-------------------------------------|
| `Rate(int)`     | `rate N`, `оціни N`, `постав N`, `N stars/зір`    | `nowPlayingFlowService.rateTrack`   |
| `SetEnergy(EN)` | `energy N`, `енергія N`, `eN`                     | `djTagFlowService.setDjEnergy`      |
| `SetFunction()` | `марк/познач intro/tool/banger/closer (or UA)`    | `djTagFlowService.setDjFunction`    |
| `AddComment()`  | `comment <text>`, `коментар <text>` (and variants)| `djTagFlowService.addComment`       |
| `Unknown`       | otherwise                                          | `LibraryResult.failed(reason)`      |

UA aliases normalised to canonical English: `інтро→intro`, `тул→tool`,
`банжер→banger`, `клозер→closer`.

## Pre-conditions
- For everything except `Unknown`: there MUST be a DjTagContext for `chatId`
  (i.e. user has run `/np` recently). Otherwise:
  `LibraryResult.failed("нема активного треку — спочатку /np")` and no event.
- `DjTagContext` is now persisted via `ChatStateStore` (flow_key=`dj_tag`),
  so it survives restart.

## Hard rules
1. No LLM. If grammar grows beyond regex feasibility, upgrade
   `LibraryAgentService` to a real LangChain4j `@AiService` — but keep the
   same `handle(LibraryRequest) → LibraryResult` contract so callers
   (MainAgent tool) don't change.
2. Never write to Telegram outside the accumulator.
3. Every event published here MUST carry `chatId` so the libraryagent
   listener can echo a result back through `TrackUpdateResultEvent`.
4. Parser is case-insensitive and trims whitespace; null/empty → `Unknown`.

## Out of scope
- File-tag write on disk (the libraryagent listeners do that).
- DB writes (libraryagent does that on event receipt).
- Track lookup (already done in `/np`, stored in `DjTagContext`).

## SDD checkpoints (change here BEFORE code)
- New library operation:
  1. Add a `LibraryCommand.X` variant to the sealed interface.
  2. Add the regex pattern + match branch in `LibraryCommandParser`.
  3. Add the case in `LibraryAgentService.handle`.
  4. Add a new event class + producer + libraryagent listener.
- Replace parser with LLM later → swap implementation behind
  `LibraryAgentService`. Tests still pass because they target
  `LibraryAgentService.handle`, not the parser directly (except
  `LibraryCommandParserTest`, which can stay or move).
