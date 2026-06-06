# mainagent/search — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)
> State persistence: [/.specs/conversation-state.md](../../../../../../.specs/conversation-state.md)

## Purpose
Пошук релізів і зберігання результатів між запитами. Обслуговує discovery flow і download flow. Не LLM — тільки оркестрація HTTP-клієнтів і персистентний стан сесії.

---

## `SearchContextService` — contract

Persists to `ChatStateStore` (flow_key: `"search"`):
```java
record SearchState(SearchContext context, List<ReleaseMetadata> releases)
record SearchContext(SearchEngine source, MetadataSearchRequest request, String rawInput, List<String> releaseIds)
```

| Метод | Що робить |
|-------|-----------|
| `saveSearchContext(conversationId, source, rawInput, request, results)` | Merge + persist + update cache |
| `getReleaseMetadata(releaseId)` | In-memory cache тільки |
| `getReleaseMetadata(releaseId, conversationId)` | Cache + lazy loadContext при промаху (пережива рестарт JVM) |
| `getSearchResults(conversationId)` | З store, відновлює cache як side-effect |
| `getMetadataWithTracks(releaseId, conversationId)` | Lazy-load треків через `SearchEngineService` |
| `copySearchContext(fromId, toId)` | Копіює `:d` → основний conversationId після Discovery |
| `clearSearch(conversationId)` | Видаляє з store |
| `clearAllCaches()` | Store + in-memory (по "стоп") |

**Merge strategy:** нові результати перезаписують старі по releaseId (дедупліцирує). Дозволяє `DIG_DEEPER` акумулювати результати з різних джерел.

---

## `SearchEngineService` — interface

```java
SearchEngine getSource();
List<ReleaseMetadata> searchReleases(MetadataSearchRequest request);
List<TrackMetadata> getTracks(String releaseId);
```

Implementations: `MusicBrainzClient`, `DiscogsClient`, `BandcampClient`.

---

## `ReleaseSearchFlowService` — ключові методи

| Метод | Призначення |
|-------|-------------|
| `searchWithFallback(query, engines...)` | Послідовний fallback по движках |
| `switchStrategyAndSearch(ctx)` | DIG_DEEPER: наступний engine по колу |
| `buildPageResponse(ctx, page)` | Release картки з пагінацією |
| `buildReleaseDownloadCard(release, engine)` | Картка для download flow |

---

## Hard rules
1. `SearchContextService` — єдиний writer в `ChatStateStore` для `flow_key = "search"`.
2. `getReleaseMetadata(releaseId)` без `conversationId` → тільки in-memory cache, не ходить в store.
3. Discovery flow зберігає під `conversationId + ":d"`, потім `copySearchContext` дублює під основний ID.
4. `DownloadContextHolder` — окремий holder, не тут.

---

## SDD checkpoints
- Новий `SearchEngine` → `SearchEngineService` impl + реєстрація в `SearchEngineConfig`. `searchWithFallback` підхопить автоматично.
- Змінити порядок пошуку → порядок у `SearchEngine` enum.
- `DownloadContextHolder` мігрує на `ChatStateStore` → `flow_key = "download"`, оновити `CLAUDE.md`.
