# MainAgent — Spec

> Feature flows: [/.specs/search.md](../../../../../../.specs/search.md) · [/.specs/download.md](../../../../../../.specs/download.md) · [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md)
> Async events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Точка входу для будь-якого **вільного тексту** від користувача. Сам веде discovery (пошук, рекомендації,
схоже, research), делегує бібліотеку в `LibraryAgent`, запускає завантаження. Повертає коротку українську
відповідь. Тільки цей агент говорить до користувача.

Один LLM-маршрутизатор замість двох: колись discovery був окремим агентом (Haiku, пам'ять `:d`), і запит
маршрутизувався двічі — MainAgent обирав "discovery", DiscoveryAgent знову обирав тул, а його фінальний текст
викидався, коли були картки. Правила про "схоже", треклисти й вступи дублювались у двох промптах. Тепер
discovery-тули — це тули MainAgent ([discovery/spec.md](../discovery/spec.md)).

Slash-команди (`/library`, `/np`, `/newtopic`, `/clearctx`) обходять MainAgent і йдуть напряму через
`UserInteractionOrchestrator`. `/discovery <query>` — аліас вільного тексту: той самий `MainAgentService.chat`.

---

## LangChain4j interface

```java
public interface MainAgent {
    @SystemMessage(MainAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage, InvocationParameters turn);
}
```

| Параметр | Значення |
|----------|----------|
| `conversationId` | `"chatId"` для DM, `"chatId:topicId"` для групового топіку |
| `userMessage` | Вербатимний текст від користувача |
| `turn` | `AgentTurn.parameters()` — хід, якому тули віддають картки (див. "AgentTurn") |
| Return | Коротка укр. відповідь ≤600 символів, lowercase, без markdown. Результат пошукового тула може задати власні правила вступу (довжина, що називати, які факти), і вони мають пріоритет |

Викликається тільки через `MainAgentService.chat(ctx, text) → List<BotResponse>`.

---

## AgentTurn — що тул показує юзеру

`agents.turn.AgentTurn` — один хід вільного тексту. `MainAgentService` створює його на кожне повідомлення і
передає в `MainAgent.chat` як `InvocationParameters`; LangChain4j прокидає їх у кожен тул, що оголосив параметр
`InvocationParameters` (у JSON-схему тула він не потрапляє). Тул бере `AgentTurn.of(parameters)` і:

| Метод | Для чого |
|-------|----------|
| `context()` / `conversationId()` | куди йде відповідь — замість `@ToolMemoryId` і розбору суфіксів |
| `show(List<BotResponse>)` | картки, кнопки підтвердження, прев'ю — повз LLM |
| `progress(text)` | "🔍 шукаю..." напряму в Telegram; той самий текст двічі за хід не шлеться |
| `stackOpened(stack)` / `openedStacks()` | стоси карток пошуку цього ходу (див. discovery/spec.md) |

Чому не спільний буфер (`ChatResponseAccumulator`, видалений): картки жили в singleton-мапі по
`conversationId` і переживали хід, що впав посередині (`begin()` лише логував "stale"). Хід тепер — локальний
об'єкт: впав `chat()` — його картки зникають разом з ним, і нічого не треба чистити.

`MainAgentService.chat` перед ходом стискає пам'ять (`mainMemoryProvider.compact(conversationId)`, див. "Модель і
пам'ять"), а після `mainAgent.chat()` збирає відповідь у такому порядку:
1. текст MainAgent (`BotResponse.aiText`) — вступ до того, що далі;
2. `turn.shown()` — картки бібліотеки / завантаження в порядку показу;
3. по повідомленню на кожен відкритий за хід стос (`ReleaseSearchFlowService.buildStackResponse(ctx, id, 0)`).

**Межа ходу для стосів закривається після ходу, не під час нього.** Тули лише додають стоси
(`SearchContextService.openStack`); стоси попередньої відповіді лишаються, поки хід не скінчився:
- хід вдався і відкрив стоси → `searchContextService.retainStacks(conversationId, ids)` — лишаються тільки стоси
  цього ходу;
- хід упав → `searchContextService.discardStacks(conversationId, ids)` — стоси цього ходу прибираються (їхніх
  карток ніхто не побачив), а стоси й ⬅️/➡️ на вже надісланих картках працюють далі; виняток іде далі.

Помилка моделі → виняток іде в `UserInteractionOrchestrator`, який відповідає "шось не то, попробуй ще раз".

---

## Модель і пам'ять

| Параметр | Значення |
|----------|----------|
| Модель | `claude-sonnet-5-5` (override: `agents.main.model-name`) |
| maxTokens | 8192 — thinking теж рахується в `max_tokens`, 2048 на Sonnet 5.5 обрізало б відповідь |
| Effort | дефолт Sonnet 5.5 (не задається). Бажане значення `low` — MainAgent здебільшого маршрутизує й пише короткий вступ, а на `medium`+ модель думає перед кожною відповіддю, навіть привітанням. Але `output_config.effort` можна передати лише через `customParameters`, а `langchain4j` 1.10.0 серіалізує їх двічі (розгорнуто й цілим об'єктом під ключем `custom_parameters`), і Anthropic відповідає 400 на кожен запит. Повернути `low` можна після оновлення бібліотеки або власним HTTP-клієнтом, лише з перевіркою тіла запиту (`SonnetRequestBodyTest`) |
| Thinking | adaptive (дефолт Sonnet 5.5, вимкнути не можна — `disabled` дає 400). `returnThinking` + `sendThinking`: блоки зберігаються в `AiMessage` і повертаються в API без змін — так вимагає Sonnet 5.5 у циклі тулів |
| Server tools | **немає**. Web search живе в `MusicResearcher` (Haiku, без пам'яті) за тулом `researchMusic` — блоки `server_tool_use`/`web_search_tool_result` не змішуються з thinking-блоками Sonnet у пам'яті й циклі тулів |
| Refusal fallback | вимкнено з тієї ж причини, що й effort: `fallbacks` теж ішов би через `customParameters`. Відмова класифікатора повертається як звичайна помилка моделі |
| Memory window | 32 messages |
| Memory provider | `MainChatMemoryProvider` (backed by `PostgresChatMemoryStore` → `conversation_messages` table) |
| Prompt caching | `cacheSystemMessages=true`, `cacheTools=true` |

`MainChatMemoryProvider.get()` віддає `MessageWindowChatMemory` без власного стану — кожне читання й запис іде в
store, тому кешувати її нема чого. Cross-agent context: `UserInteractionOrchestrator` викликає
`mainMemoryProvider.appendUserAndAi(...)` після `/library`, дописуючи чистий `UserMessage` + `AiMessage(summary)`
в основну MainAgent memory. `/discovery` цього не потребує — він і є хід MainAgent.

**Auto-summarization — `compact(conversationId)`, явний виклик, не побічний ефект `get()`.** LangChain4j
(`ChatMemoryService`) кешує `ChatMemory` кожної розмови й кличе `ChatMemoryProvider.get()` для неї один раз за
життя JVM, тож сумаризація всередині `get()` спрацьовувала лише після рестарту або на `/np`/`/library`, а в
розмові з самого вільного тексту вікно мовчки витискало старе. Тепер `compact` викликають:
`MainAgentService.chat` перед кожним ходом і `appendUserAndAi` / `appendUserAndAiIfNew` після дописування. Обидва —
з MESSAGES lane, тому з ходом тієї ж розмови не перетинаються. Коли memory має ≥ 26 повідомлень, старіші
стискаються:
1. Всі повідомлення крім останніх 10 подаються в Haiku з проханням стиснути до одного абзацу (≤150 слів, **англійською** — менше токенів, більше контексту вміщується).
2. Memory замінюється на: `UserMessage("Підсумок попередньої розмови:")` + `AiMessage(summaryText)` + останні 10 повідомлень (~12 повідомлень разом).
3. Межа розрізання вирівнюється по найближчому `UserMessage` щоб не розривати `tool_use`/`tool_result` пари.
4. У разі помилки моделі — fallback до сирого тексту першого 800 символів.

Провайдер обслуговує тільки MainAgent. `LibraryAgent` має власний `libraryMemoryProvider` під ключем
`conversationId + ":lib"` (`LibraryAgentService.memoryId`) — так `tool_use`/`tool_result` ланцюжки двох агентів не
перемішуються (Claude API вимагає цілісних пар). `/clearctx` і `стоп` чистять обидва ключі.
Ключі `:d` колишнього DiscoveryAgent прибрані міграцією `V21__drop_discovery_agent_state.sql`.

---

## Tools

Два tool-об'єкти: `MainAgentTools` (бібліотека, завантаження) і `DiscoveryTools` (пошук, рекомендації, research).

| Tool | Тригер | Що робить |
|------|--------|-----------|
| `search(query)` | названий реліз / трек / артист; лейбл як `"лейбл <назва>"` | `DiscoveryTools` — точковий пошук, свій стос карток |
| `exploreAndRecommend(topic)` | "порадь щось", сцена / жанр / епоха без назви | `DiscoveryTools` — research + до 3 стосів |
| `findSimilar(seedQuery)` | "хочу схоже на X" (НОВА музика) | `DiscoveryTools` — MusicBrainz-зв'язки + ListenBrainz, до 10 стосів |
| `digDeeper()` | "копай", "ще копай", "копай ще/далі/глибше", "покажи все" | `DiscoveryTools` — останній запит без валідації. "копай <запит>" з будь-яким іншим словом — пряма команда пошуку в Soulseek, до MainAgent не доходить (`UserInteractionOrchestrator`) |
| `getTrackList()` | треклист релізу на екрані, якого **нема** в бібліотеці | `DiscoveryTools` — треки з каталогу |
| `researchMusic(question)` | фактичне питання без прохання показати релізи: "розкажи про X", "хто такий X", "що за лейбл Y" | `DiscoveryTools` → `MusicResearcher` (Haiku + web_search) |
| `manageLibrary(command)` | будь-яка операція з власною бібліотекою: пошук, переміщення, process / reprocess, смартлисти; **треклист релізу що є в бібліотеці**; "маю щось схоже на X?"; **інфо про власний реліз**; **"що зараз грає"** | `LibraryAgentService` → `LibraryAgent` (Haiku) |
| `downloadMusic(artist, album)` | "скачай ...", "download X" (text-based, не кнопка `DL:`) | `MusicDownloadFlowService.getDownloadOptions` (без LLM) |

Деталі discovery-тулів — у [discovery/spec.md](../discovery/spec.md).

`manageLibrary` повертає `summary` LibraryAgent-а як результат тула; картки, які показали тули LibraryAgent-а,
йдуть у той самий `AgentTurn` (LibraryAgent отримує ті самі `InvocationParameters`).

`downloadMusic`: порожні artist і album → `"нема що качати — порожній запит"`. Інакше
`getDownloadOptions(ctx, "<artist> <album>")`, усі `BotResponse` → `turn.show(...)`, результат для LLM — їхні тексти
через `" | "` (або `"почав качати"`). Виняток FlowService-у → `"не вдалось почати: <message>"`. Тул повертається
швидко: результат пошуку файлів приходить через `FileSearchResultEvent`. Окремого `DownloadAgentService` більше
нема — він був однометодним делегатором.

Progress-пінги (`turn.progress`): пошукові тули — `"🔍 шукаю..."` (один на хід, скільки б пошуків не було),
`researchMusic` — `"🌐 шукаю в мережі..."`, `manageLibrary` — `"📚 бібліотека..."`, `downloadMusic` — `"⬇️ качаю..."`.

---

## Поточний трек як контекст

Плеєр — єдине джерело стану, якого немає в memory window. Тому «те, що зараз грає» приходить до MainAgent
двома шляхами:

1. **Пасивно.** `/np` і `/npalbum` (і кнопка `шо грає 🎵`) після показу картки дописують рядок
   `зараз грає: <артист> — <назва> (<реліз>, <рік>) …` у MainAgent memory через
   `mainMemoryProvider.appendUserAndAiIfNew`.
   `…IfNew` (а не `appendUserAndAi`) — бо кнопку `шо грає 🎵` тиснуть пачками: п'ять натискань на
   одному треку не мають прогнати п'ять однакових пар через 32-повідомлювальне вікно і збити
   авто-сумаризацію. Ідентичний рядок підряд ігнорується.
2. **Активно.** `manageLibrary("що зараз грає")` → `LibraryAgentTools.nowPlayingTrack()` — живий стан плеєра.

Якщо юзер посилається на те, що грає ("схоже до того що зараз грає?", "скачай ще цього артиста"), а в контексті
треку немає — спочатку `manageLibrary("що зараз грає")`, потім, у тому ж ході, цільовий тул із **розрезолвленою**
назвою (`findSimilar("symphony of love — spirit of love")`). Ніколи не питати юзера, що в нього грає.
Виняток: "маю щось схоже на те що грає?" — це один виклик `manageLibrary`, бо LibraryAgent резолвить
трек і робить audio-feature similarity сам.

---

## Hard rules
1. Тул обирається за формою запиту (таблиця вище). Small talk / питання за щойно знайдений реліз → відповідь без
   тула (контекст є в memory window). Кілька тулів за хід — коли це послідовність "розрезолвити, потім діяти"
   (поточний трек; "хочу щось таке як мій альбом X": `manageLibrary("розкажи про X")` → `findSimilar` або
   `exploreAndRecommend` з артистом/жанрами/лейблом з відповіді) або кілька названих речей (до 3 `search`).
2. Не вигадувати параметри — тільки те, що сказав юзер або що є в розмові. Запит у тул — розрезолвлений:
   "того самого артиста, але 90-ті" → `search("<артист> 1990-1999")`, ніколи займенник.
3. Не описувати release картки в тексті — вони показуються окремо.
4. Тільки MainAgent говорить до юзера. LibraryAgent повертає рядок; картки — через `AgentTurn.show`.
5. Callback-кнопки → `CallbackDispatcher`. Slash-команди → `UserInteractionOrchestrator`.
6. Рекомендації та "схоже" — тільки через тули, ніколи з власної пам'яті моделі. Research — тільки через
   `researchMusic`, а не з пам'яті.
7. Текстове "скачай X" → `downloadMusic` з артистом/альбомом, розписаними з контексту. Промпт називає тул прямо і
   не каже "у тебе нема download-тула": раніше казав, одночасно з правилом "скачай ще цього артиста →
   downloadMusic", і модель то кликала тул, то відмовляла. Кнопка ⬇️ на картці до MainAgent не доходить.
8. Тул, що показує щось юзеру, робить це тільки через `AgentTurn` — не через `TelegramClient` і не через
   singleton-буфер.

---

## Out of scope
- Callback-кнопки (`DL:`, `RATE:`, `STREAM:`, …) → `CallbackDispatcher`
- Slash-команди → `UserInteractionOrchestrator`
- Вибір пошукового движку — `AggregatedSearchService` опитує всі три завжди
- Бібліотечні операції → `LibraryAgent`
- Cross-conversation пам'ять

---

## SDD checkpoints
- Новий user intent → потрібен новий `@Tool`? Описати: тригер, параметри, return, side-effect — тут і в промпті.
- Тул показує картки → параметр `InvocationParameters`, `AgentTurn.of(...).show(...)`.
- Зміна правил LibraryAgent → [library/spec.md](../library/spec.md) + рядок `manageLibrary` тут.
- Cost → перемикнути модель через `agents.main.model-name`; вартість ходу — у `[flow=…]` логах `AgentTraceListener`.
