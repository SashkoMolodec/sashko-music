# MainAgent — Spec

> Feature flows: [/.specs/search.md](../../../../../../.specs/search.md) · [/.specs/download.md](../../../../../../.specs/download.md) · [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md)
> Async events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Точка входу для будь-якого **вільного тексту** від користувача. Вирішує який tool викликати (якщо взагалі), повертає коротку українську відповідь. Тільки цей агент говорить до користувача.

Slash-команди (`/library`, `/discovery`, `/np`, `/newtopic`, `/clear-context`) обходять MainAgent і йдуть напряму до sub-агентів через `UserInteractionOrchestrator`.

---

## LangChain4j interface

```java
public interface MainAgent {
    @SystemMessage(MainAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
```

| Параметр | Значення |
|----------|----------|
| `conversationId` | `"chatId"` для DM, `"chatId:topicId"` для групового топіку |
| `userMessage` | Вербатимний текст від користувача |
| Return | Коротка укр. відповідь ≤600 символів, lowercase, без markdown |

---

## Модель і пам'ять

| Параметр | Значення |
|----------|----------|
| Модель | `claude-sonnet-4-5` (override: `agents.main.model-name`) |
| maxTokens | 2048 |
| Memory window | 32 messages |
| Memory provider | `ChatLogBackedChatMemory` (backed by `chat_log` table) |
| Prompt caching | `cacheSystemMessages=true`, `cacheTools=true` |

`ChatLogBackedChatMemory` завантажує останні N рядків з `chat_log` при кожному виклику — тому MainAgent бачить повідомлення всіх сесій (`/library`, `/discovery`, free-text). Фінальна `AiMessage` зберігається в `chat_log` з `source='main'`.

Sub-агенти мають **окремі** memory IDs (`conversationId + ":d"`, `conversationId + ":lib"`) — histories не перемішуються.

---

## Tools

| Tool | Тригер | Delegates to |
|------|--------|-------------|
| `discoverMusic(query)` | будь-який запит про пошук / дослідження музики, артистів, лейблів, жанрів | `DiscoveryAgentService` → LLM path (Haiku) |
| `manageLibrary(command)` | будь-яка операція з власною бібліотекою: пошук, переміщення, DJ-тегування | `LibraryAgentService` → `LibraryAgent` (Haiku) |

`discoverMusic` — fire-and-forget для запитів; результат (`DiscoverResult.summary()`) вже відформатований `DiscoveryAgentService`. MainAgent не парсить `DiscoverResult` структурно.

Side-effect пошуку: release cards → `ChatResponseAccumulator` → Telegram до того як MainAgent пише summary.

---

## ChatResponseAccumulator bridge

Tools пушать `BotResponse` в accumulator під час `chat()`. Після завершення:
```
drain(conversationId) → drained cards  +  aiText(summary)  →  Telegram
```
`ProgressNotifier` надсилає "шукаю..." напряму (обходить accumulator) до завершення LLM.

---

## Slash-команди (обходять MainAgent)

`UserInteractionOrchestrator.processUserCommands()` перехоплює ці команди до `runMainAgent()`:

| Команда | Handler | LLM? |
|---------|---------|------|
| `/library <query>` | `LibraryAgentService.handle()` | Haiku тільки |
| `/discovery <query>` | `DiscoveryAgentService.handle()` | Haiku тільки |
| `/np` | `NowPlayingFlowService.nowPlaying()` | ні |
| `/newtopic` | `NewTopicFlowService.handle()` | ні |
| `/clearctx` | `chatLogService.deleteConversation()` + `chatMemoryStore.deleteMessages()` | ні |
| `/remove-release` | `RemoveReleaseFlowService.handleCommand()` | ні |
| `стоп` | `clearAllCaches()` | ні |

Відповіді slash-команд логуються в `chat_log` оркестратором (user message + assistant response), тому MainAgent бачить їх контекст при наступному free-text запиті.

---

## Hard rules
1. Рівно один tool per intent якщо підходить. Small talk / питання за щойно знайдений реліз → відповідь без tool (контекст є в `chat_log`).
2. Не вигадувати параметри — тільки те що сказав юзер.
3. Не описувати release картки в тексті — вони вже в accumulator.
4. Тільки MainAgent говорить до юзера. Sub-агенти → records/pushAll.
5. Callback-кнопки → `CallbackDispatcher`. Slash-команди → `UserInteractionOrchestrator`.
6. `discoverMusic` отримує query вербатимно. MainAgent не вирішує який пошуковий движок — це відповідальність DiscoveryAgent.

---

## Out of scope
- Callback-кнопки (`DL:`, `RATE:`, `STREAM:`, …) → `CallbackDispatcher`
- Slash-команди → `UserInteractionOrchestrator`
- Вибір пошукового движку (MusicBrainz / Discogs / Bandcamp) → `DiscoveryAgentTools`
- `digDeeper` → `DiscoveryAgentTools.digDeeper()`
- Форматування release списку → `DiscoveryAgentService.formatForMainAgent()`
- Завантаження музики → кнопка DL на картці
- Cross-conversation пам'ять

---

## SDD checkpoints
- Новий user intent → потрібен новий `@Tool`? Описати: тригер, параметри, return, side-effect.
- Зміна contract sub-агента → оновити рядок у таблиці Tools.
- Cost → перемикнути на Haiku через `agents.main.model-name`.
