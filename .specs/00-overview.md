# Sashko Music — Project Overview

## What it is
Telegram bot + music library manager. User шукає музику, качає, тегує треки через чат.
Single Spring Boot JVM monolith. Python `sm-audio-analyzer` (Essentia) живе окремо і спілкується через REST.

---

## High-level architecture

```
Telegram ─────► TelegramChatBot
                     │
              UserInteractionOrchestrator
               ├─ slash commands   ──► FlowServices
               ├─ OngoingFlows     ──► FlowServices
               ├─ callbacks        ──► CallbackDispatcher ──► FlowServices
               └─ free text        ──► MainAgent (Sonnet LLM)
                                         └─ @Tool methods ──► sub-agents

Sub-agents:
  DiscoveryAgentService   ← Haiku LLM + search engines (MusicBrainz, Discogs, Bandcamp)
  DownloadAgentService    ← deterministic, delegates to downloadagent via events
  LibraryAgentService     ← deterministic, regex parser, delegates to libraryagent via events

Spring Events (async):
  mainagent ◄──► downloadagent   (search, download, cancel, errors)
  mainagent ◄──► libraryagent    (process, reprocess, tag updates)
  Python    ──►  libraryagent    (audio analysis callback via REST)
```

---

## Entry points & routing

### Slash commands
| Command | Handler | Feature spec |
|---------|---------|-------------|
| `/np` | `NowPlayingFlowService` | [dj-tagging.md](dj-tagging.md) |
| `/process <path>` | `ProcessFolderFlowService` | [library-processing.md](library-processing.md) |
| `/reprocess <release>` | `ReprocessReleasesFlowService` | [library-processing.md](library-processing.md) |
| `/newtopic [name]` | `NewTopicFlowService` | inline, see below |
| `стоп` | `UserInteractionOrchestrator` | clears all caches |

### Callback button prefixes (CallbackDispatcher)
| Prefix | Handler | Feature spec |
|--------|---------|-------------|
| `CARD:` | `ReleaseSearchFlowService` | [search.md](search.md) |
| `DIG_DEEPER` | `ReleaseSearchFlowService` | [search.md](search.md) |
| `PAGE:` | `ReleaseSearchFlowService` | [search.md](search.md) |
| `NOOP` | — | disabled button |
| `DL:` | `MusicDownloadFlowService` | [download.md](download.md) |
| `SEARCH_ALT:` | `MusicDownloadFlowService` | [download.md](download.md) |
| `CANCEL_DL:` | `MusicDownloadFlowService` | [download.md](download.md) |
| `STREAM:` | `StreamingFlowService` | [streaming.md](streaming.md) |
| `RATE:` | `NowPlayingFlowService` | [dj-tagging.md](dj-tagging.md) |
| `EXPAND_DJ_RATE:` | `DjTagFlowService` | [dj-tagging.md](dj-tagging.md) |
| `ENERGY_RATE:` | `DjTagFlowService` | [dj-tagging.md](dj-tagging.md) |
| `FUNCTION_RATE:` | `DjTagFlowService` | [dj-tagging.md](dj-tagging.md) |
| `ADD_COMMENT:` | `DjTagFlowService` | [dj-tagging.md](dj-tagging.md) |

### OngoingFlows (multi-turn text dialogue)
| Flow | Active when | Feature spec |
|------|------------|-------------|
| `DownloadOptionSelectionOngoingFlow` | `DownloadContextHolder` non-empty | [download.md](download.md) |
| `ProcessFolderSelectionOngoingFlow` | `ProcessFolderContextHolder` has active context | [library-processing.md](library-processing.md) |
| `CommentInputOngoingFlow` | `DjTagContextHolder.waitingForComment` | [dj-tagging.md](dj-tagging.md) |

### MainAgent tools (LLM-invoked)
| Tool | Trigger | Feature spec |
|------|---------|-------------|
| `findMusic` | "знайди", "пошукай", "є щось від" | [search.md](search.md) |
| `findMusicOnDiscogs` | "знайди на discogs" | [search.md](search.md) |
| `findMusicOnBandcamp` | "знайди на bandcamp" | [search.md](search.md) |
| `findMusicOnMusicBrainz` | "знайди на musicbrainz" | [search.md](search.md) |
| `digDeeper` | "покажи ще", "наступне джерело" | [search.md](search.md) |
| `downloadMusic` | "скачай", "завантаж" | [download.md](download.md) |
| `discussRelease` | "які треки?", "що за жанр?" | [search.md](search.md) |
| `manageLibrary` | "оціни", "energy 3", "banger" | [dj-tagging.md](dj-tagging.md) |

---

## Infrastructure
- **PostgreSQL** — music DB (Track, Release, Artist, Label, Tag) + conversation state (`chat_state`)
- **Slskd** — Soulseek daemon, REST API
- **Navidrome** — music server (now-playing source)
- **Icecast** — streaming server (now-playing fallback)
- **sm-scraper** — Python/FastAPI: Bandcamp scraper + YouTube Music search
- **sm-audio-analyzer** — Python/Essentia: BPM, MFCC, danceability, loudness

## Module index
- [search.md](search.md) — музичний пошук (DiscoveryAgent, 3 джерела, пагінація)
- [download.md](download.md) — завантаження (5 движків, вибір варіанту, cancel)
- [library-processing.md](library-processing.md) — /process, /reprocess, теги, аудіо-аналіз
- [dj-tagging.md](dj-tagging.md) — /np, рейтинг, енергія, функція, коментарі
- [streaming.md](streaming.md) — посилання на стрімінгові платформи
- [ai-agents.md](ai-agents.md) — MainAgent, DiscoveryAgent, prompts, memory
- [conversation-state.md](conversation-state.md) — ChatStateStore, всі context holders
- [events.md](events.md) — всі Spring events, async map
