# MainAgent — Spec

## Purpose
Router. Takes free-form Ukrainian/English user text from Telegram and decides
which sub-agent / FlowService should handle it. Speaks to the user; sub-agents
do not.

## Inputs
- `long chatId` — Telegram chat id.
- `String userMessage` — verbatim free-form text from the user.
  (Slash commands, the literal `стоп`, and button callbacks are filtered out
  BEFORE this agent — they never reach `MainAgent.chat`.)

## Outputs
- `String` — final short Ukrainian summary line (≤120 chars, lowercase, no markdown).
- Side-effect: zero or more `BotResponse`s pushed into `ChatResponseAccumulator`
  by tools. Telegram bot drains accumulator AFTER `chat()` returns; if drain
  is empty, the summary is sent on its own.

## Model
- `claude-sonnet-4-6` (overridable via `agents.main.model-name`)
- `maxTokens` = 2048
- Memory: `MessageWindowChatMemory.withMaxMessages(30)` per `chatId`

## Tools (every public `@Tool` on `MainAgentTools`)

| Tool            | When to call                                         | Returns to LLM           | Side-effect pushed by sub-agent |
|-----------------|------------------------------------------------------|--------------------------|---------------------------------|
| `findMusic`     | Free-form discovery request                          | "знайшов 5 релізів…"     | release cards (via `DiscoveryAgentService`) |
| `downloadMusic` | Explicit "скачай / завантаж / download" verb         | "почав качати…"          | progress text (via `DownloadAgentService`) |
| `manageLibrary` | Rate / tag / comment for currently-playing track     | "оцінив на 5"            | confirmation text (via `LibraryAgentService`) |

Streaming links are intentionally NOT a tool. The user clicks the 🎧 button on
a release card; that fires `STREAM:` → `CallbackDispatcher` → `StreamingFlowService`
directly, bypassing the LLM. Don't re-introduce a tool for it — it would just
duplicate the button path and burn tokens.

## Hard rules
1. Pick **exactly one** tool when the request matches one.
2. Never call a tool with parameters the user did not mention (no hallucinated
   artists / albums).
3. Small talk / unclear → short Ukrainian reply, **no tool call**.
4. Never narrate search results yourself — the tool already pushed cards.
5. Reply ≤120 chars, lowercase, no markdown.
6. Speaking to Telegram is **this agent's job only**. Sub-agents return
   records / push `BotResponse`s — they never construct a `BotResponse` for direct send.
7. Main does NOT know about UI rendering. Tools call `*AgentService.handle(*Request)`
   and return the `summary` field. Sub-agent is responsible for pushing
   `BotResponse`s into the accumulator. If you find yourself injecting a
   `*FlowService` or `ChatResponseAccumulator` into `MainAgentTools`, you're
   leaking sub-agent responsibility upward — push it down instead.

## Out of scope
- Anything the user did via a button (handled by `CallbackDispatcher` upstream).
- Anything triggered by a slash command (handled by `UserInteractionOrchestrator` upstream).
- Maintaining cross-chat memory (each `chatId` has its own window).

## SDD checkpoints (change here BEFORE code)
- New user intent → does it map to an existing tool, or do you need a new `@Tool`?
- New tool → declare here first: name, when-to-call, return shape, accumulator effect.
- Cost concerns → flip model to Haiku via `agents.main.model-name`; assess token
  spend via `AgentTraceListener` logs (`[agent=main] tokensIn/tokensOut`).
