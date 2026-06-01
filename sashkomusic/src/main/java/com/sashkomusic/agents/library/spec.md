# LibraryAgentService — Spec

## Purpose
Застосовує бібліотечну операцію (рейтинг / energy / function / коментар) до поточного треку.
**Не LLM** — детермінований regex-парсер (`LibraryCommandParser`) перетворює
природну мову на одну з чотирьох команд. Мутація делегується FlowServices.

---

## Місце в потоці

```
MainAgentTools.manageLibrary()
  └─ LibraryAgentService.handle(LibraryRequest)
       ├─ LibraryCommandParser.parse(naturalCommand) → LibraryCommand
       ├─ DjTagContextHolder.getContext(conversationId) → активний трек
       └─ switch(command):
            ├─ Rate     → NowPlayingFlowService.rateTrack()    → RateTrackTaskEvent
            ├─ SetEnergy → DjTagFlowService.setDjEnergy()      → SetEnergyTaskEvent
            ├─ SetFunction → DjTagFlowService.setDjFunction()  → SetFunctionTaskEvent
            └─ AddComment → DjTagFlowService.addComment()      → AddCommentTaskEvent

async:
  libraryagent listener → оновлення тегів/DB → TrackUpdateResultEvent
    └─ TrackUpdateResultListener → chatBot.sendMessage(ConversationContext.from(...))
```

---

## Contract

### Input: `LibraryRequest`
```java
record LibraryRequest(String conversationId, String naturalCommand)
```

### Output: `LibraryResult`
```java
record LibraryResult(boolean success, String summary)
```
`summary` → рядок для LLM (`"оцінив на 5"`, `"нема активного треку — спочатку /np"`).
`BotResponse`-и вже в accumulator.

---

## Parser grammar (`LibraryCommandParser`)

| Command          | Тригери (будь-який збіг)                                        | Yields                        |
|------------------|-----------------------------------------------------------------|-------------------------------|
| `Rate(int stars)`| `rate N`, `оціни N`, `постав N`, `N stars`, `N зір`             | `nowPlayingFlowService.rateTrack(ctx, trackId, N)` |
| `SetEnergy(level)`| `energy N`, `енергія N`, `eN` (1–5)                            | `djTagFlowService.setDjEnergy(ctx, trackId, N)` |
| `SetFunction(fn)`| `intro/tool/banger/closer` та UA-аліаси нижче                  | `djTagFlowService.setDjFunction(ctx, trackId, fn)` |
| `AddComment(text)`| `comment <text>`, `коментар <text>` та варіанти               | `djTagFlowService.addComment(ctx, trackId, text)` |
| `Unknown(reason)`| Не розпізнано                                                   | `LibraryResult.failed(reason)` |

UA-аліаси `SetFunction`: `інтро→intro`, `тул/тool→tool`, `банжер→banger`, `клозер→closer`,
`марк/познач` як prefix → наступне слово — назва функції.

Parser — case-insensitive, trim whitespace. `null`/empty → `Unknown`.

---

## Pre-conditions

Для будь-якої команди крім `Unknown`:
- В `DjTagContextHolder` (persisted через `ChatStateStore`, `flow_key="dj_tag"`) має бути
  активний контекст для `conversationId` — тобто користувач раніше запустив `/np`.
- Якщо контексту нема → `LibraryResult.failed("нема активного треку — спочатку /np")`,
  жоден event не публікується.

`DjTagContext` виживає після рестарту (Postgres-backed через `JpaChatStateStore`).

---

## Event chain

```
LibraryAgentService → [RateTrackTaskEvent | SetEnergyTaskEvent | SetFunctionTaskEvent | AddCommentTaskEvent]
  └─ libraryagent listener:
       RateTrackListener / SetEnergyListener / SetFunctionListener / AddCommentListener
         └─ оновлює аудіотеги (JAudioTagger) + DB
              └─ publishes TrackUpdateResultEvent(conversationId, fieldUpdated, value, success)
                   └─ mainagent TrackUpdateResultListener
                        └─ chatBot.sendMessage(ConversationContext.from(dto.conversationId()), msg)
```

Всі DTO несуть `String conversationId` — відповідь потрапляє в правильний Telegram топік.

---

## Hard rules
1. **No LLM.** Якщо граматика росте понад regex → замінити `LibraryCommandParser` на
   `@AiService` (Haiku), але зберегти той самий `handle(LibraryRequest) → LibraryResult` contract.
2. Ніколи не писати в Telegram поза accumulator.
3. Кожен event ПОВИНЕН нести `conversationId` — інакше async listener не зможе відповісти.
4. Не читати теги з диску тут — активний трек вже є в `DjTagContextHolder`.

---

## Out of scope
- Запис тегів на диск → libraryagent listeners
- DB writes → libraryagent
- Пошук треку → вже зроблено в `/np`, зберігається в `DjTagContextHolder`

---

## SDD checkpoints (змінювати spec ДО коду)
- Нова операція:
  1. Додати `LibraryCommand.X` variant до sealed interface
  2. Regex pattern + match у `LibraryCommandParser`
  3. `case X ->` у `LibraryAgentService.route()`
  4. Новий event class + libraryagent listener + `TrackUpdateResultDto` variant
- Замінити парсер на LLM → swap implementation за `LibraryAgentService`.
  `LibraryCommandParserTest` залишається як regression suite.
