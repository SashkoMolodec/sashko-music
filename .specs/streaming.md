# Streaming Feature

## Purpose
Один клік на 🎧 → одне текстове повідомлення, з якого одразу видно де послухати: URL, треклист і
(для discogs) решта відео релізу окремими посиланнями.

---

## Flow

```
User clicks [🎧] на release card
  └─ CallbackDispatcher → StreamingFlowService.handleStreamingPlatforms(ctx, "STREAM:<releaseId>")
       ├─ SearchContextService.getReleaseMetadata(releaseId, conversationId)
       ├─ ListenLinkResolver.resolve(metadata)
       └─ одне текстове повідомлення:
            ▶️ artist — title (source)
            <primary url>
            <треклист>
            🎬 ще з discogs: <решта відео, title + url>
```

**Жодних кнопок у відповіді.** Ні "слухати", ні ряду платформ — URL у тексті вже клікабельний,
кнопка-дублікат того ж посилання нічого не додавала. Пошукові посилання по платформах
(Spotify/Apple/SoundCloud/Bandcamp/YT) прибрані повністю: це були *пошукові* URL, не прямі, тобто
рівно той зайвий крок, який ця фіча має усувати.

Треклист тягнеться через `getMetadataWithTracks` — це живий виклик до движка, тому найповільніша
частина відповіді, але без нього незрозуміло що саме слухаєш.

---

## `ListenLinkResolver`

Повертає `Result(source, links)` — `links` ніколи не порожній, перший елемент ведучий.
Порядок джерел:

1. `BANDCAMP` реліз → сама сторінка релізу (masterId) — це вже прямий лінк
2. `DISCOGS` реліз → **усі** community YouTube-відео з release page (`DiscogsClient.getVideos`):
   перше стає ведучим лінком, решта йдуть списком "🎬 ще з discogs" — по них видно назви треків,
   тому це найшвидший спосіб перестрибувати між окремими треками релізу
3. Discogs без відео / `MUSICBRAINZ` → yt-music scraper пошук за artist+title
   (`sm-scraper` `/ytmusic/search`). Сам скрапер уже деградує з альбому на **окремий трек** того ж
   артиста, якщо альбому нема — це і є фолбек "хоча б один трек"
4. Нічого не зматчилось → `ListenLinkWebSearch` — LLM web search на `discoveryChatModel`

Пункти 1-3 структуровані: джерело або прив'язане до конкретного релізу, або це artist+album match
проти реального каталогу. Пункт 4 — єдиний неструктурований, тому **останній**: коштує API round-trip
і повертає URL, який ніхто не звіряв з каталогом. Відповідь `NONE`, не-http або рядок з пробілом
відкидаються.

---

## Release card

Посилання на сторінку релізу живе **в тексті картки**, не кнопкою:
`📍 1/5 (discogs 🔗 https://...)`. Окрему 🔗-кнопку прибрано — вона займала місце в ряду
навігації заради того самого URL.

Ряд кнопок картки: `⬅️` `🎧` `⬇️` `➡️`.

---

## Notes
- `STREAM:` callback вимагає активної SearchContext (release має бути в кеші)
- При промаху кешу після рестарту — lazy-load з `ChatStateStore` (аналогічно до `DL:`)
- Картка "нічого не знайдено" більше не має 🎧 (нема релізу → нема що резолвити), лишились 💿 і ⛏️
