# LibraryAgentService — Spec

> Feature flow: [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md)
> Events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Застосовує бібліотечну операцію (рейтинг / energy / function / коментар) до поточного треку. **Не LLM** — детермінований regex-парсер (`LibraryCommandParser`). Мутація делегується FlowServices через events.

---

## Contract

```java
// Input
record LibraryRequest(String conversationId, String naturalCommand)

// Output
record LibraryResult(boolean success, String summary)
// summary → рядок для LLM ("оцінив на 5", "нема активного треку — спочатку /np")
```

---

## Parser grammar (`LibraryCommandParser`)

| Command | Тригери | Action |
|---------|---------|--------|
| `Rate(int stars)` | `rate N`, `оціни N`, `постав N`, `N stars`, `N зір`, `N/5` | `nowPlayingFlowService.rateTrack()` |
| `SetEnergy(level)` | `energy N`, `енергія N`, `eN` (1–5) | `djTagFlowService.setDjEnergy()` |
| `SetFunction(fn)` | `intro/tool/banger/closer`, UA-аліаси нижче | `djTagFlowService.setDjFunction()` |
| `AddComment(text)` | `comment <text>`, `коментар <text>` | `djTagFlowService.addComment()` |
| `Unknown(reason)` | Не розпізнано | `LibraryResult.failed(reason)` |

UA-аліаси `SetFunction`: `інтро→intro`, `тул/tool→tool`, `банжер→banger`, `клозер→closer`, `марк/познач` → наступне слово як функція.

Parser — case-insensitive, trim whitespace. `null`/empty → `Unknown`.

---

## Pre-conditions

Для будь-якої команди крім `Unknown`:
- `DjTagContextHolder` (`flow_key="dj_tag"`, Postgres-backed) має мати активний контекст для `conversationId` — тобто юзер запустив `/np`.
- Якщо нема → `LibraryResult.failed("нема активного треку — спочатку /np")`, event не публікується.

---

## Hard rules
1. **No LLM.** Якщо граматика росте понад regex → swap `LibraryCommandParser` на `@AiService` (Haiku), зберігаючи той самий contract.
2. Кожен event ПОВИНЕН нести `conversationId`.
3. Не читати теги з диску — трек вже в `DjTagContextHolder`.
4. Не писати в Telegram — тільки через accumulator або FlowService.

---

## Out of scope
- Запис тегів на диск → libraryagent listeners
- DB writes → libraryagent
- Пошук треку → `/np` + `DjTagContextHolder`

---

## SDD checkpoints
- Нова операція:
  1. `LibraryCommand.X` variant
  2. Regex в `LibraryCommandParser` + `@CsvSource` рядок у `LibraryCommandParserTest`
  3. `case X →` у `LibraryAgentService.route()`
  4. Новий event class + libraryagent listener
- Замінити парсер на LLM → swap implementation за `LibraryAgentService`. `LibraryCommandParserTest` залишається як regression suite.
