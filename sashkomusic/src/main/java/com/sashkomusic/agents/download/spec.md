# DownloadAgentService — Spec

> Feature flow: [/.specs/download.md](../../../../../../.specs/download.md)
> Events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
Ініціювати завантаження музики. **Не LLM** — детермінована обгортка над `MusicDownloadFlowService`. Існує щоб MainAgent мав typed contract і транспорт (in-process → A2A HTTP) можна було змінити без торкання `MainAgentTools`.

---

## Contract

```java
// Input
record DownloadRequest(String conversationId, String releaseId, String artist, String album, DownloadEngine engine)
```

| Поле | Коли заповнений |
|------|-----------------|
| `conversationId` | Завжди |
| `releaseId` | Якщо завантажуємо щойно знайдений реліз |
| `artist + album` | Якщо юзер написав "скачай X Y" без попереднього пошуку |
| `engine` | Зазвичай `null` — вибір на стороні FlowService |

Рівно одне з: `releaseId` або `(artist, album)` непорожнє.

```java
// Output
record DownloadResult(boolean success, String summary)
// summary → для LLM ("почав качати", "нема що качати")
// BotResponse-и вже в accumulator — LLM їх не бачить
```

---

## Routing

```
releaseId present   → musicDownloadFlowService.handleDownload(ctx, "DL:" + releaseId)
artist + album      → musicDownloadFlowService.getDownloadOptions(ctx, query)
empty query         → DownloadResult.failed("нема що качати — порожній запит")
```

---

## Download sources

| Engine | Handler | Якість |
|--------|---------|--------|
| `QOBUZ` | `QobuzDownloadFlowHandler` | 💎 lossless |
| `BANDCAMP` | `BandcampDownloadFlowHandler` | 🟢 |
| `SOULSEEK` | `SoulseekDownloadFlowHandler` | 💎/🟢/🟡/🔴 |
| `APPLE_MUSIC` | `AppleMusicDownloadFlowHandler` | 🟢 AAC 256 |
| `YOUTUBE_MUSIC` | `YouTubeMusicDownloadFlowHandler` | 🟢 AAC 128/256 |

Вибір движка за замовчуванням — в `MusicDownloadFlowService.initiateDefaultDownloadSearch()` (QOBUZ).

---

## Hard rules
1. **No LLM.**
2. Не конструювати `BotResponse` тут — тільки `accumulator.pushAll()` з того що повернув FlowService.
3. `handle()` завжди повертається швидко — не блокується на результат завантаження.
4. Stable contract: `DownloadRequest` і `DownloadResult` — чисті records без Spring типів.

---

## Out of scope
- Запис файлів → downloadagent
- Progress UI → event listeners + accumulator
- Вибір альтернативного джерела → `SEARCH_ALT:` callback

---

## SDD checkpoints
- Нове джерело → `DownloadEngine` enum + `DownloadFlowHandler` impl + рядок у таблиці.
  Також оновити `mainagent/download/spec.md` (кнопки alternate source).
- Дати LLM вибір між движками → `engine` параметр у `MainAgentTools.downloadMusic`.
