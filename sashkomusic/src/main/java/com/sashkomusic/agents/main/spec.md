# MainAgent — Spec

> Feature flows: [/.specs/search.md](../../../../../../.specs/search.md) · [/.specs/download.md](../../../../../../.specs/download.md) · [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md)
> Async events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Точка входу для будь-якого вільного тексту від користувача. Вирішує який tool (якщо взагалі) викликати, повертає коротку українську відповідь. Тільки цей агент говорить до користувача.

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
| Return | Коротка укр. відповідь ≤120 символів, lowercase, без markdown |

---

## Модель і пам'ять

| Параметр | Значення |
|----------|----------|
| Модель | `claude-sonnet-4-6` (override: `agents.main.model-name`) |
| maxTokens | 2048 |
| Memory window | 32 messages |
| Memory store | `PostgresChatMemoryStore` |
| Prompt caching | `cacheSystemMessages=true`, `cacheTools=true` |

DiscoveryAgent має **окремий** memory ID (`conversationId + ":d"`) — histories не перемішуються.

---

## Tools

| Tool | Тригер | Delegates to |
|------|--------|-------------|
| `findMusic(query)` | "знайди", "пошукай" — без конкретного джерела | `DiscoveryAgentService` (LLM path) |
| `findMusicOnDiscogs(query)` | юзер явно каже "discogs" | `DiscoveryAgentService` (direct path) |
| `findMusicOnBandcamp(query)` | юзер явно каже "bandcamp" | `DiscoveryAgentService` (direct path) |
| `findMusicOnMusicBrainz(query)` | юзер явно каже "musicbrainz" | `DiscoveryAgentService` (direct path) |
| `digDeeper()` | "копай ще", "try another source" | `ReleaseSearchFlowService.switchStrategyAndSearch()` |
| `searchOwnLibrary(query)` | "чи є в мене", "в моїй бібліотеці", "є у тебе" | `LibrarySearchService.search()` (PostgreSQL FTS) |
| `downloadMusic(artist, album)` | "скачай", "завантаж" (явний намір) | `DownloadAgentService` |
| `discussRelease(question)` | питання про щойно знайдений реліз | `SearchContextService.getMetadataWithTracks()` |

Side-effect всіх search tools: release cards → `ChatResponseAccumulator`.
`downloadMusic` — fire-and-forget, результат через async events.

---

## ChatResponseAccumulator bridge

Tools пушать `BotResponse` в accumulator під час `chat()`. Після завершення:
```
drain(conversationId) → drained cards  +  aiText(summary)  →  Telegram
```
`ProgressNotifier` надсилає "шукаю..." напряму (обходить accumulator) до завершення LLM.

---

## Hard rules
1. Рівно один tool per intent якщо підходить. Small talk → відповідь без tool.
2. Не вигадувати параметри — тільки те що сказав юзер.
3. Не описувати release картки в тексті — вони вже в accumulator.
4. Тільки MainAgent говорить до юзера. Sub-агенти → records/pushAll.
5. Callback-кнопки, slash-команди → `CallbackDispatcher` / `UserInteractionOrchestrator`, сюди не доходять.

---

## Out of scope
- Callback-кнопки (`DL:`, `RATE:`, `STREAM:`, …) → `CallbackDispatcher`
- Slash-команди → `UserInteractionOrchestrator`
- Cross-conversation пам'ять

---

## SDD checkpoints
- Новий user intent → потрібен новий `@Tool`? Описати: тригер, параметри, return, side-effect.
- Зміна contract sub-агента → оновити рядок у таблиці Tools.
- Cost → перемикнути на Haiku через `agents.main.model-name`.
