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
| ChatModel bean| `discoveryChatModel` (`AgentModelsConfig`) — **окремий** від спільного `haikuChatModel` (LibraryAgent + екстрактори), бо тільки цей несе Anthropic server-side `web_search` tool |
| maxTokens     | 1024 |
| Memory window | 16 messages |
| Memory store  | `PostgresChatMemoryStore`, ключ: `conversationId + ":d"` |

Memory **не очищається** між викликами `handle()` — тому `digDeeper` може читати попередній контекст пошуку (engine + results) з `:d` history.

**Web search — server-side, не local `@Tool`:** `discoveryChatModel` будується з `.serverTools(List.of(AnthropicServerTool.builder().type("web_search_20250305").name("web_search")...build()))`. Модель викликає `web_search` напряму на інфраструктурі Anthropic — результат приходить у **тій самій** відповіді API (`web_search_tool_result` content block), без жодного client-side виконання. Замінює колишній local `@Tool webSearch()` + `WebSearchService` (jsoup-скрапінг `html.duckduckgo.com`, крихкий: капчі, відсутність URL у відповіді). Верифіковано живим викликом до `claude-haiku-4-5-20251001` перед вмиканням.

---

## Tools (`DiscoveryAgentTools`)

### `search(query, conversationId)`
1. `SearchRequestExtractor.extract(query)` → `MetadataSearchRequest` — extracted **once** per call, reused across every engine in the fallback chain (not re-extracted per engine).
2. Engine order depends on `request.isBrowseQuery()` (no `release`/`recording`, but `style` and/or `dateRange` present — e.g. "trance 1994"):
   - **BROWSE** → Discogs → MusicBrainz → Bandcamp. Discogs carries per-release label data that MusicBrainz `/release-group` doesn't (label is a `/release`-level MB field, not `/release-group`).
   - **LOOKUP** (has artist/release/recording) → MusicBrainz → Discogs → Bandcamp (unchanged default order).
3. `searchContextService.saveSearchContext(conversationId, engine, query, request, releases)`
4. Return: `"found N releases on engine"` (checked via `startsWith("found ")`, not `startsWith("no results")` — engine-not-configured and no-results messages must NOT count as success) or `"not found on any source"`.

Inside MusicBrainz itself, a BROWSE-shaped request additionally tries `/release-group` (one row per album concept, dedup'd across pressings) before falling back to `/release` — see `mainagent/search/spec.md`.

### `findSimilar(seedQuery, conversationId)`
"Find something like X" — real similarity, never invented from the LLM's own knowledge:
1. Seed resolution: if `seedQuery` blank → use the release currently in view (same `currentPage` lookup as `getTrackList`); else `seedQuery` is treated as an artist name directly (no re-parsing).
2. `MusicBrainzClient.findArtistMbid(seedArtist)` → MBID. Empty → ask for a clearer artist name.
3. `ListenBrainzClient.findSimilarArtists(mbid)` (labs.api.listenbrainz.org, free, no key, CC0) → top artists by co-listen score, capped at `MAX_SIMILAR_ARTISTS_TRIED` (6).
4. For each similar artist (score-sorted), `MusicBrainzClient.searchReleases()` by artist name only — the **highest-scoring** release per artist (not the first), capped at `MAX_SIMILAR_RELEASES` (12). An unconstrained artist-name-only query can return same-name collisions from unrelated artists; picking by MusicBrainz's own relevance score instead of list order avoids surfacing one of those collisions as the pick.
5. `searchContextService.saveSearchContext(conversationId, MUSICBRAINZ, "схоже на <seed>", null, combined)` — same context slot `search()` uses, so card-building/pagination/DL work identically on the result.
Tригер: "хочу схоже", "порадь щось подібне", "similar to X", "recommend something like this". Distinct from `manageLibrary`'s library-scoped `findSimilarInLibrary` (audio-feature similarity over the user's own analyzed tracks) — this tool finds NEW music via ListenBrainz, not what the user already owns.

### Web search — no longer a local tool
Research questions ("розкажи про X", "хто такий X", "що за лейбл Y", "дискографія X") are handled by
Anthropic's server-side `web_search` tool attached to `discoveryChatModel` (see above), not a
`DiscoveryAgentTools` method. `DiscoveryAgentPrompts.SYSTEM` tells the model it has this capability
and to use it for any factual/research question instead of answering from memory. There is no
`ChatResponseAccumulator` push for this anymore (was `"🌐 виходимо у світ божий…"`) — the search runs
inside the same API call, so there's no client-side moment to hook a progress message into;
`MainAgentTools.discoverMusic()`'s generic `"🔍 шукаю..."` progress ping still covers this case.

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

Картка будується через `ReleaseSearchFlowService.buildPageResponse(ctx, 0)` — **одна** картка з пагінацією (⬅️/➡️ гортають повний список результатів), не список окремих повідомлень. Показ кількох карток одночасно навмисно НЕ робиться для одного пошукового запиту — тільки якщо колись з'явиться явна multi-item фіча (напр. юзер питає про кілька різних треків/релізів в одному повідомленні), і то буде окремий design, не автоматичний "топ-N" з одного пошуку.

**`DiscoveryAgentPrompts.SYSTEM` ключові правила:**
- Для SEARCH-запитів: передати query прямо в `search` tool.
- Для TRACKLIST-запитів: **завжди** викликати `getTrackList` — ніколи не відповідати з пам'яті.
- Для "ще копай"/"dig deeper": викликати `digDeeper`, не `search`.
- Для "хочу схоже"/similarity-запитів: **завжди** викликати `findSimilar`, ніколи не вигадувати артистів самому.
- Для дослідницьких питань (bio, discography, label info): використати вбудований web search, не відповідати з пам'яті.
- Якщо `getTrackList` повернув треки — вивести **повний** пронумерований список дослівно.

---

## Hard rules
1. DiscoveryAgent сам вирішує стратегію — MainAgent не передає `preferredEngine` (той параметр залишився в `DiscoverRequest` для можливого майбутнього direct path).
2. `search()` пробує всі движки по порядку — не зупиняється на порожньому результаті, поки є що пробувати.
3. `digDeeper()` читає `:d` контекст — не очищати пам'ять між викликами.
4. Відповідь DiscoveryAgent — рядок для MainAgent (не для юзера напряму).
5. Якщо нічого не знайшов → попросити уточнення (рік, лейбл, жанр, країна).
6. `findSimilar()` ніколи не вигадує схожих артистів з власного знання LLM — тільки ListenBrainz co-listen дані.

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
