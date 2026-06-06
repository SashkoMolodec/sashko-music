# DJ Tagging Feature (/np + rating)

## Purpose
Тегування поточного треку під час DJ-сесії або прослуховування: оцінка (1-5), енергія (1-5), функція (intro/tool/banger/closer), коментар.

---

## /np command (Now Playing)

```
User: /np
  └─ NowPlayingFlowService.nowPlaying(ctx)
       ├─ NavidromeClient.getCurrentlyPlaying()
       │    └─ Navidrome API: /rest/getNowPlaying
       ├─ fallback: IcecastClient.getCurrentlyPlaying()
       │    └─ Icecast status.xsl
       ├─ якщо нічого не грає → "😔 нічого не грає зараз"
       ├─ DjTagContextHolder.save(conversationId, DjTagContext { track, waitingForComment:false })
       └─ повертає картку треку з кнопками:
            ⭐⭐⭐⭐⭐ (RATE:1..5)
            [↕ DJ теги] (EXPAND_DJ_RATE:)
```

---

## Rating (RATE: callback)

```
User clicks ⭐⭐⭐ (3 stars)
  └─ NowPlayingFlowService.handleRate(ctx, "RATE:3")
       ├─ DjTagContextHolder.get(conversationId) → track
       ├─ SetEnergyTaskProducer?  — No, це рейтинг
       ├─ RateTrackTaskProducer.send(RateTrackTaskDto { trackId, rating:3 })
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
       ├─ SetEnergyTaskProducer.send(SetEnergyTaskDto { trackId, energy:3 })
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
       ├─ SetFunctionTaskProducer.send(SetFunctionTaskDto { trackId, function:"banger" })
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
       ├─ AddCommentTaskProducer.send(AddCommentTaskDto { trackId, comment })
       └─ return "💬 коментар збережено"

AddCommentTaskEvent → libraryagent AddCommentListener
  └─ TrackTagSyncService.addComment(trackId, comment)
  └─ TrackUpdateResultEvent → "✅"
```

---

## manageLibrary tool (LLM path)

MainAgent може викликати `manageLibrary(command)` якщо юзер написав вільний текст:

```
User: "постав цьому треку 5 зірок і energy 4"
  └─ MainAgent → manageLibrary("постав 5 зірок і energy 4", conversationId)
       └─ LibraryAgentService.handle(LibraryRequest)
            └─ LibraryCommandParser (regex) → Rate(5), SetEnergy(4)
            └─ публікує обидва events
```

`LibraryCommandParser` підтримує:
| Pattern | Результат |
|---------|-----------|
| "rate 5", "оціни 5", "5 stars", "5 зірок", "5/5" | `Rate(5)` |
| "energy 3", "енергія 3", "e3" | `SetEnergy(3)` |
| "intro", "tool", "banger", "closer", "інтро", "тул", "банжер", "клозер", "марк" | `SetFunction(type)` |
| "comment текст", "коментар текст" | `AddComment(text)` |
| інше | `Unknown(reason)` |

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
