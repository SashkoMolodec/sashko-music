# LibraryAgent — Spec

> Feature flows: [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md) · sub-libraries (working/vault)
> Events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
LLM-driven manager for the user's own processed library. Handles:
- catalog ops (release-level): search, move between sub-libraries, send to trash
- DJ tagging (track-level): rate / energy / function / comment for the currently playing track

The agent is a `@AiService` running on **Haiku** with its own memory id `<conversationId>:lib`.

Викликається двома шляхами:
1. **Free-text**: `MainAgentTools.manageLibrary()` → `LibraryAgentService.handle()`
2. **Slash**: `UserInteractionOrchestrator` → `/library <query>` → `LibraryAgentService.handle()`

---

## LangChain4j interface

```java
public interface LibraryAgent {
    @SystemMessage(LibraryAgentPrompts.SYSTEM)
    String chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
```

Built in `LibraryAgentConfig` with `haikuChatModel` + `libraryMemoryProvider` (16-message window).

`LibraryAgentService.handle(LibraryRequest)` calls `libraryAgent.chat(conversationId + ":lib", naturalCommand)`
and wraps the reply into `LibraryResult.ok(summary)`. Same contract — transports unchanged.

---

## Contract

```java
record LibraryRequest(String conversationId, String naturalCommand)
record LibraryResult(boolean success, String summary)
```

`summary` — short Ukrainian status string surfaced back to MainAgent (or logged to `chat_log` by orchestrator for slash path).

---

## Tools (`LibraryAgentTools`)

### Catalog ops (release-level)

| Tool | Trigger | Action |
|------|---------|--------|
| `searchOwnLibrary(query)` | "чи є в мене", "в моїй бібліотеці", "do I have X" | `LibrarySearchService.search(query, 5)`; updates `LastReleaseContextHolder` |
| `moveReleaseToSublibrary(releaseQuery, sublibrary)` | "посунь / перенеси / move ... у vault / working" | Resolves release → publishes `MoveReleaseTaskEvent` |
| `trashRelease(releaseQuery)` | "видали / прибери реліз" | Resolves release → pushes confirmation card (RM_OK/RM_NO buttons) into accumulator |
| `listSublibraries()` | "які vaults", "куди можна перенести" | Returns config list `library.sublibraries` |

### DJ tagging (track-level)

Require active `/np` track in `DjTagContextHolder`.

| Tool | Trigger | Action |
|------|---------|--------|
| `rateTrack(stars)` | "оціни N", "rate N" | `NowPlayingFlowService.rateTrack` |
| `setEnergy(level)` | "energy N", "енергія N" | `DjTagFlowService.setDjEnergy` |
| `setFunction(name)` | "intro / tool / banger / closer" + UA aliases | `DjTagFlowService.setDjFunction` |
| `addComment(text)` | "comment <text>", "коментар <text>" | `DjTagFlowService.addComment` |

> **Note:** `processFolder` і `reprocessRelease` видалені з LibraryAgent tools. Функціонал process/reprocess залишається в libraryagent FlowServices але не доступний через slash-команду або LLM tool — запускається внутрішньо.

---

## Release reference resolution

`resolveRelease(releaseQuery, conversationId)`:
1. If `releaseQuery` ∈ context markers (`"this"`, `"оцей"`, `"щойно"`, `""` …) → read `LastReleaseContextHolder` (persistent via `ChatStateStore`, flow_key `last_release`).
2. Otherwise → `LibrarySearchService.search(query, 1)` → top-1 → also writes back into `LastReleaseContextHolder`.
3. None → `null` → tool returns `"не знайшов реліз — уточни назву"`.

`LastReleaseContextHolder` is written from three places:
- `LibraryProcessingCompleteListener` after successful process
- `MoveReleaseCompleteListener` after move (so user can say "поверни оцей назад у working")
- `searchOwnLibrary` after FTS (top result wins as default referent)

It is cleared by the "стоп" handler in `UserInteractionOrchestrator`.

---

## Sub-libraries (physical separation)

Config: `library.sublibraries=working,vault`, `library.default-sublibrary=working`. Extensible by adding more names.

On disk: `lib/<sublibrary>/<artist>/<album>/…`. Navidrome multi-library and Traktor can mount only `working/` and ignore `vault/`.

`Release.sublibrary` (non-null, default `'working'`) tracks current placement. Updated whenever
`ReleaseRelocationService.move(...)` moves files via `MoveReleaseTaskEvent`.

`SublibraryMigrationRunner` (`ApplicationRunner`, order 100) runs on startup: every release whose
`directory_path` is not yet under a known sub-library is moved on disk to `lib/working/` and DB updated
(`directoryPath`, `coverPath`, all track `localPath` prefixes, `sublibrary='working'`). Idempotent.

---

## Hard rules

1. **All catalog ops are file-system + DB transactions.** Move and trash always go through libraryagent events so DB and disk stay in sync.
2. `trashRelease` NEVER deletes immediately — always shows a confirmation card. The buttons (`RM_OK:` / `RM_NO:`) are the only path to actual deletion.
3. Each task event MUST carry `conversationId` so result events can route back to the user.
4. Sub-agents do NOT call `chatBot.sendMessage` directly — only via `ChatResponseAccumulator` (for tool-time pushes) or via dedicated `*CompleteListener` (for async completion).
5. `MoveReleaseTaskEvent` → libraryagent: only listener allowed to mutate `release.directoryPath`, `release.sublibrary`, `track.localPath` for relocation purposes.
6. Track-level tools require `DjTagContextHolder` (active `/np`). Without it, return `"нема активного треку — спочатку /np"` and DO NOT publish events.

---

## Out of scope

- Telegram I/O (handled by `ChatResponseAccumulator` + `TelegramChatBot`)
- Filesystem writes outside relocation / trash (those belong to `processFolder` services)
- Direct discovery — searches that hit external APIs go to `DiscoveryAgent`
- Slash command parsing (`/np`, `/remove-release`, `/migrate-sublibs`) — handled by `UserInteractionOrchestrator`
- Process / reprocess LLM tools — функціонал існує в FlowServices але не виставлений як `@Tool`

---

## Library Full-Text Search (`LibrarySearchService`)

Reused by `searchOwnLibrary` tool. Unchanged: PostgreSQL FTS on `releases.search_vector` (GIN index),
`simple_unaccent` config, `websearch_to_tsquery` parsing, weights A (title + artists), B (genre tags),
C (track titles). See `LibrarySearchService` for details.

---

## SDD checkpoints

- New catalog op:
  1. Add `@Tool` method in `LibraryAgentTools`
  2. Add trigger description in `LibraryAgentPrompts.SYSTEM`
  3. Add row to **Tools** table in this spec
  4. If introducing new event class → also update CLAUDE.md Spring Event Map
- New sub-library:
  1. Add name to `library.sublibraries` config
  2. (No code change needed — buttons + valid targets driven from config)
- Track-level tool: same as catalog op, plus precondition note about `DjTagContextHolder`.
