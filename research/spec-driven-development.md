# Spec-Driven Development (SDD) — best practices

**Date:** 2026-06-01
**Status:** not tried — обрані ідеї чекають на впровадження

---

## TL;DR

SDD перевертає звичну схему: **специфікація стає контрактом**, а код — згенерованим
артефактом що їй слугує. У 2026 це стандарт для роботи з AI-агентами — без чітких
spec'ів агент "vibe-code"-ить і дрейфує від інтенту. У нас вже є `spec.md` per agent,
але вони не повністю формалізовані. Найбільший прибуток: фіксований skeleton +
`[NEEDS CLARIFICATION]` маркери + drift detection в pre-commit.

---

## Sources

### Authoritative
- [GitHub spec-kit — Spec-Driven methodology](https://github.com/github/spec-kit/blob/main/spec-driven.md) — найдетальніший виклад "Constitutional Principles"
- [GitHub spec-kit repo](https://github.com/github/spec-kit) — toolkit + CLI
- [GitHub Blog: SDD with AI](https://github.blog/ai-and-ml/generative-ai/spec-driven-development-with-ai-get-started-with-a-new-open-source-toolkit/)
- [Microsoft Developer Blog: Diving Into SDD](https://developer.microsoft.com/blog/spec-driven-development-spec-kit)

### Conceptual / broader perspective
- [Augment Code: What Is SDD — Complete Guide](https://www.augmentcode.com/guides/what-is-spec-driven-development)
- [Towards Data Science: From Vibe Coding to SDD](https://towardsdatascience.com/from-vibe-coding-to-spec-driven-development/)
- [DeepLearning.AI Course: SDD with Coding Agents](https://www.deeplearning.ai/courses/spec-driven-development-with-coding-agents)

### Comparison / tooling
- [Augment Code: 6 Best SDD Tools in 2026](https://www.augmentcode.com/tools/best-spec-driven-development-tools)
- [Ran the Builder: I Tested Three SDD AI Tools](https://ranthebuilder.cloud/blog/i-tested-three-spec-driven-ai-tools-here-s-my-honest-take/)
- [Java Code Geeks: SDD Workflow Replacing "Prompt and Pray"](https://www.javacodegeeks.com/2026/03/spec-driven-developmentwith-ai-coding-agents-the-workflow-replacingprompt-and-pray.html)

### Academic / theoretical
- arXiv paper early 2026 — three levels of SDD rigor:
  - **Level 1 (Spec-First):** старт точка для більшості команд
  - **Level 2 (Drift detection):** додає автоматику
  - **Level 3 (Formally executable spec):** для regulated domains

---

## Notes — прожований контент

### Core principle: інверсія
> *"Specifications don't serve code — code serves specifications."*

У старій схемі spec → застаріває → код стає source of truth. У SDD:
spec лишається source of truth, код регенерується від нього. Це особливо
критично з AI-агентами, бо вони не мають інтуїції — їм потрібен явний контракт.

### Що ДОЛЖНО бути в spec
- WHAT users need + WHY (НЕ how)
- Testable, unambiguous requirements з measurable success criteria
- User stories з actual user needs
- Error handling scenarios
- Non-functional requirements (latency, retries)
- `[NEEDS CLARIFICATION: ...]` markers для всіх неясностей

### Що НЕ повинно бути в spec
- Tech stack ("використати Resilience4j")
- Code samples / algorithms — тільки structural contracts (record signatures OK)
- Premature architectural decisions
- "Nice to have" фічі без user traceability
- Future-proofing assumptions

### Constitutional Principles (з GitHub Spec Kit)
Це non-negotiable правила що "вшиваються" в проект:

| Article | Принцип | Як у нас |
|---------|---------|----------|
| I — Library-First | Фіча спочатку standalone, потім інтегрується | частково — у нас monolith з логічними boundaries |
| III — Test-First | Жодного коду до тестів. RED → GREEN → REFACTOR | НЕ робиться — тести пишемо після |
| VII — Simplicity | Max 3 проекти, інші — з justification | у нас 1 модуль + Python — OK |
| VIII — Anti-Abstraction | Користуватись фреймворком напряму, не обгортати | дотримуємось |
| IX — Integration-First | Real DBs, real services. **NO mocks** | НЕ дотримуємось — `InMemoryChatStateStore`, mocks |

**Важливо:** Article IX — спірне для нас. У `CLAUDE.md` написано "design tests so they do
not need real PostgreSQL or LLM". Це усвідомлене tradeoff: швидкість тестів vs прод-similarity.
SDD propaganda каже інакше. Варто перевірити чи не "burned by mocks" effect (mock тести
проходять, прод-міграція ламається).

### Workflow Spec → Plan → Tasks → Implement

```
1. /speckit.specify   → feature spec (5 хв)
2. /speckit.plan      → implementation plan (5 хв)
3. /speckit.tasks     → executable tasks з dependency ordering (5 хв)
4. /speckit.implement → код генерується тільки тут
```

Кожна фаза — markdown артефакт що feeds наступну. Дає AI structured context
замість ad-hoc prompts.

Наш еквівалент:
- Plan mode в Claude Code = /speckit.plan
- TaskCreate = /speckit.tasks (але без dependency markers `[P]` для паралелізму)
- Spec.md update = /speckit.specify

### Anti-patterns
1. **Premature implementation details:** spec що говорить про React/Redux до того
   як визначені user needs
2. **Plausible guesses:** припускати auth method без явного clarification
3. **Incomplete specs:** залишити `[NEEDS CLARIFICATION]` маркери в фінальному spec
4. **Over-engineering:** додавати складність що не trace-иться до requirement
5. **Manual propagation:** руками апдейтити код коли spec змінився
6. **Skipping contracts:** генерувати implementation до того як API/data models визначені
7. **Mocking в тестах** (контроверсійно — див. вище)

### Quality Gates (checkpoints)
- **Simplicity Gate:** перевірка ≤3 проекти, no future-proofing
- **Anti-Abstraction Gate:** direct framework usage, single model representation
- **Integration-First Gate:** contracts визначені, contract tests написані
- **Completeness Checklist:** no `[NEEDS CLARIFICATION]` markers, all requirements testable

---

## Ideas to test у цьому проекті

| # | Idea | Effort | Impact | Status |
|---|------|--------|--------|--------|
| 1 | Фіксований skeleton для всіх `spec.md` | 30хв | 🟢 high | not tried |
| 2 | `[NEEDS CLARIFICATION]` marker convention | 0хв | 🟢 high | not tried |
| 3 | Acceptance criteria секція в кожному spec | 1-2г | 🟡 medium | not tried |
| 4 | Constitution file (`sashkomusic/CONSTITUTION.md`) | 30хв | 🟢 high | not tried |
| 5 | Pre-commit drift check (agent code → spec.md) | 30хв | 🟢 very high | not tried |
| 6 | Test-first для нових `@Tool` | per-feature | 🟡 medium | not tried |
| 7 | Перейти на GitHub Spec Kit toolkit | велике | 🔴 low fit | rejected (greenfield-only) |

### Деталі по idea 1 — Spec skeleton
Кожен `spec.md` повинен мати **рівно** ці секції:

```markdown
# <Agent> — Spec
## Purpose                ← WHAT + WHY, одне речення
## Contract               ← Input/Output records (це OK — структурний контракт)
## Acceptance criteria    ← testable behaviors (GIVEN/WHEN/THEN)
## Flow                   ← діаграма потоку
## Hard rules             ← non-negotiable invariants
## Out of scope           ← what NOT to do
## Open questions         ← [NEEDS CLARIFICATION: ...]
## SDD checkpoints        ← триггери для зміни spec
```

### Деталі по idea 4 — Constitution
Зібрати cross-spec правила що зараз розкидані в `CLAUDE.md`:

```markdown
# Constitution
1. No LLM в deterministic services (download, library)
2. Only MainAgent talks to user
3. Every async event carries conversationId
4. Spec.md changes in same commit as agent code
5. ChatStateStore для будь-якого per-chat state (no in-memory Maps)
6. Records for data, entities for persistence
7. No @Transactional без DB access
8. Strategy maps over conditionals
```

### Деталі по idea 5 — Pre-commit drift check
Простий bash hook:
```bash
#!/bin/bash
# .git/hooks/pre-commit (or via husky / pre-commit framework)
agent_code_changed=$(git diff --cached --name-only | grep "agents/.*\.java")
agent_spec_changed=$(git diff --cached --name-only | grep "agents/.*spec.md")

if [ -n "$agent_code_changed" ] && [ -z "$agent_spec_changed" ]; then
    echo "❌ Agent code changed but spec.md not updated:"
    echo "$agent_code_changed"
    echo ""
    echo "Update relevant spec.md or commit with --no-verify if intentional."
    exit 1
fi
```

### Чому idea 7 не підходить
GitHub Spec Kit заточений під:
- Greenfield проекти (старт з нуля)
- Повну ре-генерацію коду від spec
- AI agent що інтегрується з Spec Kit CLI

У нас:
- Brownfield Java/Spring Boot з готовим кодом
- Не хочемо ре-генерувати все
- Власний workflow з Claude Code

Краще взяти **принципи** Spec Kit, а не toolkit.

---

## Open questions для подальшого дослідження

- [NEEDS CLARIFICATION: чи варто переходити на real-Postgres tests (Testcontainers)
  для дотримання Article IX, чи зберегти швидкість через `InMemoryChatStateStore`?]
- [NEEDS CLARIFICATION: чи робити TaskCreate з `[P]` markers для паралельних задач,
  як у Spec Kit?]
- Чи можна generate spec.md з коду (reverse SDD) як baseline для existing agents?

---

## Outcome

*(порожньо — нічого з ideas ще не впроваджено. Заповнити коли почнемо тестувати)*
