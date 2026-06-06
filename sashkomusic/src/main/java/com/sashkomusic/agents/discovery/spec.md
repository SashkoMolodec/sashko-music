# DiscoveryAgent — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)

## Purpose
Пошук і дослідження музики по 3 джерелах. Отримує вільний запит, сам вирішує стратегію (движок, `digDeeper`, треклист). Повертає відформатований summary для MainAgent і будує release картки через accumulator.

Викликається двома шляхами:
1. **Free-text**: `MainAgentTools.discoverMusic()` → `DiscoveryAgentService.handle()` → LLM path
2. **Slash**: `UserInteractionOrchestrator` → `/discovery <query>` → `DiscoveryAgentService.handle()` → LLM path

---

## LangChain4j interface

```java
public interface DiscoveryAgent {
    @SystemMessage(DiscoveryAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
```

| Параметр        | Значення |
|-----------------|----------|
| `conversationId`| `conversationId + ":d"` — ізольований від MainAgent memory ID |
| `userMessage`   | Query вербатимно |
| Return          | Рядок який `DiscoveryAgentService` використовує як fallback summary |

Чому окремий `:d` memory ID: якби Main і Discovery ділили memory, `tool_use` без `tool_result` в history викликав би помилку Claude API.

---

## Модель і пам'ять

| Параметр      | Значення |
|---------------|----------|
| Модель        | `claude-haiku-4-5-20251001` (override: `agents.discovery.model-name`) |
| maxTokens     | 1024 |
| Memory window | 16 messages |
| Memory store  | `PostgresChatMemoryStore`, ключ: `conversationId + ":d"` |

Memory **не очищається** між викликами `handle()` — тому `digDeeper` може читати попередній контекст пошуку (engine + results) з `:d` history.

---

## Tools (`DiscoveryAgentTools`)

### `search(query, conversationId)`
1. `SearchRequestExtractor.extract(query)` → `MetadataSearchRequest`
2. Перебирає `SearchEngine.values()` (MusicBrainz → Discogs → Bandcamp), зупиняється на першому hit
3. `searchContextService.saveSearchContext(conversationId, engine, query, request, releases)`
4. Return: `"found N releases on engine"` або `"not found on any source"`

### `digDeeper(conversationId)`
Читає попередній `rawInput` і `source` з `:d` контексту, переходить до наступного движку по колу (`(ordinal + 1) % values.length`).
Тригер: "копай", "ще копай", "try another source", "dig deeper".

### `getTrackList(conversationId)`
Читає `searchResults` з основного (не `:d`) conversationId — бере реліз по `currentPage` (яка зберігається у `SearchContext.currentPage` в ChatStateStore). Lazy-fetches повний треклист через `getMetadataWithTracks()`. Повертає пронумерований список треків.
Тригер: "які треки", "tracklist", "що на альбомі".

**Важливо:** `conversationId` що передається в tool — це `:d` ID. Метод сам стрипає суфікс: `mainId = conversationId.endsWith(":d") ? conversationId.substring(0, len-2) : conversationId`, після чого читає `SearchContext` під основним ID.

---

## DiscoveryAgentService — відповідальність

`handle(DiscoverRequest)`:
1. Якщо `preferredEngine != null` → `handleDirect()` (LLM не викликається, `runSearch()` напряму)
2. Інакше → `handleViaLlm()` → snapshot `rawInputBefore` → `discoveryAgent.chat()` → snapshot `rawInputAfter`
3. `buildResult(conversationId, discoveryMemoryId, summary, rawInputBefore)`:
   - Якщо `rawInputBefore != rawInputAfter` (нова search відбулась) → `formatForMainAgent()` + replace accumulator картками → `DiscoverResult.found(...)`
   - Якщо `rawInput` не змінився (наприклад, викликано тільки `getTrackList`) → повернути summary DiscoveryAgent без форматування
4. Якщо нічого не знайшов → `DiscoverResult.empty(summary)`

`formatForMainAgent()` — формує агрегований summary для MainAgent: кількість, діапазон років, розбивка по типах (album/EP/single/other), топ-3 лейбли, топ-5 тегів. **Не перелічує кожен реліз** — MainAgent не парсить `DiscoverResult` структурно, тільки читає `.summary()`. Tracklist-відповідь (`getTrackList`) передається без агрегації — повний пронумерований список дослівно.

**`DiscoveryAgentPrompts.SYSTEM` ключові правила:**
- Для SEARCH-запитів: передати query прямо в `search` tool.
- Для TRACKLIST-запитів: **завжди** викликати `getTrackList` — ніколи не відповідати з пам'яті.
- Для "ще копай"/"dig deeper": викликати `digDeeper`, не `search`.
- Якщо `getTrackList` повернув треки — вивести **повний** пронумерований список дослівно.

---

## Hard rules
1. DiscoveryAgent сам вирішує стратегію — MainAgent не передає `preferredEngine` (той параметр залишився в `DiscoverRequest` для можливого майбутнього direct path).
2. `search()` пробує всі движки по порядку — не зупиняється на порожньому результаті, поки є що пробувати.
3. `digDeeper()` читає `:d` контекст — не очищати пам'ять між викликами.
4. Відповідь DiscoveryAgent — рядок для MainAgent (не для юзера напряму).
5. Якщо нічого не знайшов → попросити уточнення (рік, лейбл, жанр, країна).

---

## Out of scope
- Говорити до користувача напряму — тільки через summary → MainAgent
- Вибір джерела завантаження — DownloadAgent / кнопка DL
- Бібліотечні операції → LibraryAgent

---

## SDD checkpoints
- Новий пошуковий движок → `SearchEngine` enum value + `SearchEngineService` impl. Tool-код не змінюється — перебирає `values()`.
- Змінити порядок движків → змінити порядок у `SearchEngine` enum.
- Новий тип запиту (напр., RAG) → новий `@Tool` тут + рядок у таблиці Tools.
