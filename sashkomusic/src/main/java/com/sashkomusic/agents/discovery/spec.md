# DiscoveryAgent — Spec

## Purpose
Пошук музики. Отримує вільний запит, обирає пошуковий движок, викликає його через tool,
повертає короткий укр. summary. Результати зберігає в `SearchContextService` — по них
потім будуються release картки для Telegram.

---

## Місце в потоці

`DiscoveryAgentService.handle(DiscoverRequest)` має два шляхи залежно від `preferredEngine`:

```
MainAgentTools.findMusic()                              MainAgentTools.findMusicOnDiscogs()
  └─ DiscoverRequest(preferredEngine=null)                └─ DiscoverRequest(preferredEngine=DISCOGS)
       └─ DiscoveryAgentService.handle()                       └─ DiscoveryAgentService.handle()
            └─ [LLM path]                                           └─ [direct path]
                 discoveryAgent.chat(":d", query)                        discoveryAgentTools.runSearch(DISCOGS, query, ":d")
                   └─ [tool: search()]                                        └─ SearchEngineService.searchReleases()
                        └─ sequential MusicBrainz→Discogs→Bandcamp                 └─ searchContextService.saveSearchContext(":d", ...)
                             └─ saveSearchContext(":d", ...)
            ↓ (обидва шляхи зливаються тут)
            ├─ getSearchResults(discoveryMemoryId)
            ├─ copySearchContext(":d", conversationId)
            └─ accumulator.pushAll(conversationId, buildPageResponse(...))
```

`digDeeper` теж використовує **direct path** — вибирає наступний движок по колу від останнього використаного.

---

## LangChain4j interface

```java
public interface DiscoveryAgent {
    @SystemMessage(DiscoveryAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
```

| Параметр        | Значення                                                              |
|-----------------|-----------------------------------------------------------------------|
| `conversationId`| `conversationId + ":d"` — **ізольований** від MainAgent memory ID     |
| `userMessage`   | Query вербатимно (LLM path only; direct path не кличе `chat()`)       |
| Return          | Summary для LLM (наприклад `"знайшов 5 релізів на musicbrainz"`)      |

**Увага:** LangChain4j interface використовується тільки для **LLM path** (`preferredEngine == null`).
При **direct path** `DiscoveryAgentService` кличе `DiscoveryAgentTools.runSearch()` напряму,
минаючи `chat()` повністю.

---

## Модель і пам'ять

| Параметр         | Значення                                                                            |
|------------------|-------------------------------------------------------------------------------------|
| Модель           | `claude-haiku-4-5-20251001` (override: `agents.discovery.model-name`)               |
| maxTokens        | 1024 (override: `agents.discovery.max-tokens`)                                      |
| Memory window    | `MessageWindowChatMemory.maxMessages(16)` — ~8 ходів                               |
| Memory store     | `PostgresChatMemoryStore` → `conversation_messages`, ключ: `conversationId + ":d"` |
| Memory ID        | Приклад: `"-1003551198668:d"` або `"-1003551198668:42:d"`                           |

**Чому окремий memory ID:** якби Main і Discovery ділили один memory ID, то після
`tool_use` від MainAgent і подальшого виклику Discovery його history виглядало б як
`[..., AI(tool_use), SYSTEM, USER]` — Claude API відхиляє таку послідовність
(`tool_use` без `tool_result`). Суфікс `:d` ізолює histories.

---

## Tools (`DiscoveryAgentTools`)

### `search(query, conversationId)`
Єдиний основний tool. Послідовно пробує всі движки поки не знайде результат.

| Крок | Дія                                                                          |
|------|------------------------------------------------------------------------------|
| 1    | `SearchRequestExtractor.extract(query)` → структурований `MetadataSearchRequest` |
| 2    | Перебір `SearchEngine.values()` (MusicBrainz → Discogs → Bandcamp)           |
| 3    | `SearchEngineService.searchReleases(request)` для кожного движка              |
| 4    | Перший hit → `searchContextService.saveSearchContext(conversationId, engine, query, request, releases)` |
| 5    | Return: `"found N releases on engine"` або `"not found on any source"`        |

Зберігання в `SearchContextService` (in-memory `ConcurrentHashMap`):
- `userSearches[conversationId]` → `SearchContext(source, request, rawInput, releaseIds)`
- `releaseMetadata[releaseId]` → `ReleaseMetadata` (shared, keyed by release ID)

### `getPreviousSearches(conversationId)`
Читає `SearchContextService` — що шукали в цій розмові.
Повертає: `"last search on MusicBrainz for artist='X' release='Y' returned 5 releases"`.
Використовується коли юзер каже "як минулого разу" / "ще таке".

---

## SearchContext flow після discovery

```
DiscoveryAgentService після chat():
  1. getSearchResults(discoveryMemoryId)          → releases під ключем ":d"
  2. copySearchContext(discoveryMemoryId, convId) → дублює під основний ключ
  3. buildPageResponse(ConversationContext, page=0) → перша сторінка карток
  4. accumulator.pushAll(conversationId, cards)
  5. return DiscoverResult.found(summary, releases, engine)
```

`copySearchContext` потрібен тому що `ReleaseSearchFlowService.buildPageResponse()`
читає `SearchContextService` по `ctx.conversationId()` (без `:d`), а tools зберегли
під `discoveryMemoryId`.

---

## Hard rules
1. LLM path: Default → `searchMusicBrainz` першим (порядок `SearchEngine.values()`).
2. Direct path: `preferredEngine != null` → LLM не викликається, `runSearch(engine)` напряму.
3. Не переформатовувати запит — `SearchRequestExtractor` сам розбере artist/album/year.
4. Відповідь LLM path — один рядок Ukrainian summary, ≤120 символів, lowercase, без markdown.
5. Не описувати кожен реліз в тексті — картки будує `ReleaseSearchFlowService`.
6. Якщо нічого не знайшов — повернути `"not found"`, попросити уточнення.

---

## Out of scope
- Говорити до користувача (тільки MainAgent)
- Обирати джерело для завантаження (це DownloadAgent)
- Завантажувати треклисти / обкладинки (це SearchEngineService)

---

## SDD checkpoints (змінювати spec ДО коду)
- Новий пошуковий движок → додати `SearchEngine` enum value + `SearchEngineService` impl.
  Tool-код не змінюється — він перебирає `SearchEngine.values()`.
- Змінити порядок пошуку → змінити порядок у `SearchEngine.values()`.
- RAG / vector search → новий tool тут (наприклад `searchSimilarToHistory`).
- Перевірити cost → `[agent=discovery]` trace logs (`tokensIn/tokensOut`).
