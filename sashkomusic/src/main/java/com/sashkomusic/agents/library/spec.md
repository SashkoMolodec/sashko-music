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

## Library Full-Text Search (`LibrarySearchService`)

Окремий сервіс в `libraryagent.domain.service`. Не LLM — чистий PostgreSQL FTS.

### Індекс

Колонка `releases.search_vector tsvector` з GIN-індексом. Оновлюється явно — через `indexRelease(id)` або `reindexAll()`.

Документ будується з чотирьох полів з різними вагами:

| Вага | Поле | Пріоритет при ранжуванні |
|------|------|--------------------------|
| `A`  | `releases.title` + `artists.name` | найвищий |
| `B`  | `tags.name` (жанрові теги) | середній |
| `C`  | `tracks.title` | нижній |

Токенізатор: `simple_unaccent` — кастомна конфігурація (PG text search config), яка опускає акценти (`unaccent`) та переводить у нижній регістр. Без стемінгу — підходить для кирилиці та англійських власних назв.

### Пошуковий запит

```sql
r.search_vector @@ websearch_to_tsquery('simple_unaccent', :query)
```

`websearch_to_tsquery` парсить вхід як Google-запит:
- `burial rival` → `'burial' & 'rival'` (AND)
- `burial OR godspeed` → `'burial' | 'godspeed'`
- `"rival dealer"` → точна фраза
- `-techno` → виключення

Ранжування: `ts_rank(search_vector, tsquery)` — 0..1, враховує частоту і вагу токена.

### API

```java
List<LibrarySearchResult> search(String query, int limit)  // FTS пошук
void indexRelease(Long releaseId)                          // оновити вектор одного релізу
int  reindexAll()                                          // batch UPDATE всіх релізів
```

`indexRelease` автоматично викликається в `ReleaseService.saveRelease()` після збереження.
`reindexAll` викликається на початку `/reprocess all`.

### Тригери індексації

| Момент | Метод |
|--------|-------|
| `/process` або `/reprocess` одного релізу | `indexRelease(id)` в `ReleaseService.saveRelease()` |
| `/reprocess all` | `reindexAll()` на початку + `indexRelease` для кожного обробленого |

---

## SDD checkpoints
- Нова операція:
  1. `LibraryCommand.X` variant
  2. Regex в `LibraryCommandParser` + `@CsvSource` рядок у `LibraryCommandParserTest`
  3. `case X →` у `LibraryAgentService.route()`
  4. Новий event class + libraryagent listener
- Замінити парсер на LLM → swap implementation за `LibraryAgentService`. `LibraryCommandParserTest` залишається як regression suite.
