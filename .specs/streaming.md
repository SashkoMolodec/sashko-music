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
| Tidal | `https://tidal.com/search?q={artist}%20{title}` |
| Deezer | `https://www.deezer.com/search/{artist}%20{title}` |

Всі посилання — search-based (не прямі), бо немає інтеграції з платформами через API.

---

## Notes
- `STREAM:` callback вимагає активної SearchContext (release має бути в кеші)
- При промаху кешу після рестарту — lazy-load з `ChatStateStore` (аналогічно до `DL:`)
