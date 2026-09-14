# mainagent/search — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)
> State persistence: [/.specs/conversation-state.md](../../../../../../.specs/conversation-state.md)

## Purpose
Пошук релізів і зберігання результатів між запитами. Обслуговує discovery flow і download flow. Не LLM — тільки оркестрація HTTP-клієнтів і персистентний стан сесії.

---

## `SearchContextService` — contract

Persists to `ChatStateStore` (flow_key: `"search"`):
```java
record SearchState(SearchContext context, List<ReleaseMetadata> releases, List<SearchStack> stacks)
record SearchContext(SearchEngine source, MetadataSearchRequest request, String rawInput, List<String> releaseIds, int currentPage)
record SearchStack(String id, String label, List<ReleaseMetadata> releases, int currentPage)
```

**Стос (`SearchStack`) — одиниця показу.** Один хід може створити кілька: "порадь щось схоже" дає окремий
стос на кожен рекомендований реліз, кожен гортається своїми ⬅️/➡️ у своєму повідомленні. `id` (`s1`, `s2`…)
їде в `CARD:`-колбеку, `label` — запит, що побудував стос (друкується на картці, щоб три стоси підряд не
злились). `context`/`releases` дзеркалять **активний** (останній) стос — на них тримається все, що читає
"поточний реліз".

`stacks` відсутній у payload'ах, записаних до появи мульти-стосів → нормалізується в порожній список
(compact constructor), тому старі сесії десеріалізуються без помилки.

`currentPage` — індекс поточної відкритої release картки (0-based). Оновлюється при кожному `CARD:`
переключенні. Читається `DiscoveryAgentTools.getTrackList()` аби знати для якого релізу показувати треклист.

| Метод | Що робить |
|-------|-----------|
| `saveSearchContext(conversationId, source, rawInput, request, results)` | Один результат без стосів (URL-резолв, photo search) |
| `beginStacks(conversationId)` | Межа ходу: чистить список стосів, лишає активний стос живим (кнопки на старих картках далі працюють) |
| `openStack(conversationId, rawInput, request, label, results)` | Додає стос, робить його активним, повертає `stackId` |
| `getStacks(conversationId)` / `findStack(conversationId, id)` | Стоси поточного ходу |
| `updateStackPage(conversationId, stackId, page)` | Сторінка конкретного стосу |
| `rememberQuery(conversationId, rawInput, request)` | Запам'ятати запит **без** зміни результатів — щоб ⛏️ мав що розширювати після пустого точкового пошуку |
| `getReleaseMetadata(releaseId, conversationId)` | Шукає по активному стосу **і по всіх стосах** — юзер може тиснути ⬇️/🎧 на картці будь-якого стосу |
| `getSearchResults(conversationId)` | Релізи активного стосу |
| `getMetadataWithTracks(releaseId, conversationId)` | Lazy-load треків; оновлений реліз пишеться і в активний стос, і в усі стоси, де він лежить |
| `updateCurrentPage` / `getCurrentPage` | Сторінка активного стосу |
| `copySearchContext(fromId, toId)` | Копіює **весь** `SearchState` (`:d` → основний), інакше мульти-стосовий хід приїхав би як один |
| `clearSearch` / `clearAllCaches` | Видаляє зі store |

---

## `SearchEngineService` — interface

```java
SearchEngine getSource();
List<ReleaseMetadata> searchReleases(MetadataSearchRequest request);
List<TrackMetadata> getTracks(String releaseId);
```

Implementations: `MusicBrainzClient`, `DiscogsClient`, `BandcampClient`.

**Порядку движків більше немає.** Раніше движки пробувались по черзі до першого непорожнього результату —
тобто відповідь залежала від того, який каталог першим щось повернув, а не від того, в якому реально є реліз.
Тепер усі три опитуються паралельно (`AggregatedSearchService`), результати валідуються і зливаються в один стос.

### `MusicBrainzClient` — BROWSE vs LOOKUP
- **LOOKUP** (has `release` or `recording`): `/release` endpoint, as before (limit **100** — MusicBrainz hard-caps at 100; 150 silently errors).
- **BROWSE** (`isBrowseQuery()`): tries `/release-group` FIRST (one row per album concept, dedup'd across pressings — far less noisy than `/release` for a bare style+year query), falls back to `/release` if empty. Lucene field for date differs: `firstreleasedate:` on `/release-group` vs `date:` on `/release`; `/release-group` has no `country`/`label`/`catno` fields.
- `findArtistMbid(artistName)` — new lookup (`/artist?query=`) used by `findSimilar`.
- **Group sort order is SCORE first, year only a tiebreaker** (`mapToGroupedDomain`). Was year-ascending first — same-name-collision releases (a different artist who happens to share/partially-match the searched name) would outrank the actual best match purely for being older, surfacing a totally unrelated result as the top card. Score (MusicBrainz's own Lucene relevance) must dominate; year only orders among equally-relevant matches.

### `AggregatedSearchService` — один пошук, три каталоги

`search(request)` → `Aggregated(releases, rawCount, sources, tracklistsChecked)`:
1. MusicBrainz + Discogs + Bandcamp паралельно (`asyncExecutor`, таймаут 25s на движок, падіння движка = порожній список, не помилка всього пошуку).
2. Кожен результат через `ReleaseMatchValidator` — не збігся, викидається. Cap `PER_ENGINE_CAP=12` на движок, щоб балакучий каталог не витіснив інші.
3. **Deep pass:** якщо підтверджених < 5 і в запиті є `recording`, то для кандидатів, яких можна підтвердити тільки треклистом (компіляції), довантажуються треки — бюджет `TRACKLIST_BUDGET=8` викликів, паралельно.
4. Сортування: `EXACT` → `TRACK` → `PARTIAL`, всередині рівня — по пріоритету джерела.
5. Дедуп по нормалізованому `artist::title`; слот лишає джерело з вищим пріоритетом: **Discogs → Bandcamp → MusicBrainz** (у Discogs найбагатша картка + відео для 🎧; Bandcamp — прямa сторінка прослуховування; MB виграє, коли він єдиний). Cap `TOTAL_CAP=20`.

`searchLoose(request)` — те саме без валідації (кнопка/тул "копай глибше").

`rawCount` > 0 при порожньому `releases` означає "запит надто розмитий", а не "такого не існує" — і повідомлення юзеру відрізняється.

### `ReleaseMatchValidator` — точковість

Чистий, без I/O. `evaluate(request, release)` → `EXACT | TRACK | PARTIAL | NONE`:
- `NONE`, якщо просили артиста, а в релізу інший артист (виняток — `Various`: на компіляції альбомний артист нічого не каже про конкретний трек).
- `EXACT` — назва релізу дорівнює запитаній (release або recording).
- `TRACK` — запитаний трек є в треклисті, і його **потрековий** артист збігається. Так у відповідь потрапляє компіляція.
- `PARTIAL` — назва містить запитану цілим словом (перевидання/розширені назви).
- Browse-запит (тільки style/year) — валідувати нема проти чого, результати движка лишаються як є.

`normalize()` зрізає дужкові уточнення, тому `Adjust (BE)` (як пише юзер) і `Adjust (2)` (як дизамбігує Discogs) — це один артист. Порівняння підрядків — по межах слів: "mist" не матчить "mistake".

`needsTracklistCheck()` каже, чи вердикт впирається саме в треклист — щоб не витрачати API-виклики на вже вирішені кандидати.

### `DiscogsClient` — structured params
`addDiscogsParameters` maps `MetadataSearchRequest` fields onto Discogs' own structured filters (`artist`, `release_title`, `track`, `year`, `format`, `catno`, `label`, `style`, `country`) instead of concatenating everything into the free-text `q` param — `q` is relevance-ranked full-text search, so "trance 1994" in `q` matched titles containing "trance" literally, not releases tagged trance from 1994. `type=release` only (was `master,release` — `mapToDomain` already filtered to `"release"` type, so `master` was pure noise). `mapToDomain` calls `filterByRequestedArtist` right after the type filter — Discogs' `artist=` param is a relevance hint, not an exact filter, so a query like "Alpi - Discontinuity" returns releases from every unrelated Discogs artist entity disambiguated as "Alpi", "Alpi (2)", "Alpi (3)", etc; the filter keeps only results whose extracted artist string case-insensitively equals the requested artist, falling back to the unfiltered list if that empties it (loose-but-present beats exact-but-empty). Grouping in `mapToDomain` keys on **artist + title**, not title alone — a title-only key merged unrelated releases that happen to share a generic title (e.g. "Imaginary Landscapes" is both a well-known John Cage piece with a dozen reissues AND an unrelated electronic release; grouping by title alone mashed both into one release with garbage combined years/tags/label). Uses `LinkedHashMap` — a plain `groupingBy` uses a `HashMap` and silently destroys Discogs' relevance ordering.
**Free-text `q` для трекових запитів (`textSearchForTrack`):** те, що юзер вбиває в пошук discogs.com, потрапляє в повнотекстовий індекс `q`, який покриває **треклист** — артиста й назву кожного треку. Структурні параметри цього не вміють: `artist=` бачить лише альбомного артиста, тому "Adjust (BE) - Mist" там дає нуль і деградує до голого `track=mist` по всіх артистах світу (реальний баг: 49 чужих релізів з треком "Mist"). Тому при `artist` + `recording` додатково виконується `q="<artist> <recording>"`, а результат мапиться з `request.withoutArtist()` — фільтр по альбомному артисту тут навмисно вимкнений, бо ціль саме компіляції з `Various`. Результати обох пошуків зливаються по `id`.
`performSearch`/`retryWithoutArtist`/`performTextSearch` route through a `@Lazy self` proxy reference so `@CircuitBreaker`/`@Retry` actually apply — calling a `@CircuitBreaker`-annotated method directly (`this.performSearch(...)`) bypasses the Spring AOP proxy entirely.
`getVideos(releaseId)` — community-curated YouTube links from the release's `videos[]`, in page order (used by `ListenLinkResolver`: перше = ведучий лінк, решта = переходи по треках).

---

## `ReleaseSearchFlowService` — ключові методи

| Метод | Призначення |
|-------|-------------|
| `searchDefault(ctx, rawInput)` | Точковий агрегований пошук → один стос. Порожньо → `rememberQuery` + повідомлення з кнопками (💿, ⛏️) |
| `searchWithFallback(query, engines...)` | Послідовний fallback по движках — лишився для download flow (`MusicDownloadFlowService`) |
| `switchStrategyAndSearch(ctx)` | DIG_DEEPER: той самий запит з вимкненою валідацією, стос з label "усе підряд" |
| `buildStackResponse(ctx, stackId, page)` | Картка конкретного стосу; `stackId == null` → активний стос |
| `buildPageResponse(ctx, page)` | Синонім `buildStackResponse(ctx, null, page)` |
| `handleCardCallback(ctx, data, msgId)` | `CARD:<idx>` — активний стос; `CARD:<stackId>:<idx>` — конкретний. Коротка форма приймається назавжди: її несуть картки, відправлені до появи стосів |
| `buildReleaseDownloadCard(release, engine)` | Картка для download flow |

Формат заголовка картки: `📍 <n>/<total> (<джерело> 🔗)` + ` · <label>`, якщо у стосу є label.

---

## Listen links (`ListenLinkResolver`)

`StreamingFlowService` віддає одне текстове повідомлення з готовим лінком (без кнопок) — деталі в [/.specs/streaming.md](../../../../../../.specs/streaming.md). Порядок джерел:
- `BANDCAMP` → `release.masterId()` (the release page itself IS the direct link)
- `DISCOGS` → `DiscogsClient.getVideos()` (дедуп по URL, cap 8), falls back to yt-music search if the release has no attached video
- `MUSICBRAINZ` → yt-music scraper search (`YtMusicScraperClient`, `sm-scraper` `/ytmusic/search`) by artist+title

Останній крок ланцюжка — LLM web search (`ListenLinkWebSearch`), єдине неструктуроване джерело, тому воно останнє. Apple Music шукається окремо через iTunes Search API.

---

## Similar-music lookup (`ListenBrainzClient`)

`labs.api.listenbrainz.org/similar-artists/json` — free, no API key, CC0-licensed co-listen data. Used only by `DiscoveryAgentTools.findSimilar()` (see `agents/discovery/spec.md`) via `MusicBrainzClient.findArtistMbid()` → `ListenBrainzClient.findSimilarArtists()`. Library-internal similarity (own analyzed tracks) is a separate, unrelated path — `LibrarySimilarityService` in `libraryagent`, exposed via `LibraryAgentTools.findSimilarInLibrary` — pure audio-feature cosine similarity, no external API.

---

## Hard rules
1. `SearchContextService` — єдиний writer в `ChatStateStore` для `flow_key = "search"`.
2. `getReleaseMetadata(releaseId)` без `conversationId` → тільки in-memory cache, не ходить в store.
3. Discovery flow зберігає під `conversationId + ":d"`, потім `copySearchContext` дублює під основний ID.
4. `DownloadContextHolder` — окремий holder з flow_key `"dl_ctx"`, не тут.
5. `currentPage` читається `getTrackList` (DiscoveryAgent) щоб знати який реліз показати.
6. Жоден результат не потрапляє юзеру без перевірки `ReleaseMatchValidator` — крім явного "копай глибше".
7. `beginStacks` викликає **власник ходу** (`DiscoveryAgentService`, `searchDefault`), не окремі тули — інакше другий тул у тому ж ході затер би стос першого.

---

## SDD checkpoints
- Новий `SearchEngine` → `SearchEngineService` impl + реєстрація в `SearchEngineConfig`. `AggregatedSearchService` підхопить автоматично (ітерує весь map).
- Змінити пріоритет джерела в дедупі → `AggregatedSearchService.SOURCE_PRIORITY`.
- Змінити суворість пошуку → `ReleaseMatchValidator`, не окремі клієнти.
- Нове поле в `SearchContext`/`SearchStack` → оновити `SearchState` deserialization (Jackson) і всі місця де вони будуються.
