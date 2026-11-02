# AI Agents

Джерело правди — `spec.md` у пакетах агентів; тут лише карта.

## Overview

| Agent | Model | Memory window | Memory ID | Purpose |
|-------|-------|--------------|-----------|---------|
| `MainAgent` | Claude Sonnet | 32 messages (+ авто-підсумок) | `conversationId` | Єдиний, хто говорить до юзера: маршрутизація, discovery-тули, вступи до карток |
| `LibraryAgent` | Claude Haiku | 16 messages | `conversationId + ":lib"` | Власна бібліотека: пошук, переміщення, process, смартлисти, що грає |

Одноразові LLM-сервіси без пам'яті й тулів: `SearchRequestExtractor`, `ReleaseRecommender`, `MusicResearcher`
(web search), `FolderNameParser`, `DownloadBatchAnalyzer`, `SmartlistDslExtractor`, `ListenLinkWebSearch`.

Специфікації: [main](../sashkomusic/src/main/java/com/sashkomusic/agents/main/spec.md) ·
[discovery tools](../sashkomusic/src/main/java/com/sashkomusic/agents/discovery/spec.md) ·
[library](../sashkomusic/src/main/java/com/sashkomusic/agents/library/spec.md) ·
[architecture](../sashkomusic/src/main/java/com/sashkomusic/agents/ARCHITECTURE.md)

## Хід вільного тексту

```
UserInteractionOrchestrator.runMainAgent
  └─ MainAgentService.chat(ctx, text)
       ├─ AgentTurn(ctx, progress → ProgressNotifier)
       ├─ MainAgent.chat(conversationId, text, turn.parameters())
       │    ├─ search / exploreAndRecommend / findSimilar / digDeeper / getTrackList / researchMusic  (DiscoveryTools)
       │    ├─ downloadMusic(artist, album) → MusicDownloadFlowService.getDownloadOptions   (без LLM)
       │    └─ manageLibrary(command) → LibraryAgentService → LibraryAgent (той самий AgentTurn)
       └─ [вступ MainAgent] + turn.shown() + по повідомленню на кожен стос ходу
```

Тули отримують хід через LangChain4j `InvocationParameters` і показують картки тільки через `AgentTurn`.

## Config (`agents/config`)

| Bean | Model | Споживачі |
|------|-------|-----------|
| `sonnetChatModel` | Claude Sonnet (`agents.main.*`) | MainAgent |
| `haikuChatModel` | Claude Haiku (`agents.haiku.*`) | LibraryAgent, авто-підсумок пам'яті, екстрактори |
| `webSearchChatModel` | Claude Haiku + server `web_search` | `MusicResearcher`, `ReleaseRecommender`, `ListenLinkWebSearch` |
| `mainMemoryProvider` | `PostgresChatMemoryStore` | MainAgent, 32 messages |
| `libraryMemoryProvider` | `PostgresChatMemoryStore` | LibraryAgent, 16 messages |

`AgentTraceListener` на кожній моделі пише `[agent=main|haiku|web-search]` і вартість ходу (`[flow=…] TOTAL`).
