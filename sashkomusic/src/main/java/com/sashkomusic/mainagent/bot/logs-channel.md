# TelegramLogsChannel — Spec

## Purpose

Centralised router that sends operational notifications to a dedicated Telegram "logs" topic
instead of the user-facing main/default topic. Keeps the user's conversation clean while
preserving full observability in a separate channel.

## Config

| Property | Env var | Default | Notes |
|---|---|---|---|
| `telegram.default-chat-id` | `TGBOT_DEFAULT_CHAT_ID` | required | Same group as the main bot |
| `telegram.logs-topic-id` | `TGBOT_LOGS_TOPIC_ID` | `null` | If absent all `send()` calls are silent no-ops |

---

## What goes to the logs topic

| Source | Message | Class |
|---|---|---|
| Tag changes | Full formatted "🎵 оновлено теги треків" card (same text as before, redirected) | `TagChangesNotificationListener` |
| Smartlist created | "✅ смартлист 'name' створено — N треків" | `SmartlistCreationFlowService.confirm()` |
| Smartlists regenerated | "🔄 смартлисти оновлено: N/M" | `SmartlistLogListener` ← `SmartlistsRegeneratedEvent` |
| Agent flow cost | "[flow=XYZ] TOTAL cost=$0.0042" | `UserInteractionOrchestrator.logFlowCost()` |

---

## Event flow for smartlist regeneration

```
TrackUpdateResultEvent / TagChangesNotificationEvent / … (libraryagent)
  → SmartlistRegenerationListener (@Async)
    → SmartlistService.regenerateAll() → RegenerationResult(count, total)
    → publishes SmartlistsRegeneratedEvent(count, total)
      → SmartlistLogListener (@Async, mainagent)
        → TelegramLogsChannel.send("🔄 смартлисти оновлено: N/M")
```

`SmartlistsRegeneratedEvent` is the boundary between libraryagent (no Telegram) and mainagent
(owns I/O). `SmartlistRegenerationListener` publishes it; `SmartlistLogListener` consumes it.

---

## What does NOT go to the logs topic

- Download CLI output → `TelegramDownloadLogStreamer` (separate, same logs topic, not via this class)
- User-facing search results, AI summaries, DJ-tag confirmations → main/default topic only
- Errors / stack traces → SLF4J only

---

## Hard rules

1. `TelegramLogsChannel.send()` is a **no-op** when `logs-topic-id` is not configured — callers must not null-check.
2. Messages are plain text (or existing Telegram Markdown from formatters). No HTML.
3. `TelegramLogsChannel` lives in `mainagent.bot` — it must NOT be injected into `libraryagent` or `downloadagent`.
4. If a new operational event should go to the logs topic from libraryagent: add a new `*Event`, publish from libraryagent, listen in a `*LogListener` in mainagent.
