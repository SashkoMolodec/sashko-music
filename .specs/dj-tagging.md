# DJ Tagging Feature (/np + rating)

## Purpose
Тегування поточного треку під час DJ-сесії або прослуховування: оцінка (1-5), енергія (1-5), функція (intro/tool/banger/closer), коментар.

---

## /np command (Now Playing)

```
User: /np   (або кнопка "шо грає 🎵")
  └─ NowPlayingFlowService.nowPlaying(ctx) → NowPlayingResult(responses, agentContext)
       ├─ NowPlayingResolver.resolve()          ← спільний для /np, /npalbum і LLM-тула
       │    ├─ NavidromeClient.getCurrentlyPlayingTrackInfo()  (/rest/getNowPlaying)
       │    ├─ fallback: IcecastClient.getCurrentlyPlayingTrackInfo()  (status.xsl)
       │    └─ TrackService.findByArtistAndTitleOptional(artist, title) → трек + реліз + жанри
       ├─ якщо нічого не грає → "зараз нич не грає 🥺"
       ├─ DjTagContextHolder.save(conversationId, DjTagContext { track, waitingForComment:false })
       ├─ LastReleaseContextHolder.set(...) — реліз, що грає, стає референтом "this"
       │    ("перенеси це у vault", "маю схоже?" працюють без називання релізу)
       ├─ повертає картку треку з кнопками (RATE:/ENERGY_RATE:/FUNCTION_RATE:/ADD_COMMENT:)
       └─ agentContext → UserInteractionOrchestrator → mainMemoryProvider.appendUserAndAi(...)
            "зараз грає: <артист> — <назва> (реліз: <назва>, <рік>), жанри: …, рейтинг: N/5, …"
            ← без цього рядка MainAgent на "схоже до того що зараз грає?" не знає, що грає
```

`/npalbum` (`NowPlayingAlbumFlowService`) робить те саме через той самий резолвер — і так само пише
`LastReleaseContextHolder` та agentContext.

Плеєр можна прочитати і з вільного тексту: `LibraryAgentTools.nowPlayingTrack()` (той самий
`NowPlayingResolver`) — див. [agents/library/spec.md](../sashkomusic/src/main/java/com/sashkomusic/agents/library/spec.md).

---

## Rating (RATE: callback)

```
User clicks ⭐⭐⭐ (3 stars)
  └─ NowPlayingFlowService.handleRate(ctx, "RATE:3")
       ├─ DjTagContextHolder.get(conversationId) → track
       ├─ publishEvent(new RateTrackTaskEvent(trackId, 3, conversationId))
       └─ return "⭐⭐⭐ (3/5)"

RateTrackTaskEvent → libraryagent RateTrackListener
  └─ TrackTagSyncService.setRating(trackId, 3)
       └─ upsert tag value (WMP format: rating * 20 → 60 for 3/5)
  └─ publishes TrackUpdateResultEvent → mainagent → "✅ оцінено: 3/5"
```

---

## DJ panel (EXPAND_DJ_RATE: callback)

```
User clicks [↕ DJ теги]
  └─ DjTagFlowService.expandDjRatePanel(ctx, "EXPAND_DJ_RATE:<trackId>")
       └─ повертає розширену картку:
            Енергія: 1️⃣ 2️⃣ 3️⃣ 4️⃣ 5️⃣    (ENERGY_RATE:1..5)
            Функція: [intro] [tool] [banger] [closer]   (FUNCTION_RATE:intro..)
            [💬 коментар]   (ADD_COMMENT:)
```

---

## Energy (ENERGY_RATE: callback)

```
User clicks [3] для енергії
  └─ DjTagFlowService.handleEnergyRate(ctx, "ENERGY_RATE:3")
       ├─ DjTagContextHolder.get(conversationId) → track
       ├─ publishEvent(new SetEnergyTaskEvent(trackId, "E3", conversationId))
       └─ return "⚡ енергія: 3/5"

SetEnergyTaskEvent → libraryagent SetEnergyListener
  └─ TrackTagSyncService.setEnergy(trackId, 3)
  └─ TrackUpdateResultEvent → "✅"
```

---

## Function (FUNCTION_RATE: callback)

```
User clicks [banger]
  └─ DjTagFlowService.handleFunctionRate(ctx, "FUNCTION_RATE:banger")
       ├─ publishEvent(new SetFunctionTaskEvent(trackId, "banger", conversationId))
       └─ return "🔥 функція: banger"

SetFunctionTaskEvent → libraryagent SetFunctionListener
  └─ TrackTagSyncService.setFunction(trackId, "banger")
  └─ TrackUpdateResultEvent → "✅"
```

---

## Comment (ADD_COMMENT: callback + CommentInputOngoingFlow)

```
User clicks [💬 коментар]
  └─ DjTagFlowService.handleCommentAdd(ctx, "ADD_COMMENT:<trackId>")
       ├─ DjTagContextHolder.setWaitingForComment(conversationId, true)
       └─ return "✏️ тепер введи коментар:"

User types "dark and heavy, good for peak"
  └─ CommentInputOngoingFlow.handle(ctx, "dark and heavy, good for peak")
       ├─ appliesTo(): DjTagContextHolder.isWaitingForComment(conversationId)
       ├─ DjTagContextHolder.setWaitingForComment(conversationId, false)
       ├─ publishEvent(new AddCommentTaskEvent(trackId, comment, conversationId))
       └─ return "💬 коментар збережено"

AddCommentTaskEvent → libraryagent AddCommentListener
  └─ TrackTagSyncService.addComment(trackId, comment)
  └─ TrackUpdateResultEvent → "✅"
```

---

## manageLibrary tool (LLM path)

Тегування з вільного тексту ("постав цьому треку 5 зірок") **не реалізоване**: у `LibraryAgentTools`
немає ні `rateTrack`, ні `setEnergy`, ні `setFunction`, ні `addComment`, а `LibraryCommandParser`
(regex-парсер під це) ніде не викликається — живий лишився тільки його тест. Єдиний шлях запису
DJ-тегів — кнопки на картці `/np`.

`LibraryAgentPrompts.SYSTEM` **не має** перелічувати ці тули: промпт із фантомним тулом гірший за
відсутність тула. Haiku «вірить», що має доступ до треку, і замість виклику `nowPlayingTrack`
вигадує пояснення («плеєр не передає цю інформацію»). Читати, що грає, агент може
(`nowPlayingTrack`) — писати теги ні.

---

## DjTagContextHolder persistence

Persisted via `ChatStateStore` (flow_key: `"dj_tag"`).

```java
DjTagContext {
  TrackInfo track,           // trackId, title, artist, album
  boolean waitingForComment
}
```

Пережив рестарт JVM — `/np` не потрібно повторювати після перезавантаження.

---

## TagChangesNotificationEvent

`TrackTagSyncService` батчує зміни тегів і публікує `TagChangesNotificationEvent` з diff:
```
"оновлено теги:
  rating: 3 → 5
  energy: none → 4"
```
mainagent показує цей diff юзеру.
