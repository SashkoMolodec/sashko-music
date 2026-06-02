# mainagent/search — Spec

## Purpose
Пошук релізів і зберігання результатів пошуку між запитами. Обслуговує discovery flow і download flow.
Не є LLM-агентом — тільки оркестрація HTTP-клієнтів і персистентний стан сесії.

---

## Ключові компоненти

### `SearchContextService`
Центральний state manager для пошукових сесій.

**Стан зберігається в `ChatStateStore`** з `flow_key = "search"`:
```java
record SearchState(SearchContext context, List<ReleaseMetadata> releases)
```

| Поле `SearchContext` | Зміст |
|---|---|
| `source` | `SearchEngine` яким знайшли |
| `request` | Структурований `MetadataSearchRequest` |
| `rawInput` | Сирий текст запиту від юзера |
| `releaseIds` | ID знайдених релізів |

`releases` — повні об'єкти `ReleaseMetadata`, зберігаються разом із `context` щоб пережити рестарт JVM.

**In-memory cache** `Map<releaseId, ReleaseMetadata>` — відновлюється lazily при першому `loadContext()` після рестарту. Потрібен для O(1) lookups по `releaseId` без знання `conversationId`.

**Ключові методи:**
- `saveSearchContext(conversationId, source, rawInput, request, results)` — зберігає стан + оновлює cache
- `getSearchResults(conversationId)` → `List<ReleaseMetadata>` — читає з store, відновлює cache
- `getReleaseMetadata(releaseId)` → з in-memory cache (не потребує conversationId)
- `getMetadataWithTracks(releaseId, conversationId)` — lazy-load треків через `SearchEngineService`
- `copySearchContext(fromId, toId)` — копіює стан discovery (`:d` суфікс) → основний conversationId
- `clearSearch(conversationId)` — видаляє з store
- `clearAllCaches()` — очищує store + cache (викликається по "стоп")

### `ReleaseSearchFlowService`
Оркеструє пошук і пагінацію карток. Делегує в `SearchContextService` для стану, в `SearchEngineService` для HTTP.

- `searchDefault(ctx, query)` → перебирає `SearchEngine.values()` поки не знайде
- `searchWithEngine(ctx, query, engine)` → конкретний движок напряму
- `switchStrategyAndSearch(ctx)` → наступний движок по колу від останнього
- `buildPageResponse(ctx, page)` → картки релізів з пагінацією (`PAGE:` callback)
- `buildReleaseDownloadCard(release, engine)` → окрема картка для download flow

### `SearchEngineService` (інтерфейс)
Кожен impl (`MusicBrainzClient`, `DiscogsClient`, `BandcampClient`) відповідає за один зовнішній API.

```java
SearchEngine getSource();
List<ReleaseMetadata> searchReleases(MetadataSearchRequest request);
List<TrackMetadata> getTracks(String releaseId);
```

---

## Стан після рестарту JVM

```
До рестарту:   saveSearchContext() → ChatStateStore (Postgres)
Після рестарту: getSearchResults() → loadContext() → store.get() → відновлює cache → повертає releases
```

Пагінація карток (`PAGE:`) і download (`DL:`) працюють після рестарту без повторного пошуку.

---

## Hard rules
1. `SearchContextService` — єдиний writer в `ChatStateStore` для `flow_key = "search"`.
2. `getReleaseMetadata(releaseId)` читає тільки з in-memory cache — не ходить в store. Якщо cache порожній після рестарту — `loadContext(conversationId)` відновить його як side-effect.
3. Discovery flow зберігає під `conversationId + ":d"`, потім `copySearchContext` дублює під основний ID.
4. Не персистувати `DownloadContextHolder` тут — він окремий (`flow_key = "download"`, поки in-memory).

---

## SDD checkpoints
- Новий `SearchEngine` → новий `SearchEngineService` impl + реєстрація в `SearchEngineConfig`.
  `searchDefault` автоматично підхопить через `SearchEngine.values()`.
- Змінити порядок пошуку → змінити порядок в `SearchEngine` enum.
- `DownloadContextHolder` мігрує на `ChatStateStore` → окремий `flow_key = "download"`, оновити `CLAUDE.md` ContextHolder map.
