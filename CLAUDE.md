# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Sashko Music — Architecture & Coding Standards

## Build & Test Commands

```bash
# Build
./gradlew :sashkomusic:build

# Run tests
./gradlew :sashkomusic:test

# Run a single test class
./gradlew :sashkomusic:test --tests com.sashkomusic.agents.library.LibraryCommandParserTest

# Run locally (requires infrastructure via docker compose)
./gradlew :sashkomusic:bootRun

# Start infrastructure (PostgreSQL, Slskd, Navidrome, Icecast)
docker compose up -d

# Python audio analyzer (local dev)
cd sm-audio-analyzer && pip install -r requirements.txt && python src/main.py
```

The project is a single Gradle module `sashkomusic` — one Spring Boot application. The Python `sm-audio-analyzer` is built/deployed via Docker and communicates via REST.

**Key dependencies:** LangChain4j 1.8.0-beta15 (Anthropic), Resilience4j, WireMock (test), Flyway, JAudioTagger, spring-boot-starter-webflux.

## Module Map

| Package | Responsibility | Must NOT contain |
|---|---|---|
| `com.sashkomusic` (root) | `SashkoMusicApplication` — single `@SpringBootApplication`, `@EnableAsync`, `@EnableScheduling` | Any business logic |
| `com.sashkomusic.config` | Cross-cutting Spring config (`AsyncConfig` — `asyncExecutor` `ThreadPoolTaskExecutor`) | Domain logic, feature wiring |
| `com.sashkomusic.events` | Spring Application Event records (one per former Kafka topic + `TrackAnalysisCompleteEvent`) | Logic — events are pure data |
| `com.sashkomusic.agents` | LLM agents (`main`, `discovery`) + deterministic agent services (`download`, `library`). Shared `contract` records + `bridge` accumulator. | Telegram I/O outside `bridge`; direct DB access |
| `com.sashkomusic.mainagent` | Telegram bot entry, slash commands, FlowServices (search / download / library / streaming / process — orchestration only), session state, AI extractors that fit mainagent's concerns (`SearchRequestExtractor`, `DownloadBatchAnalyzer`). The `process` sub-package keeps **only** the Telegram-facing orchestration for `/process` and `/reprocess` (Flow / ContextHolder / OngoingFlow / messaging). All filesystem inspection + `.release-metadata.json` IO + folder-name LLM extraction belongs in libraryagent. | Direct DB access, file system ops |
| `com.sashkomusic.downloadagent` | Soulseek/Bandcamp/Qobuz/Apple Music/YouTube Music download coordination, MusicSourcePort implementations | Telegram logic, user session state |
| `com.sashkomusic.libraryagent` | File system watching, audio metadata extraction, DB persistence (Track/Release/Artist/Tag), tag-change detection, audio-analyzer REST bridge, `.release-metadata.json` read/write (Reader+Writer live together), folder-name LLM extraction (`FolderNameParser`), filesystem-based release identification (`ReleaseIdentifierService`) | Download coordination, Telegram |
| `com.sashkomusic.api` | Read-only REST API over the music DB — `TrackController`, `TrackService`, DTOs, exceptions. Reads from libraryagent repositories. | Write operations, agent logic, Telegram I/O |
| `sm-audio-analyzer` | Python / Essentia: audio feature extraction (BPM, MFCC, danceability, loudness). HTTP in, HTTP callback out | Any Java/Spring concerns |

## Agent Map

```
com.sashkomusic.agents
├── contract           ← sealed AgentRequest / AgentResponse records (A2A-ready surface)
├── config             ← Sonnet + Haiku ChatModel beans, memory providers, trace listener
├── bridge             ← ChatResponseAccumulator — per-chatId BotResponse buffer drained by TelegramChatBot
├── main               ← MainAgent @AiService (Sonnet) + MainAgentTools (delegates to sub-agents)             — see spec.md
├── discovery          ← DiscoveryAgent @AiService (Haiku) + DiscoveryAgentTools (search engines + history)  — see spec.md
├── download           ← DownloadAgentService — deterministic, wraps MusicDownloadFlowService                 — see spec.md
└── library            ← LibraryAgent @AiService (Haiku) + LibraryAgentTools (search/move/trash/DJ tagging)  — see spec.md
```

### Spec-Driven Development
Each agent package contains a `spec.md` describing **purpose, inputs, outputs,
tools, hard rules, out-of-scope, and SDD checkpoints**. For any change to an
agent:
1. Update the agent's `spec.md` first.
2. Then change code so it matches the updated spec.
3. Then update tests (or write new ones) to enforce the new spec.

**Non-negotiable:** every code change inside `com.sashkomusic.agents` MUST include
a corresponding `spec.md` update in the same edit session — no exceptions.
This applies to: adding/removing/renaming tools, changing tool parameters or behavior,
changing which path (LLM vs direct) a service takes, changing contracts (`*Request`/`*Result`).
If you changed agent code without updating the spec, fix the spec before doing anything else.

`spec.md` is the source of truth for agent contracts — code reviews and tests
must match it. If you find a code-vs-spec divergence, fix the spec OR the code
in the same change.

Decision split:
- Free-text NL → `MainAgent.chat()` (LLM picks one tool) → optionally delegates to `DiscoveryAgent` (LLM) or to `DownloadAgentService` / `LibraryAgentService` (no LLM).
- Button callbacks (`DL:`, `RATE:`, `STREAM:`, `PAGE:`, …) are routed by `mainagent.bot.CallbackDispatcher` (prefix → handler map) directly to FlowServices.
- Slash commands (`/np`, `/process`, `/reprocess`) and the `стоп` keyword bypass the LLM and go straight to FlowServices via `UserInteractionOrchestrator`. No web app handler — no `/app` command, no `telegram.webapp.url`.
- Only `MainAgent` talks to the user — sub-agents return records / push `BotResponse` into `ChatResponseAccumulator`.

## Telegram Entry Layer

```
TelegramChatBot.consume
  ├─ text → UserInteractionOrchestrator.handleUserRequest
  │         ├─ for each OngoingFlow → if appliesTo → handle (multi-turn dialogue)
  │         ├─ slash command / "стоп"
  │         └─ runMainAgent → MainAgent.chat → tools → accumulator drain
  └─ callback → UserInteractionOrchestrator.handleCallback → CallbackDispatcher.dispatch
```

`CallbackDispatcher` owns a `LinkedHashMap<String prefix, CallbackHandler>` — add new callback types by registering a single entry, never by touching the orchestrator.

`OngoingFlow` SPI (`mainagent.bot.OngoingFlow`) — same idea for text-based multi-turn dialogue (e.g. "type your comment now"). Each flow that holds pending per-chat state implements:
```java
public interface OngoingFlow {
    boolean appliesTo(long chatId);
    List<BotResponse> handle(long chatId, String input);
}
```
Orchestrator injects `List<OngoingFlow>` and iterates. Add a new ongoing flow by writing a `@Component` impl — zero edits in orchestrator. Existing impls: `CommentInputOngoingFlow` (DjTag comment), `ProcessFolderSelectionOngoingFlow`.

## Conversation State Persistence

Per-chat state survives JVM restart via Postgres — **not Redis** (existing infra wins; swap to Redis later behind the same interface if multi-instance becomes needed).

```
chat_state (chat_id BIGINT, flow_key TEXT, payload JSONB, updated_at TIMESTAMPTZ, PRIMARY KEY(chat_id, flow_key))
```

Use through `com.sashkomusic.mainagent.bot.state.ChatStateStore`:
```java
<T> Optional<T> get(long chatId, String flowKey, Class<T> type);
       void     put(long chatId, String flowKey, Object payload);
       void     remove(long chatId, String flowKey);
       int      clearAll(String flowKey);
```

Production impl: `JpaChatStateStore` (Postgres + Jackson). Test impl: `InMemoryChatStateStore` (no DB).

**ContextHolder pattern** — every per-chat state holder routes through `ChatStateStore` with one stable `flow_key`:
- `dj_tag` → `DjTagContextHolder` (track + waitingForComment flag)
- `search` → `SearchContextService` (SearchContext + List\<ReleaseMetadata\> per conversation)
- `dl_ctx` → `DownloadContextHolder` (chosenReleaseId + per-release OptionReport list)

When introducing a new holder, follow the pattern: declare a private `FLOW_KEY` constant, inject `ChatStateStore`, no in-process `Map<Long, T>`.

## Spring Event Map

Inter-package communication uses Spring Application Events (`ApplicationEventPublisher` → `@EventListener @Async`).

```
Publisher package        Event class                           Listener package
──────────────────────────────────────────────────────────────────────────────────
mainagent            →  FilesSearchTaskEvent               →  downloadagent
downloadagent        →  FileSearchResultEvent              →  mainagent
mainagent            →  FilesDownloadTaskEvent             →  downloadagent
downloadagent        →  DownloadCompleteEvent              →  mainagent
downloadagent        →  DownloadBatchCompleteEvent         →  mainagent
downloadagent        →  DownloadErrorEvent                 →  mainagent
mainagent            →  ProcessLibraryTaskEvent            →  libraryagent
libraryagent         →  LibraryProcessingCompleteEvent     →  mainagent
mainagent            →  ReprocessReleaseTaskEvent          →  libraryagent
libraryagent         →  ReprocessReleaseCompleteEvent      →  mainagent
mainagent            →  RemoveReleaseTaskEvent             →  libraryagent
libraryagent         →  RemoveReleaseCompleteEvent         →  mainagent
mainagent            →  MoveReleaseTaskEvent               →  libraryagent
libraryagent         →  MoveReleaseCompleteEvent           →  mainagent
mainagent            →  RateTrackTaskEvent                 →  libraryagent
mainagent            →  AddCommentTaskEvent                →  libraryagent
mainagent            →  SetEnergyTaskEvent                 →  libraryagent
mainagent            →  SetFunctionTaskEvent               →  libraryagent
libraryagent         →  TrackUpdateResultEvent             →  mainagent
libraryagent         →  TagChangesNotificationEvent        →  mainagent
mainagent (orch)     →  ChatContextClearedEvent            →  MainChatMemoryProvider
mainagent (orch)     →  ChatHardResetEvent                 →  FileIdCacheService, DownloadContextHolder, DjTagContextHolder, LastReleaseContextHolder, SmartlistCreationFlowService
```

`ChatContextClearedEvent` and `ChatHardResetEvent` are **synchronous** (no `@Async`) — they fire from `UserInteractionOrchestrator` on `/clearctx` (soft only) and `стоп` (both). The orchestrator's response is gated on listener completion. Each owner of per-conversation state subscribes only to the event it cares about — keeps cleanup wiring out of the orchestrator.

Audio analyzer REST bridge:
- Java → Python: `POST {AUDIO_ANALYZER_URL}/analyze` (WebClient, fire-and-forget)
- Python → Java: `POST /internal/audio-analysis-complete` → `TrackAnalysisCompleteEvent` → libraryagent listener

## Dependency Rules

All packages live in the same JVM — Spring beans may be injected freely within the monolith. The logical boundaries below are conceptual guidelines to keep the code organized.

```
LOGICAL boundaries (enforce via code review, not the compiler):
  mainagent orchestrates flows, delegates to downloadagent and libraryagent via events
  api package reads from libraryagent repositories directly (same JVM)
  downloadagent and libraryagent do not call each other
```

## Internal Layer Rules (per package)

```
FlowService  →  domain Service  →  Port / Repository
     ↓               ↓
  messaging      AI service (@AiService)
```

- `*FlowService` — orchestrates one user-facing workflow. Calls services + publishers. No DB access, no AI calls.
- `*Service` (domain) — owns one domain concern. No Telegram, no messaging plumbing.
- `*ContextHolder` — per-chat/session state for one workflow only. Singleton bean, persisted through `ChatStateStore` (one `FLOW_KEY` per holder). Survives JVM restart. Do NOT use in-process `Map<Long, T>` for any state that the user would notice after a restart.
- `*Producer` / `*Listener` — event transport only. No business decisions.
- `*Handler` (e.g. `DownloadFlowHandler`) — one strategy implementation. Injected via `Map<Engine, Handler>`.
- LangChain4j agent interface (`MainAgent`, `DiscoveryAgent`) — system prompt is a `String` constant in a sibling `*Prompts` class; tools are a sibling `*Tools` Spring component; built in a `*Config` via `AiServices.builder()`.
- Agent contracts (`AgentRequest` / `AgentResponse`) live in `agents.contract`. Sub-agents return their typed `*Result` record; transports may be swapped (in-process → A2A HTTP) without changing callers.

## Coding Practices

**Records for data, entities for persistence**
- Use Java records for DTOs, event payloads, domain value objects — immutable by default.
- `@Entity` classes are mutable (JPA requires it); keep them in `domain/entity/` only.

**Constructor injection, final fields**
- `@RequiredArgsConstructor` + `private final`. Never `@Autowired` on fields.

**Strategy maps over conditionals**
- `Map<DownloadEngine, DownloadFlowHandler>` injected by Spring. No `if/switch` on engine type in callers.
- Same pattern when adding new sources, intent handlers, metadata providers.

**One event class = one logical message type**
- Event classes live in `com.sashkomusic.events`.
- Never reuse an event class for structurally different payloads.

**`@Async` on all event listeners**
- All `@EventListener` methods must also be `@Async` to avoid blocking the publisher thread.
- Use the named executor `asyncExecutor` (defined in `AsyncConfig`).

**Idempotent listeners**
- Check state before acting. A re-delivered event must not cause duplicate side effects.

**`Optional` only at boundaries**
- Repository return values → `Optional<T>`. Never as method parameters or fields.

**No `@Transactional` without DB access**
- Annotate only methods that actually read/write via JPA. FlowServices are never transactional.

**Flyway: append-only**
- Never edit an applied migration. Always add a new versioned file.

**Don't introduce one-method delegator services**
- A `*Service` that exists only to call 2–3 other services is overhead. Inline into the caller (e.g. the `clearAllCaches` lesson — `UtilFlowService` was inlined into `UserInteractionOrchestrator`).

**Match prompt location to consumer**
- Single-purpose LangChain4j interfaces live next to their caller package, not in a shared `ai/` bag. `SearchRequestExtractor` lives under `agents.discovery`; `DownloadBatchAnalyzer` under `mainagent.download`; `FolderNameParser` under `mainagent.process`.

## Testing

Test infra: `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito) + WireMock. No Testcontainers / H2 — design tests so they do not need real PostgreSQL or LLM.

**Pure unit tests**
- Plain JUnit. Use for parsers, formatters, calculators (e.g. `LibraryCommandParserTest`).
- Prefer `@ParameterizedTest` + `@CsvSource` over many similar `@Test`s.

**Integration tests — `@SpringJUnitConfig` slice (preferred for FlowServices)**
- Use `@SpringJUnitConfig` instead of `@SpringBootTest` — does NOT trigger Spring Boot auto-configuration (no JPA, no Telegram client, no LangChain4j model creation).
- `@Import({SomeFlowService.class, SomeContextService.class, TestConfig.class})` lists the exact real beans you need.
- Inner `static class TestConfig` declares Mockito mocks via `@Bean`.
- Verify event publishing with `@RecordApplicationEvents` + `@Autowired ApplicationEvents events`; assert via `events.stream(SomeEvent.class)`.
- Resolve duplicate-bean conflicts (e.g. multiple `SearchEngineService` impls) with `@Qualifier("beanName")` on the autowired field.

Skeleton:
```java
@SpringJUnitConfig
@RecordApplicationEvents
@Import({TheFlowService.class, TheContextService.class, MyTest.TestConfig.class})
class MyTest {
    @Autowired TheFlowService sut;
    @Autowired ApplicationEvents events;

    @BeforeEach void reset() { /* clear holders */ }

    @Test void publishes_event_with_payload() { ... }

    @Configuration
    static class TestConfig {
        @Bean ExternalClient client() { return mock(ExternalClient.class); }
    }
}
```

**What lives where**
- `LibraryCommandParserTest` — pure unit example (regex parser, 23 cases).
- `SearchFlowIntegrationTest` — slice example (MusicBrainz → Discogs fallback, engine switch).
- `DownloadFlowIntegrationTest` — slice + event capture (`DL:` callback → `FilesSearchTaskEvent`).
- `LibraryFlowIntegrationTest` — slice + event capture + `InMemoryChatStateStore` (`/np` UI + `RATE:` → `RateTrackTaskEvent` + Navidrome call).

**ChatStateStore in tests** — provide `new InMemoryChatStateStore()` as a `@Bean` instead of mocking. Round-trip serialisation matches the real impl, so test data behaves like prod data.

When adding a new flow test, mirror one of the above — do NOT introduce `@SpringBootTest` unless you specifically need the full container.

## Change Recipes

Типові зміни що зачіпають кілька модулів — виконуй у цьому порядку, оновлюй spec до коду.

### Нове джерело завантаження (новий `DownloadEngine`)
1. `mainagent/download/DownloadEngine.java` — додати enum value
2. `downloadagent/infrastructure/client/<source>/` — новий клас що імплементує `MusicSourcePort`
3. `downloadagent/config/MusicSourceConfig.java` — зареєструвати в map
4. `mainagent/download/<Source>DownloadFlowHandler.java` — новий `DownloadFlowHandler`
5. `mainagent/download/config/DownloadSourceConfig.java` — зареєструвати в map
6. Інші handlers (`QobuzDownloadFlowHandler`, `YouTubeMusicDownloadFlowHandler`) — додати кнопку в `buildSearchResultsResponse`
7. `agents/download/spec.md` — оновити таблицю джерел
8. `mainagent/download/spec.md` — оновити таблицю handlers і кнопки

### Нова slash-команда або OngoingFlow
1. `mainagent/bot/UserInteractionOrchestrator` — додати routing (тільки для slash-команд; OngoingFlow — без змін тут)
2. Новий `*FlowService` або `*OngoingFlow` — реалізація
3. Якщо є стан — новий `*ContextHolder` через `ChatStateStore` (flow_key константа, без `Map<Long, T>`)
4. Якщо публікує event — додати рядок у Spring Event Map в цьому файлі

### Новий callback-тип (`XYZ:`)
1. Новий `@Component` що імплементує `CallbackHandler`
2. `mainagent/bot/CallbackDispatcher` — зареєструвати prefix у `LinkedHashMap`
3. Якщо потрібен `DownloadFlowHandler` — додати метод в інтерфейс і всі 5 impl

### Новий tool у MainAgent
1. `agents/main/spec.md` — описати tool (умова, параметри, return, side-effect) **ДО коду**
2. `MainAgentTools` — додати `@Tool` метод
3. Якщо новий sub-agent — новий `*AgentService` + `agents/*/spec.md`

### Нова Spring подія між пакетами
1. `events/` — новий record-клас події
2. Spring Event Map вище — додати рядок
3. `@EventListener @Async` listener у пакеті-отримувачі
4. Publisher у пакеті-відправнику через `ApplicationEventPublisher`

---

## Skills Registry

Project skills live in `.claude/skills/<name>/SKILL.md`. When adding a new skill, add a row here.

| Skill | Invoke | Trigger | Purpose |
|---|---|---|---|
| `research-before-implement` | auto | User asks to implement / add / create something new | Forks a subagent that checks Maven Central for latest dep versions and searches for current implementation patterns. Returns a research report before any code is written. |
| `review` | `/review [file]` | Manual | Checks code changes against module placement, layer rules, event contracts, cohesion/coupling, and Java practices defined in this file. |

**When to add a new skill vs extend CLAUDE.md:**
- CLAUDE.md — facts, rules, maps that apply passively to every interaction (architecture, coding standards)
- Skill — a procedure or research step that loads only when triggered (scaffolding, review checklists, pre-implementation research)
