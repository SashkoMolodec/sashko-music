# MainAgent — Spec

## Purpose
Точка входу для будь-якого вільного тексту від користувача. Отримує повідомлення,
вирішує який інструмент (якщо взагалі) викликати, повертає коротку українську відповідь.
Тільки цей агент говорить до користувача — sub-агенти повертають результати, але не надсилають повідомлення самостійно.

---

## Місце в потоці

```
TelegramChatBot.consume()
  └─ UserInteractionOrchestrator.handleUserRequest()
       ├─ "стоп" → clearAllCaches
       ├─ OngoingFlow? → handle (multi-turn)
       ├─ slash command (/np /process /reprocess /newtopic) → FlowService
       └─ runMainAgent(ctx, text)
            ├─ responseAccumulator.begin(conversationId)
            ├─ mainAgent.chat(conversationId, text)   ← ТУТ
            │    └─ [tool calls під час chat()]
            │         └─ sub-agent pushAll → accumulator
            ├─ responseAccumulator.drain(conversationId) → BotResponse[]
            └─ aiText(summary) appended if non-blank → все надсилається в Telegram
```

Слеш-команди, "стоп" і callback-кнопки **до цього агента не доходять** —
вони перехоплені вище в `UserInteractionOrchestrator`.

---

## LangChain4j interface

```java
public interface MainAgent {
    @SystemMessage(MainAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
```

| Параметр        | Значення                                                              |
|-----------------|-----------------------------------------------------------------------|
| `conversationId`| `"chatId"` для DM/General, `"chatId:topicId"` для групового топіку   |
| `userMessage`   | Вербатимний текст від користувача                                      |
| Return          | Коротка укр. відповідь (≤120 символів, lowercase, без markdown)        |

---

## Модель і пам'ять

| Параметр         | Значення                                                                       |
|------------------|--------------------------------------------------------------------------------|
| Модель           | `claude-sonnet-4-6` (override: `agents.main.model-name`)                       |
| maxTokens        | 2048 (override: `agents.main.max-tokens`)                                      |
| Memory window    | `MessageWindowChatMemory.maxMessages(32)` — приблизно 10–16 ходів розмови     |
| Memory store     | `PostgresChatMemoryStore` → таблиця `conversation_messages`                    |
| Memory ID        | `conversationId` (наприклад `"-1003551198668"` або `"-1003551198668:42"`)      |
| Prompt caching   | `cacheSystemMessages=true`, `cacheTools=true`                                  |

**Про вікно:** хід без tool-call = 2 повідомлення (USER+AI). Хід з tool-call = 4
(USER + AI(tool_use) + TOOL_RESULT + AI(text)). При переповненні LangChain4j
автоматично витискає найстаріші повідомлення. DiscoveryAgent має **окремий** memory ID
(`conversationId + ":d"`) щоб їх message history не перемішувалась.

---

## Tools (`MainAgentTools`)

| Tool                  | Коли викликати                                                   | Що повертає LLM-у                       | Side-effect в accumulator                     |
|-----------------------|------------------------------------------------------------------|-----------------------------------------|-----------------------------------------------|
| `findMusic`           | Загальний discovery без конкретного джерела                      | formatted release list або summary      | release cards (через `DiscoveryAgentService`) |
| `findMusicOnDiscogs`  | Юзер явно каже "discogs" / "дискогс"                             | formatted release list або summary      | release cards                                 |
| `findMusicOnBandcamp` | Юзер явно каже "bandcamp" / "бандкемп"                           | formatted release list або summary      | release cards                                 |
| `findMusicOnMusicBrainz`| Юзер явно каже "musicbrainz"                                   | formatted release list або summary      | release cards                                 |
| `digDeeper`           | Юзер каже "копай" / "ще копай" / "dig deeper" / "try another"   | formatted release list або summary      | release cards                                 |
| `downloadMusic`       | Явний "скачай / завантаж / download"                             | summary від `DownloadAgentService`      | progress text + download options              |
| `discussRelease`      | Питання про щойно знайдений реліз (треки, жанр, рік)            | formatted release info                  | нічого                                        |
| `manageLibrary`       | Rate/energy/function/comment для поточного треку                 | summary від `LibraryAgentService`       | confirmation text                             |

### Search tools — спільна реалізація
Всі 5 search tools делегують у `runDiscovery(DiscoverRequest)` → `DiscoveryAgentService.handle()`.
Різниця тільки в `preferredEngine` поля `DiscoverRequest`.

### `findMusic`
1. `ProgressNotifier.notify(ctx, "🔍 шукаю...")`
2. `DiscoverRequest.of(conversationId, query)` — `preferredEngine = null`
3. `DiscoveryAgentService` → LLM path (MusicBrainz → Discogs → Bandcamp, зупиняється на першому хіті)

### `findMusicOnDiscogs` / `findMusicOnBandcamp` / `findMusicOnMusicBrainz`
1. `ProgressNotifier.notify(ctx, "🔍 шукаю на <engine>...")`
2. `DiscoverRequest.of(conversationId, query, engine)` — `preferredEngine` заповнений
3. `DiscoveryAgentService` → **direct path** (обходить LLM, кличе engine напряму)

### `digDeeper`
1. Читає з `SearchContextService`: `getRawInput(conversationId)` + `getSource(conversationId)`
2. Якщо попереднього пошуку нема → повертає `"нема попереднього пошуку — спочатку знайди щось"`
3. `nextEngine = SearchEngine.values()[(lastEngine.ordinal() + 1) % engines.length]`
   (цикл: MusicBrainz → Discogs → Bandcamp → MusicBrainz → …)
4. `ProgressNotifier.notify(ctx, "🔍 копаю на <nextEngine>...")`
5. `DiscoverRequest.of(conversationId, lastRawQuery, nextEngine)` → direct path

### `downloadMusic`
1. `ProgressNotifier.notify(ctx, "⏳ шукаю на soulseek...")`
2. `DownloadAgentService.handle(DownloadRequest.byQuery(conversationId, artist, album))`
3. Fire-and-forget — результат прийде через async event (`DownloadBatchCompleteEvent`)

### `discussRelease`
- Читає `SearchContextService.getSearchResults(conversationId)` (in-memory)
- Немає контексту → `"no release context — user should search first"`
- Підвантажує треклист через `getMetadataWithTracks` якщо потрібно

### `manageLibrary`
- `LibraryAgentService.handle(LibraryRequest.of(conversationId, command))`
- Потребує активного `/np` сесії (трек збережений в `DjTagContextHolder`)

---

## Bridge: ChatResponseAccumulator

```
runMainAgent():
  1. accumulator.begin(conversationId)     ← очищає stale стан
  2. mainAgent.chat(...)                   ← під час виконання:
       tool calls → sub-agents → accumulator.pushAll(conversationId, responses)
  3. drained = accumulator.drain(id)       ← забираємо всі BotResponse-и
  4. if summary non-blank → append aiText(summary)
  5. send all to Telegram via TelegramChatBot.sendResponse()
```

`ProgressNotifier` обходить accumulator — надсилає через `TelegramClient` напряму,
щоб користувач бачив "шукаю..." ще до того як LLM завершить обробку.

---

## Async events (fire-and-forget)

Деякі операції не повертають результат синхронно — замість цього публікується Spring event:

```
downloadMusic tool → DownloadAgentService → MusicDownloadFlowService
  └─ publishes FilesSearchTaskEvent
       └─ downloadagent listener → ... → DownloadBatchCompleteEvent
            └─ mainagent DownloadBatchCompleteListener
                 └─ chatBot.sendMessage(ConversationContext.from(dto.conversationId()), msg)
```

Всі async DTOs несуть `conversationId` (рядок), а listener реконструює
`ConversationContext.from(conversationId)` щоб надіслати відповідь у правильний топік.

---

## Hard rules
1. Викликати **рівно один** інструмент якщо запит підходить.
2. Не вигадувати параметри — тільки те що сказав користувач.
3. Small talk / незрозуміло → коротка відповідь, **без tool call**.
4. Не описувати release картки в тексті — вони вже в accumulator.
5. Відповідь ≤120 символів, lowercase, без markdown.
6. Тільки MainAgent говорить до користувача. Sub-агенти → records/pushAll.
7. Не інжектувати `FlowService` або `ChatResponseAccumulator` в `MainAgentTools` —
   тільки через sub-agent services.

---

## Out of scope
- Callback-кнопки (`DL:`, `RATE:`, `STREAM:`, `CARD:`) → `CallbackDispatcher`
- Slash-команди → `UserInteractionOrchestrator`
- Cross-conversation пам'ять (кожен `conversationId` — окреме вікно)
- Рендеринг UI — тільки push `BotResponse` в accumulator

---

## SDD checkpoints (змінювати spec ДО коду)
- Новий user intent → потрібен новий `@Tool`? Або покривається існуючим?
- Новий `@Tool` → описати: назва, умова виклику, параметри, return, side-effect.
- Зміна contract sub-агента → оновити відповідну секцію Tools.
- Великий cost → перемикнути на Haiku через `agents.main.model-name`;
  моніторинг через `[agent=main]` trace logs.
