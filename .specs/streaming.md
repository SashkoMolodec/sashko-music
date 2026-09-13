# Streaming Feature

## Purpose
Генерація посилань на стрімінгові платформи для знайденого реліза.

---

## Flow

```
User clicks [▶ Stream] на release card
  └─ CallbackDispatcher → StreamingFlowService.handleStreamingPlatforms(ctx, "STREAM:<releaseId>")
       ├─ SearchContextService.getReleaseMetadata(releaseId, conversationId)
       ├─ будує search URL для кожної платформи: "{artist} {title}"
       └─ повертає картку з кнопками-посиланнями
```

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

**`ListenLinkResolver` (🎯 button)** — прямий (не пошуковий) лінк, якщо вдалось резолвнути, ставиться ПЕРШИМ:
- `BANDCAMP` реліз → сама сторінка релізу (masterId) — це вже прямий лінк
- `DISCOGS` реліз → перший community YouTube video з release page (`DiscogsClient.getPrimaryVideoUrl`), fallback на yt-music scraper пошук
- `MUSICBRAINZ` реліз → yt-music scraper пошук за artist+title (`sm-scraper` `/ytmusic/search`)

Ніякого LLM чи вільного web-пошуку тут — кожне джерело або прив'язане до конкретного релізу, або структурований artist+album match проти реального каталогу.

---

## Notes
- `STREAM:` callback вимагає активної SearchContext (release має бути в кеші)
- При промаху кешу після рестарту — lazy-load з `ChatStateStore` (аналогічно до `DL:`)
