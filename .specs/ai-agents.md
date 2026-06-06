# AI Agents

## Overview

Два LLM агенти (LangChain4j `@AiService`):

| Agent | Model | Memory window | Memory ID | Purpose |
|-------|-------|--------------|-----------|---------|
| `MainAgent` | Claude Sonnet | 32 messages | `conversationId` | Orchestration, tool routing, user-facing responses |
| `DiscoveryAgent` | Claude Haiku | 16 messages | `conversationId + ":d"` | Music search decisions, query structuring |

Два детермінованих "агенти" (no LLM):
- `DownloadAgentService` — wraps `MusicDownloadFlowService`
- `LibraryAgentService` — wraps `LibraryCommandParser` + event publishing

---

## MainAgent

### System prompt (`MainAgentPrompts.SYSTEM`)
Мова: українська. Ключові правила (з промпту):
- Відповідати коротко, музикальний контекст
- Не вигадувати факти про музику
- Використовувати tools для пошуку/завантаження, не відповідати з пам'яті
- Тільки `findMusic` для пошуку (не hallucinate results)

### Tools (всі в `MainAgentTools`)

```
findMusic(query, conversationId)
  → ProgressNotifier "🔍 шукаю..." → DiscoveryAgentService.handle() → DiscoverResult
  → side-effect: release cards pushed to accumulator

findMusicOnDiscogs(query, conversationId)
findMusicOnBandcamp(query, conversationId)
findMusicOnMusicBrainz(query, conversationId)
  → direct path: DiscoveryAgentService.handle(preferredEngine=X) → no LLM
  → same side-effect

digDeeper(conversationId)
  → ReleaseSearchFlowService.switchStrategyAndSearch() → tries next engine

downloadMusic(artist, album, conversationId)
  → ProgressNotifier "⏳ шукаю на soulseek..." → DownloadAgentService.handle()
  → fire-and-forget, результат приходить через FileSearchResultEvent

discussRelease(question, conversationId)
  → SearchContextService.getSearchResults() → getMetadataWithTracks()
  → return formatted info (no side-effects)

manageLibrary(command, conversationId)
  → LibraryAgentService.handle() → LibraryCommandParser → events
  → requires active DjTagContext (/np must have been run first)
```

### Memory
- `PostgresChatMemoryStore` — persisted to DB
- Window: 32 messages per conversationId
- LangChain4j prompt caching enabled
- Separate memory per Telegram topic (conversationId = "chatId:topicId")

### ChatResponseAccumulator
Буфер між MainAgent tools і Telegram. Tools пушать `BotResponse` cards в accumulator, після завершення `chat()` → `drain()` → відправка.

```
accumulator.push(conversationId, response)   // під час tool execution
responses = accumulator.drain(conversationId) // після MainAgent.chat() returns
final = drained + [aiTextResponse]
```

---

## DiscoveryAgent

### System prompt
Визначає стратегію пошуку: яке джерело спробувати першим, коли зупинитись.

### Tools (в `DiscoveryAgentTools`)

```
search(query)
  → SearchRequestExtractor.extract(query) → MetadataSearchRequest
  → пробує SearchEngine sequentially until hit
  → SearchContextService.saveSearchContext(conversationId+":d", ...)
  → return "found N releases on ENGINE"

getPreviousSearches()
  → SearchContextService.getSearchResults(conversationId+":d")
  → return list of previous results (допомагає LLM не повторювати)
```

### Two execution paths

**LLM path** (findMusic tool):
```
DiscoveryAgent.chat(memoryId, userQuery) → LLM вирішує який engine, викликає search()
```

**Direct path** (findMusicOnX tools):
```
DiscoveryAgentTools.runSearch(preferredEngine, query) → bypass LLM
```

Direct path швидший і дешевший — використовується коли юзер явно назвав джерело.

### SearchRequestExtractor
`@AiService` на Haiku. Free-text → structured:
```
"Burial Untrue 2007" → MetadataSearchRequest {
  artist: "Burial",
  release: "Untrue",
  year: 2007,
  label: null,
  country: null,
  format: null
}
```

---

## FolderNameParser (libraryagent LLM)

`@AiService` на Haiku. Folder name → MetadataSearchRequest.
```
"01. Burial - Untrue [Hyperdub 2007]" → {artist:"Burial", release:"Untrue", year:2007, label:"Hyperdub"}
```
Використовується в `/process` і file watcher.

---

## DownloadBatchAnalyzer (mainagent LLM)

`@AiService` на Haiku. Soulseek results → recommendation summary.
Input: tracklist очікуваного реліза + список батчів з файлами
Output: short summary типу "варіант 1 найкращий: повний FLAC з правильною кількістю треків"

---

## Config (`agents/config`)

| Bean | Model | Notes |
|------|-------|-------|
| `sonnetChatModel` | Claude Sonnet | MainAgent |
| `haikuChatModel` | Claude Haiku | DiscoveryAgent, FolderNameParser, DownloadBatchAnalyzer, SearchRequestExtractor |
| `mainAgentMemoryProvider` | PostgresChatMemoryStore | 32 messages |
| `discoveryAgentMemoryProvider` | PostgresChatMemoryStore | 16 messages |
| `traceListener` | AiServiceEventListener | Logs tool calls |
