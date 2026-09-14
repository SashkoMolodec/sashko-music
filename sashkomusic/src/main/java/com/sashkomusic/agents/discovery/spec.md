# DiscoveryAgent — Spec

> Feature flow: [/.specs/search.md](../../../../../../.specs/search.md)

## Purpose
Пошук і дослідження музики по 3 джерелах. Отримує вільний запит, сам вирішує стратегію (який тул, скільки
пошуків, чи послабити фільтри). Повертає відформатований summary для MainAgent і будує release картки через
accumulator.

**Точковість замість розмитості.** Кожен пошук іде в MusicBrainz + Discogs + Bandcamp **одночасно**, результати
валідуються проти запитаного артиста/назви/треку, і невалідні відкидаються (`AggregatedSearchService` +
`ReleaseMatchValidator`, див. [mainagent/search/spec.md](../../mainagent/search/spec.md)). Один пошук = одна
конкретна річ = один стос карток. Гортати в Telegram десятки випадкових релізів — це не результат.

Викликається двома шляхами (обидва — LLM path):
1. **Free-text**: `MainAgentTools.discoverMusic()` → `DiscoveryAgentService.handle()`
2. **Slash**: `UserInteractionOrchestrator` → `/discovery <query>` → `DiscoveryAgentService.handle()`

Direct (no-LLM) path з `preferredEngine` **видалено**: після агрегації вибирати движок нема сенсу — опитуються
всі три. Поле прибране з `DiscoverRequest`, щоб не лишати параметр, який мовчки ігнорується.

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
| ChatModel bean| `discoveryChatModel` (`AgentModelsConfig`) — **окремий** від спільного `haikuChatModel` (LibraryAgent + екстрактори), бо тільки цей несе Anthropic server-side `web_search` tool. Інші споживачі того ж біна: `ListenLinkWebSearch` (`AiExtractorsConfig`, резолвер лінку "де послухати", див. [.specs/streaming.md](../../../../../../.specs/streaming.md)) і `ReleaseRecommender` (`DiscoveryAgentConfig`, research для `exploreAndRecommend` — без пам'яті й без тулів) |
| maxTokens     | 1024 |
| Memory window | 16 messages |
| Memory store  | `PostgresChatMemoryStore`, ключ: `conversationId + ":d"` |

Memory **не очищається** між викликами `handle()` — тому `digDeeper` може читати попередній контекст пошуку (engine + results) з `:d` history.

**Web search — server-side, не local `@Tool`:** `discoveryChatModel` будується з `.serverTools(List.of(AnthropicServerTool.builder().type("web_search_20250305").name("web_search")...build()))`. Модель викликає `web_search` напряму на інфраструктурі Anthropic — результат приходить у **тій самій** відповіді API (`web_search_tool_result` content block), без жодного client-side виконання. Замінює колишній local `@Tool webSearch()` + `WebSearchService` (jsoup-скрапінг `html.duckduckgo.com`, крихкий: капчі, відсутність URL у відповіді). Верифіковано живим викликом до `claude-haiku-4-5-20251001` перед вмиканням.

---

## Tools (`DiscoveryAgentTools`)

### `search(query, conversationId)` — точковий пошук
0. **Guard lookup vs explore.** Якщо у витягнутому запиті немає ані артиста, ані назви релізу/треку
   (`MetadataSearchRequest.hasLookupAnchor() == false`) — валідувати результат нема проти чого, тобто це
   насправді explore-запит, який приїхав не в той тул. Такий виклик **автоматично** перекидається в
   `exploreAndRecommend`. Розділення lookup/explore описане в промпті (STEP 0), але промпт — це рекомендація
   моделі, а не гарантія: "пошукай detroit techno класичне" Haiku стабільно віддає в `search`. Гарантію дає
   саме цей код.
1. `SearchRequestExtractor.extract(query)` → `MetadataSearchRequest` → **`SearchRequestNormalizer.normalize(query, request)`**.
   Екстрактор — це LLM, і два його правила не тримаються на практиці, тому їх доводить до кінця код:
   - **`(BE)` — це не країна.** "Adjust (BE)" — дизамбігуація однойменних артистів у Discogs (і саме так
     юзер копіює назву звідти). Модель стабільно читає дужку як `country=BE`, після чого всі три каталоги
     шукають *бельгійський прес* релізу, у якого його нема, і пошук гарантовано порожній. Правило: якщо
     код країни присутній у запиті саме як `(XX)` — `country` обнуляється. Справжній запит про країну
     формулюється словами ("German techno", "японські релізи"), ніколи дужкою.
   - **"Artist — Title" — це ще й трек.** Без слова album/трек у запиті назва однаково ймовірно і те, і те,
     і промпт просить заповнити обидва поля. Коли модель кладе тільки `release`, весь трековий шлях
     вимикається мовчки: `DiscogsClient.textSearchForTrack` (єдиний, що дістає до треклиста компіляції) і
     deep pass з підтвердженням треклистом обидва загейтовані на `recording`. Правило: `recording` порожній
     + `release` є + у запиті нема album/LP/EP/vinyl/CD/альбом → `recording = release`. Слово "трек"/"track"
     перебиває слово-реліз у тому ж запиті.

   Реальний кейс, на якому це впіймали: "Adjust (BE) - Fractured Elements" — трек з компіляції
   `Various — Air II` (Discogs 33491327). До фіксу пошук ішов як `artist=Adjust, release_title=Fractured
   Elements, country=BE` і давав нуль на всіх трьох каталогах.
2. `AggregatedSearchService.search(request)` — три каталоги паралельно + валідація + (за потреби) підтвердження треклистом.
3. Порожньо → `searchContextService.rememberQuery(...)` (щоб `digDeeper` мав що розширювати) і повернути **перелік застосованих фільтрів** з інструкцією повторити з меншою їх кількістю. Окремо розрізняється `rawCount > 0` ("каталоги віддали N, але жоден не той артист/назва" → швидше проблема написання) від чистого нуля.
4. Знайшло → `searchContextService.openStack(conversationId, query, request, label=null, releases)` — **власний стос** на цей виклик.
5. Return: `"found N matching releases for '<query>' on <sources> — card stack shown to the user"`.

**Викликається кілька разів за хід.** Питає юзер про три речі — три виклики, три стоси, три повідомлення з
картками. Ліміт — 3 на відповідь (більше не читається в Telegram).

### `exploreAndRecommend(topic, conversationId)` — research → N стосів
Для відкритих запитів ("порадь щось", "що послухати з детройт-техно", "хочу дарк ембієнт 90-х"):
1. `ReleaseRecommender` (окремий `@AiService` на `discoveryChatModel`, тобто **з web search**) → рівно 3 рядки `Artist — Release Title`, у написанні каталогів.
2. Парсинг рядків детермінований (split по тире), **без** повторного виклику екстрактора — 3 зайвих LLM-хопи ні до чого.
3. Для кожного кандидата — той самий точковий агрегований пошук; кандидати без підтвердження в каталогах мовчки пропускаються (рекомендація, яку не відкрити, гірша за на один стос менше).
4. Кожен підтверджений → `openStack(..., label="Artist — Title", ...)`.
5. Return: перелік стосів + звідки вони, аби MainAgent написав вступ.

Чому одним тулом, а не "research → LLM сам викликає search × 3": цикл всередині тула детермінований і гарантує
форму відповіді (N стосів), а Haiku при трьох послідовних тул-викликах легко зривається на прозу.

### `findSimilar(seedQuery, conversationId)` — схоже на X → N стосів
Real similarity, never invented from the LLM's own knowledge:
1. Seed resolution: if `seedQuery` blank → the release currently in view (same `currentPage` lookup as
   `getTrackList`); artist і title там уже розділені каталогом, тому `MetadataSearchRequest` будується
   напряму, без виклику екстрактора. Інакше — рядок, як його дав юзер (може бути "артист" або
   "артист — назва"), через `SearchRequestExtractor`.
2. `MusicBrainzClient.findArtistMbid(seedArtist)` → MBID, де `seedArtist` = поле `artist` з витягнутого запиту
   (голий seed "symphony of love — spirit of love" як ім'я артиста в MB не резолвиться взагалі).
3. `ListenBrainzClient.findSimilarArtists(mbid)` (labs.api.listenbrainz.org, free, no key, CC0) → top artists by co-listen score, capped at `MAX_SIMILAR_ARTISTS_TRIED` (6).
4. Кожен споріднений артист стає кандидатом → точковий пошук по імені артиста → **свій стос** з label = ім'я артиста. Беруться перші 3, що дали результат.

Раніше це був один стос із 12 релізів від 12 різних артистів — по картках було неможливо зрозуміти, де чия
рекомендація. Тепер один артист = один стос.

**Style fallback — коли ListenBrainz нічого не знає (крок 2 або 3 пустий).** MusicBrainz+ListenBrainz покривають
те, що люди масово слухають; обскурне вінілове 12" 1993-го (типовий вміст цієї бібліотеки) там не існує —
а в Discogs існує, разом зі стилями. Тому замість тупика:
1. Той самий точковий агрегований пошук по seed → топ-реліз.
2. З нього беруться `tags` (Discogs styles: `acid, techno, trance`) — максимум 3 — і десятиліття з `years` (`1990s`).
3. `exploreAndRecommend("<styles> <decade>")` — юзер отримує стоси, а не відмову.
4. Якщо навіть seed не знайшовся в жодному каталозі (або в нього немає стилів) — тільки тоді відмова
   з проханням уточнити назву.

Це код, а не правило промпту: «знайди схоже» не має права впертись у відсутність рядка в MusicBrainz.

Тригер: "хочу схоже", "порадь щось подібне", "similar to X". Distinct from `manageLibrary`'s library-scoped `findSimilarInLibrary` (audio-feature similarity over the user's own analyzed tracks) — this tool finds NEW music via ListenBrainz, not what the user already owns.

### Web search — no longer a local tool
Research questions ("розкажи про X", "хто такий X", "що за лейбл Y", "дискографія X") are handled by
Anthropic's server-side `web_search` tool attached to `discoveryChatModel` (see above), not a
`DiscoveryAgentTools` method. `DiscoveryAgentPrompts.SYSTEM` tells the model it has this capability
and to use it for any factual/research question instead of answering from memory. There is no
`ChatResponseAccumulator` push for this anymore (was `"🌐 виходимо у світ божий…"`) — the search runs
inside the same API call, so there's no client-side moment to hook a progress message into;
`MainAgentTools.discoverMusic()`'s generic `"🔍 шукаю..."` progress ping still covers this case.

### `digDeeper(conversationId)`
Читає попередній `rawInput` + `MetadataSearchRequest` з `:d` контексту і ганяє `AggregatedSearchService.searchLoose()` —
той самий запит **без валідації**, стос із label "усе підряд". "Наступний движок по колу" більше не існує:
всі три опитуються завжди, тому єдина вісь, по якій ще можна копнути — суворість.
Тригер: "копай", "ще копай", "покажи все", "dig deeper".

### `getTrackList(conversationId)`
Читає `searchResults` з основного (не `:d`) conversationId — бере реліз по `currentPage` (яка зберігається у `SearchContext.currentPage` в ChatStateStore). Lazy-fetches повний треклист через `getMetadataWithTracks()`. Повертає пронумерований список треків.
Тригер: "які треки", "tracklist", "що на альбомі".

**Важливо:** `conversationId` що передається в tool — це `:d` ID. Метод сам стрипає суфікс: `mainId = conversationId.endsWith(":d") ? conversationId.substring(0, len-2) : conversationId`, після чого читає `SearchContext` під основним ID.

---

## DiscoveryAgentService — відповідальність

`handle(DiscoverRequest)`:
1. `searchContextService.beginStacks(conversationId + ":d")` — **межа ходу**. Стоси попередньої відповіді
   перестають рахуватись як "щойно знайдене"; активний стос лишається, тому ⬇️/🎧 на старих картках працюють.
2. `discoveryAgent.chat()` — агент робить скільки завгодно тул-викликів, кожен пошуковий тул додає свій стос.
3. `buildResult()`: стоси = все, що цей хід наробив.
   - Стосів нема (тільки `getTrackList`, тільки research-відповідь, або промах) → `DiscoverResult.empty(summary)`,
     картки на екрані не чіпаються.
   - Є → `copySearchContext(:d → основний)` + **по одному повідомленню на стос** у accumulator
     (`buildStackResponse(ctx, stackId, 0)`) + `DiscoverResult.found(formatForMainAgent(stacks), ...)`.

Визначення "чи був пошук у цьому ході" — це тепер просто "чи є стоси". Попередня евристика (порівняння
`rawInput`/`engine` до і після виклику) не вміла відрізнити два різні пошуки в одному ході від жодного.

`formatForMainAgent(stacks)`:
- один стос → агрегат як раніше: кількість, джерела, діапазон років, типи, топ-3 лейбли, топ-5 тегів;
- кілька стосів → по рядку на стос (`• <label> — <агрегат>`) + інструкція представити всі.
**Не перелічує кожен реліз** — MainAgent читає тільки `.summary()`.

**`DiscoveryAgentPrompts.SYSTEM` ключові правила:**
- **Один пошук — одна конкретна річ.** Питають про кілька — кілька викликів `search` (макс 3), не один злитий запит.
- **lookup vs explore:** названий реліз/трек/артист → `search`; сцена/жанр/епоха без назви → `exploreAndRecommend`.
  Помилка роутингу не фатальна — `search` сам перекине безанкорний запит в explore (див. Tools → `search`, крок 0).
- **Запит будується з контексту розмови:** "того самого артиста, але 90-ті" → `search("<артист> 1990-1999")`,
  ніколи займенник чи голе уточнення. Це і є "контекст як фільтри".
- **Порожній результат → повтор з меншою кількістю фільтрів** (тул сам каже, які застосував; слабкі —
  рік, лейбл, стиль, формат, країна — знімаються першими; артист+назва лишаються). Максимум два повтори,
  далі — одне уточнююче питання юзеру.
- **STEP 0 (перевіряється першим, перекриває всі інші правила):** якщо повідомлення містить дієслово
  пошуку/показу ("пошукай", "знайди", "покажи", "find", "search") стосовно музики/жанру/артиста — **обов'язково**
  викликати пошуковий тул як першу дію. Названий реліз/трек/артист → `search`; сцена/жанр/епоха без конкретної
  назви → `exploreAndRecommend`. Слова типу "в інтернеті", "в мережі", "online", "класичне", "витоки", "топ" —
  це описові уточнення query, а НЕ сигнал перейти в research/web-search режим (навіть слово "інтернет" саме по
  собі нічого не означає — `search` і так шукає по онлайн-каталогах). Це найчастіша точка провалу: Haiku
  схильний трактувати "пошукай ... класичне, витоки, в інтернеті" як дослідницьке питання і відповідати
  прозою без жодного виклику tool — STEP 0 явно забороняє цю поведінку і дає приклад правильної дії
  (`search("detroit techno класичне витоки")`, не есе про історію жанру).
- Для SEARCH-запитів: передати повний, розрезолвлений query в `search`.
- Для рекомендацій/експлору: `exploreAndRecommend`, ніколи не радити релізи з власної пам'яті.
- Для TRACKLIST-запитів: **завжди** викликати `getTrackList` — ніколи не відповідати з пам'яті.
- Для "ще копай"/"dig deeper": викликати `digDeeper`, не `search`.
- Для "хочу схоже"/similarity-запитів: **завжди** викликати `findSimilar`, ніколи не вигадувати артистів самому.
- Для чисто дослідницьких питань без прохання знайти/показати релізи (bio, discography, label info): використати
  вбудований web search, не відповідати з пам'яті. Ніколи не писати "дивись картки нижче", якщо в цьому виклику
  не викликався `search`/`findSimilar`.
- Якщо `getTrackList` повернув треки — вивести **повний** пронумерований список дослівно.

---

## Hard rules
1. DiscoveryAgent сам вирішує стратегію — який тул, скільки пошуків, коли послабити фільтри. MainAgent передає тільки query.
2. `search()` опитує всі три каталоги одночасно і показує **тільки** підтверджені збіги. Розмитий результат — не результат.
3. `digDeeper()` читає `:d` контекст — не очищати пам'ять між викликами.
4. Відповідь DiscoveryAgent — рядок для MainAgent (не для юзера напряму).
5. Якщо нічого не знайшов → попросити уточнення (рік, лейбл, жанр, країна).
6. `findSimilar()` ніколи не вигадує схожих артистів з власного знання LLM — тільки ListenBrainz co-listen дані
   або (fallback) стилі, які реальний каталог написав на самому seed-релізі.
7. `exploreAndRecommend()` показує тільки те, що підтвердилось у каталогах — рекомендація без картки не показується.
8. Жоден запит без анкера (артист/назва/трек) не доходить до показу як "пошук" — або explore, або нічого.
9. `beginStacks()` викликає `DiscoveryAgentService` раз на хід; тули стоси не чистять.

---

## Out of scope
- Говорити до користувача напряму — тільки через summary → MainAgent
- Вибір джерела завантаження — DownloadAgent / кнопка DL
- Бібліотечні операції → LibraryAgent

---

## SDD checkpoints
- Новий пошуковий движок → `SearchEngine` enum value + `SearchEngineService` impl. Tool-код не змінюється — `AggregatedSearchService` ітерує весь map.
- Змінити суворість збігу → `ReleaseMatchValidator`, не тули.
- Екстрактор систематично плутає поле → правило в `SearchRequestNormalizer` (детерміновано), а не тільки
  абзац у промпті `SearchRequestExtractor`. Промпт — рекомендація, нормалізатор — гарантія.
- Змінити кількість стосів у рекомендації → `MAX_RECOMMENDATIONS`.
- Новий тип запиту (напр., RAG) → новий `@Tool` тут + рядок у таблиці Tools.
