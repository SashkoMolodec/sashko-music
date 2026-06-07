# LibraryAgent — Spec

> Feature flows: [/.specs/dj-tagging.md](../../../../../../.specs/dj-tagging.md) · sub-libraries (working/vault)
> Events: [/.specs/events.md](../../../../../../.specs/events.md)

## Purpose
LLM-driven manager for the user's own processed library. Handles:
- catalog ops (release-level): search, move between sub-libraries, send to trash
- processing / reprocessing of downloads into the library
- smartlists: dynamic M3U playlists driven by DSL rules over track tags

DJ tagging (rate / energy / function / comment) is **button-driven via `/np`** and intentionally NOT an agent tool — see "DJ tagging" section.

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

Built in `LibraryAgentConfig` with `haikuChatModel` + `libraryMemoryProvider` (16-message window, backed by `PostgresChatMemoryStore` → `conversation_messages` table under key `<conversationId>:lib`).

`LibraryAgentService.handle(LibraryRequest)` calls `libraryAgent.chat(conversationId + ":lib", naturalCommand)`
and wraps the reply into `LibraryResult.ok(summary)`. Same contract — transports unchanged.

---

## Contract

```java
record LibraryRequest(String conversationId, String naturalCommand)
record LibraryResult(boolean success, String summary)
```

`summary` — short Ukrainian status string surfaced back to MainAgent. For slash path (`/library <query>`),
`UserInteractionOrchestrator` also calls `MainChatMemoryProvider.appendUserAndAi(conversationId, "/library " + query, summary)`
so MainAgent sees the activity in its persistent memory (`conversation_messages` under `<conversationId>`).

---

## Tools (`LibraryAgentTools`)

### Catalog ops (release-level)

| Tool | Trigger | Action |
|------|---------|--------|
| `searchOwnLibrary(query)` | "чи є в мене", "в моїй бібліотеці", "do I have X" | `LibrarySearchService.search(query, 5)`; updates `LastReleaseContextHolder` |
| `moveReleaseToSublibrary(releaseQuery, sublibrary)` | "посунь / перенеси / move ... у vault / working" | Resolves release → publishes `MoveReleaseTaskEvent` |
| `trashRelease(releaseQuery)` | "видали / прибери реліз" | Resolves release → pushes confirmation card (RM_OK/RM_NO buttons) into accumulator |
| `listSublibraries()` | "які vaults", "куди можна перенести" | Returns config list `library.sublibraries` |

### Smartlists (dynamic M3U playlists)

| Tool | Trigger | Action |
|------|---------|--------|
| `createSmartlist(name, naturalDescription)` | "створи смартлист X — ...", "make smartlist <name> with <rules>" | `SmartlistDslExtractor` (Haiku) → DSL; stores `SmartlistDraft` via `ChatStateStore` (flow_key `smartlist_create`); pushes preview card (top-5 tracks + DSL summary) with `SM:OK` / `SM:NO` buttons into accumulator. User can refine via free text (handled by `SmartlistCreationOngoingFlow`). |
| `listSmartlists()` | "які смартлисти", "list smartlists" | `SmartlistService.list()` — name, track count, DSL summary |
| `renameSmartlist(oldName, newName)` | "перейменуй смартлист X на Y" | `SmartlistService.rename` — DB row + `.m3u8` file renamed |
| `deleteSmartlist(name)` | "видали смартлист X" | `SmartlistService.delete` — DB row + `.m3u8` file removed |
| `regenerateAllSmartlists()` | "оновити всі смартлисти", "regenerate smartlists" | `SmartlistService.regenerateAll` — re-eval + rewrite every `.m3u8` |

DSL form: `{ "conditions": [...] }`. **OR/AND semantics**: multiple conditions on the **same field** are ORed; conditions on **different fields** are ANDed. Supported fields & ops:
- `comment`, `label`, `genre` → `contains` (case-insensitive substring on `track_tags.tag_value`) or `is` (exact match / `null` ⇒ tag absent)
- `year` → `contains` / `is` (string match) **or** numeric: `range` with raw 4-digit integers (`min=1970, max=1989`), or `gt`/`gte`/`lt`/`lte` with a single integer year
- `rating` → `range` with `min`/`max` in `1..5` stars (mapped to WMP 51/102/153/204/255), `gt`/`gte`/`lt`/`lte` with single `1..5` value, `is` with single `1..5` value, or `is null` (unrated)
- `sublibrary` → `is "working"` / `is "vault"` or `contains` (direct column on `tracks`, not a tag). Use when user wants to filter by sub-library.

Comparison ops (`gt`/`gte`/`lt`/`lte`) are preferred for one-sided bounds ("> 1990", "≥ 4 stars"); `range` is for closed intervals.

`SmartlistEvaluator` rejects `range`/`gt`/`gte`/`lt`/`lte` on `comment`/`label`/`genre`/`sublibrary` with `IllegalArgumentException` — the LLM prompt forbids it but defence-in-depth catches drift.

Auto-regeneration: `SmartlistRegenerationListener` (`@Async`) listens to `TrackUpdateResultEvent`, `TagChangesNotificationEvent`, `TrackAnalysisCompleteEvent`, `LibraryProcessingCompleteEvent` and calls `SmartlistService.regenerateAll()` — re-evaluates every smartlist and rewrites `{library.rootPath}/Smartlists/<name>.m3u8`.

### Processing / reprocessing (release-level)

| Tool | Trigger | Action |
|------|---------|--------|
| `processFolder(folderHint)` | "обробити папку X", "process Y", "опрацюй цей даунлоад" | `ProcessFolderFlowService.process` — empty hint = most recent download |
| `reprocessRelease(target, skipRetag, force)` | "переобробити X", "перетегни все", "reprocess all" | Builds `/reprocess` command, delegates to `ReprocessReleasesFlowService.handle` |

### DJ tagging (track-level)

Track-level DJ tagging (rate / energy / function / comment) is **not** exposed as agent tools — it is driven entirely by the inline keyboard from `/np` (`RATE:`, `ENERGY_RATE:`, `FUNCTION_RATE:`, `ADD_COMMENT:` callbacks → `NowPlayingFlowService` / `DjTagFlowService` / `CommentInputOngoingFlow`). The agent should not attempt to mutate track tags directly; if the user asks for it in NL, point them at `/np`.

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
- Slash command parsing (`/np`) — handled by `UserInteractionOrchestrator`. Release removal goes through the `trashRelease` tool; no dedicated slash exists.

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
