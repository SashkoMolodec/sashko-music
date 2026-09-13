# Streaming Feature

## Purpose
Один клік на 🎧 → одразу робочий лінк, де послухати реліз. Пошукові посилання по платформах —
лише фолбек, коли прямий лінк не резолвнувся.

---

## Flow

```
User clicks [🎧] на release card
  └─ CallbackDispatcher → StreamingFlowService.handleStreamingPlatforms(ctx, "STREAM:<releaseId>")
       ├─ SearchContextService.getReleaseMetadata(releaseId, conversationId)
       ├─ ListenLinkResolver.resolve(metadata)
       │    ├─ знайшов → текст з URL + кнопка "▶️ слухати" першою, далі платформи
       │    └─ ні       → "прямого лінку не знайшов" + самі лише платформи
       └─ одне повідомлення, жодного другого кроку
```

Треклист тут **не** тягнеться: `getMetadataWithTracks` робив живий виклик до движка і був основним
джерелом затримки, а до задачі "дай послухати" не додавав нічого. Треки лишаються доступні через
"які треки" (`DiscoveryAgentTools.getTrackList`).

---

## Platforms

| Platform | URL pattern |
|----------|-------------|
| Spotify | `https://open.spotify.com/search/{artist}%20{title}` |
| Apple Music | `https://music.apple.com/search?term={artist}%20{title}` |
| YouTube Music | `https://music.youtube.com/search?q={artist}+{title}` |
| SoundCloud | `https://soundcloud.com/search?q={artist}%20{title}` |
| Bandcamp | `https://bandcamp.com/search?q={artist}%20{title}` |

Ці посилання лишаються search-based — немає прямої інтеграції з жодною платформою через API.
Вони показуються як фолбек-ряд під прямим лінком, або самі, якщо прямий не знайшовся.

**`ListenLinkResolver`** — прямий (не пошуковий) лінк, ставиться ПЕРШИМ як кнопка "▶️ слухати"
і дублюється URL-ом у тексті повідомлення. Порядок джерел:
1. `BANDCAMP` реліз → сама сторінка релізу (masterId) — це вже прямий лінк
2. `DISCOGS` реліз → перший community YouTube video з release page (`DiscogsClient.getPrimaryVideoUrl`)
3. Далі / `MUSICBRAINZ` → yt-music scraper пошук за artist+title (`sm-scraper` `/ytmusic/search`).
   Сам скрапер уже деградує з альбому на **окремий трек** того ж артиста, якщо альбому нема —
   це і є фолбек "хоча б один трек"
4. Нічого не зматчилось → `ListenLinkWebSearch` — LLM web search на `discoveryChatModel`

Пункти 1-3 структуровані: джерело або прив'язане до конкретного релізу, або це artist+album match
проти реального каталогу. Пункт 4 — єдиний неструктурований, тому **останній**: коштує API round-trip
і повертає URL, який ніхто не звіряв з каталогом. Відповідь `NONE`, не-http або рядок з пробілом
відкидаються.

---

## Notes
- `STREAM:` callback вимагає активної SearchContext (release має бути в кеші)
- При промаху кешу після рестарту — lazy-load з `ChatStateStore` (аналогічно до `DL:`)
