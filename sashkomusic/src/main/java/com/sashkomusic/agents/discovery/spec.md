# DiscoveryAgent — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)

## Purpose
Пошук музики по 3 джерелах. Отримує вільний запит або явний движок, повертає structured summary. Результати зберігає в `SearchContextService` — по них будуються release картки.

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
| `userMessage`   | Query вербатимно (тільки LLM path) |
| Return          | Ukrainian summary ≤120 символів |

**LLM path** (`preferredEngine == null`): `discoveryAgent.chat()` → LLM вирішує стратегію.
**Direct path** (`preferredEngine != null`): `DiscoveryAgentTools.runSearch(engine)` напряму, LLM не викликається.

Чому окремий `:d` memory ID: якби Main і Discovery ділили memory, `tool_use` без `tool_result` в history викликав би помилку Claude API.

---

## Модель і пам'ять

| Параметр      | Значення |
|---------------|----------|
| Модель        | `claude-haiku-4-5-20251001` (override: `agents.discovery.model-name`) |
| maxTokens     | 1024 |
| Memory window | 16 messages |
| Memory store  | `PostgresChatMemoryStore`, ключ: `conversationId + ":d"` |

---

## Tools (`DiscoveryAgentTools`)

### `search(query, conversationId)`
1. `SearchRequestExtractor.extract(query)` → `MetadataSearchRequest`
2. Перебирає `SearchEngine.values()` (MusicBrainz → Discogs → Bandcamp)
3. Перший hit → `searchContextService.saveSearchContext(conversationId, engine, ...)`
4. Return: `"found N releases on engine"` або `"not found on any source"`

### `getPreviousSearches(conversationId)`
Читає `SearchContextService` — що шукали раніше в цій розмові.

---

## Hard rules
1. LLM path: порядок — `SearchEngine.values()` (MusicBrainz першим).
2. Direct path: `preferredEngine != null` → LLM не викликається.
3. Не переформатовувати запит — `SearchRequestExtractor` сам парсить artist/album/year.
4. Відповідь — один рядок, lowercase, без markdown.
5. Якщо нічого не знайшов → `"not found"`, попросити уточнення.
6. Не описувати кожен реліз в тексті — картки будує `ReleaseSearchFlowService`.

---

## Out of scope
- Говорити до користувача — тільки MainAgent
- Вибір джерела завантаження — DownloadAgent
- Завантаження треклистів / обкладинок — SearchEngineService

---

## SDD checkpoints
- Новий пошуковий движок → `SearchEngine` enum value + `SearchEngineService` impl. Tool-код не змінюється — перебирає `values()`.
- Змінити порядок → змінити порядок у `SearchEngine` enum.
- RAG / vector search → новий tool тут.
