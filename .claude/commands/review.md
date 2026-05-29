# Code Review

Review the changes in context (or the file/module given as $ARGUMENTS) against the standards in `CLAUDE.md`. Be direct. Flag issues with severity: **critical** / **warning** / **note**. Skip sections that don't apply.

---

## Checklist

### 1. Module placement
Is this code in the right module? Cross-reference the **Module Map** in `CLAUDE.md`.
- Does the class belong to this module's stated responsibility?
- Does it import from a forbidden cross-module package (`com.sashkomusic.<other-agent>.*`)?

### 2. Layer placement
Is the class in the right internal layer? Cross-reference **Internal Layer Rules** in `CLAUDE.md`.
- `FlowService` doing DB access or AI calls → **critical**
- `Consumer`/`Listener` making business decisions → **critical**
- `ContextHolder` used as a singleton with global state (not chat-keyed) → **critical**

### 3. Kafka contract
- New topic added: does it follow `noun-verb-noun` naming? Is it in the topic map?
- Consumer: is it idempotent (safe to re-deliver)?
- Is the DTO duplicated per module, not shared via import?

### 4. Cohesion
Does this class have one clear reason to exist?
- More than one unrelated concern in a single class → **warning**
- Method >30 lines mixing multiple abstraction levels → **warning**

### 5. Coupling
- New concrete dependency instead of interface → **warning**
- `if/switch` on engine/source/intent type instead of strategy map → **warning**

### 6. Java practices (from `CLAUDE.md`)
- DTO modelled as class with setters instead of record → **note**
- `@Autowired` field injection → **warning**
- `@Transactional` on a method with no JPA calls → **note**
- Edited existing Flyway migration → **critical**
- `Optional` used as method parameter or field → **note**
- Prompt string in a FlowService or Service instead of `@AiService` interface → **warning**