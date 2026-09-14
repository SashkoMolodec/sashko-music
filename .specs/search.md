# Search Feature

## Purpose
Знайти **конкретну** музику і показати її стосом карток. Результати зберігаються в сесії і є основою для
завантаження, стрімінгу й обговорення.

**Головний принцип: точковий пошук.** Пошук іде в MusicBrainz + Discogs + Bandcamp **одночасно**, кожен
результат перевіряється проти запитаного артиста/назви/треку, невалідне не показується. Розмитий результат
(«49 релізів з назвою Mist від різних виконавців») — це не відповідь: гортати таке в Telegram неможливо.

---

## Flows

### 1. Точковий пошук — `search` tool
```
User: "трек Adjust (BE) - Mist"
  └─ MainAgent.chat() → discoverMusic(query)
       └─ DiscoveryAgentService.handle()
            ├─ beginStacks(conversationId + ":d")           ← межа ходу
            └─ DiscoveryAgent.chat() → search("Adjust (BE) Mist")
                 ├─ SearchRequestExtractor → {artist:"Adjust (BE)", release:"Mist", recording:"Mist"}
                 ├─ AggregatedSearchService.search(request)
                 │    ├─ MB ‖ Discogs ‖ Bandcamp  (паралельно)
                 │    ├─ ReleaseMatchValidator: викидає все, що не той артист/назва
                 │    ├─ deep pass: довантажує треклисти компіляцій (бюджет 8) → підтверджує по треку
                 │    └─ дедуп + ранжування + cap 20
                 └─ openStack(...) → stack "s1"
       ├─ copySearchContext(":d" → основний)
       ├─ buildStackResponse(ctx, "s1", 0) → картка в accumulator
       └─ DiscoverResult.found(summary для MainAgent)
```

### 1a. Lookup vs explore — де проходить межа
`search` обслуговує **названу** річ (є артист і/або назва релізу/треку). `exploreAndRecommend` — відкритий
запит (сцена/жанр/епоха без назви). Розділення просить промпт, але **гарантує код**: якщо у витягнутому
`MetadataSearchRequest` немає жодного анкера (`hasLookupAnchor()`), `search` перекидає виклик в
`exploreAndRecommend`. Інакше валідувати результат нема проти чого — і "точковий пошук" тихо вироджується
в ту саму розмиту купу, заради якої все й переписувалось.

```
"трек Adjust (BE) - Mist"      → анкер є (артист + трек)     → search      → 1 стос
"знайди Burial Untrue"          → анкер є (артист + реліз)    → search      → 1 стос
"є щось від Jeff Mills?"        → анкер є (артист)            → search      → 1 стос
"пошукай detroit techno, витоки"→ анкера нема (лише style)    → explore     → 3 стоси
```

### 2. Рекомендація/експлор — `exploreAndRecommend` → **кілька стосів**
```
User: "порадь щось з класичного detroit techno"
  └─ exploreAndRecommend("класичне detroit techno")
       ├─ ReleaseRecommender (web search) → 3 рядки "Artist — Title"
       ├─ для кожного: AggregatedSearchService.search(...) → openStack(label="Artist — Title")
       └─ повертає перелік стосів
  → MainAgent пише вступ (одне повідомлення)
  → 3 окремих повідомлення з картками, кожне гортається своїми ⬅️/➡️
```
`findSimilar` ("хочу схоже на X") працює так само, але кандидати беруться з ListenBrainz co-listen даних:
один споріднений артист = один стос.

Кандидат, який не підтвердився в каталогах, мовчки пропускається — рекомендація, яку не можна відкрити,
гірша за на один стос менше.

### 3. `⛏️ DIG_DEEPER` / "копай" — розширити
Той самий запит з **вимкненою валідацією** (`searchLoose`) — усе, що віддали каталоги, стос із label
"усе підряд". Це страховка на випадок, коли точний фільтр був надто суворий (інше написання артиста,
нестандартний реліз). Перебору движків по колу більше немає — вони й так опитуються всі.

### 4. `CARD:` — гортання
`CARD:<stackId>:<index>` гортає конкретний стос; `CARD:<index>` (стара форма, лишається підтримуваною) —
активний. Кожне повідомлення редагує саме себе, тому три стоси на екрані не заважають один одному.

### 5. `discussRelease` / `getTrackList`
Треклист релізу, який юзер зараз дивиться (`currentPage` активного стосу).

---

## Search engines

| Engine | Class | API | Сильна сторона |
|--------|-------|-----|---------------|
| `MUSICBRAINZ` | `MusicBrainzClient` | musicbrainz.org API v2 | Найповніше покриття, `/release-group` для browse-запитів |
| `DISCOGS` | `DiscogsClient` | discogs.com API | Вініл/колекційне, лейбли, відео на сторінці релізу |
| `BANDCAMP` | `BandcampClient` | sm-scraper `/bandcamp/search` | Незалежні артисти, пряме посилання на прослуховування |

Опитуються **всі три паралельно**, без пріоритету. Пріоритет джерела працює тільки при дедупі однакового
релізу: **Discogs → Bandcamp → MusicBrainz** (найбагатша картка / прямий лінк / найширше покриття).

**Валідація (`ReleaseMatchValidator`)** — що саме визнається збігом:

| Рівень | Умова |
|--------|-------|
| `EXACT` | Назва релізу = запитана, артист збігається |
| `TRACK` | Запитаний трек є в треклисті, і його **потрековий** артист збігається (так проходять компіляції) |
| `PARTIAL` | Назва містить запитану цілим словом (перевидання), артист збігається |
| `NONE` | Все інше — не показується |

Дужкові уточнення нормалізуються: `Adjust (BE)` (як пише юзер) = `Adjust (2)` (як дизамбігує Discogs).
Порівняння по межах слів: "mist" не матчить "mistake".

Для трекових запитів Discogs додатково опитується вільним текстом `q="<artist> <track>"` — це той самий
індекс, що й пошуковий рядок на discogs.com, і він покриває треклист. Структурний `artist=` бачить лише
альбомного артиста, тому для треку на компіляції дає нуль і деградує до голого `track=` по всіх артистах.

---

## Key classes

| Class | Responsibility |
|-------|---------------|
| `AggregatedSearchService` | Паралельний опит трьох каталогів, валідація, підтвердження треклистом, дедуп, ранжування |
| `ReleaseMatchValidator` | Чиста логіка "це те, що просили?" |
| `SearchContextService` | Стан: стоси, активний стос, сторінки, метадані по releaseId (flow_key `"search"`) |
| `ReleaseSearchFlowService` | Побудова карток, гортання стосів, DIG_DEEPER |
| `SearchRequestExtractor` | Haiku: вільний текст → `MetadataSearchRequest` |
| `ReleaseRecommender` | Web-search LLM: тема → 3 конкретні "Artist — Title" |

---

## State after search

```
flow_key = "search"
payload  = SearchState {
  context:  SearchContext { source, request, rawInput, releaseIds[], currentPage }   ← активний стос
  releases: ReleaseMetadata[]                                                        ← активний стос
  stacks:   SearchStack[] { id, label, releases[], currentPage }                      ← усі стоси ходу
}
```

`getReleaseMetadata(releaseId, conversationId)` шукає по **всіх** стосах — юзер може тиснути ⬇️/🎧 на картці
будь-якого з трьох стосів у будь-якому порядку. Стан переживає рестарт JVM (Postgres), тому кнопки на старих
картках працюють і після редеплою.

Пустий точковий пошук нічого не затирає — він тільки запам'ятовує запит (`rememberQuery`), щоб ⛏️ мав що
розширювати, а картки попереднього пошуку лишаються робочими.

---

## Release card format (Telegram)

```
📍 1/5 (discogs 🔗) · Model 500 — Classics
💿 title
👤 artist
year • type • label • N тр. • tags

[⬅️]  [🎧]  [⬇️]  [➡️]
```

` · <label>` з'являється тільки у стосів, створених рекомендацією — щоб три стоси підряд не злились.
Посилання на сторінку релізу — в тексті, вшите в 🔗, не кнопкою (див. [streaming.md](streaming.md)).
`🎧` віддає готовий лінк де послухати + треклист одним повідомленням.

---

## Out of scope
- Локальна бібліотека — `/process` в [library-processing.md](library-processing.md)
- Завантаження — [download.md](download.md)
