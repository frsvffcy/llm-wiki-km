# open-doc Agent Document Workflow Evaluation

> Classification: `TRACK_FULL`  
> Evaluated: 2026-09-21  
> External repository: `simonliu-ai-product/open-doc`  
> Audited revision: `68d5d063f332161efa71e907dcfa3e11f59dbb2e`  
> License: MIT  
> llm-wiki-km baseline at audit: `7b9bdbb31df42ba558a179233a99d548748ae3fe`  
> Tracking: Refs #585, actionable follow-up Refs #586

## 1. Evaluation question

This evaluation does **not** ask whether `llm-wiki-km` should adopt open-doc's React/TSX document runtime, PDF renderer, or page-layout stack.

The useful question is:

> Which open-doc patterns around agent context, mutation safety, human review, rendered-output verification, skills governance, and MCP can improve `llm-wiki-km` without creating a second authority, second retrieval pipeline, or speculative product surface?

The external project is especially relevant because it treats an agent as a first-class operator over a user-visible document workspace and therefore has to solve several coordination problems that also appear in a local-first AI knowledge system:

- what document the user currently means;
- how an agent avoids overwriting concurrent human work;
- how a human leaves precise feedback for an agent;
- how the agent verifies output it cannot reliably infer from source alone;
- how Browser and MCP share one implementation rather than diverge;
- how stable repo instructions are separated from reusable task procedures.

## 2. Sources audited

The evaluation used primary repository evidence, not only the README.

Key external sources:

- `README.md`
- `AGENTS.md`
- `packages/core/skills/current-doc/SKILL.md`
- `packages/core/skills/create-doc/SKILL.md`
- `packages/core/skills/apply-comments/SKILL.md`
- `.agents/skills/doc-runtime-patterns/SKILL.md`
- `.agents/skills/print-layout-review/SKILL.md`
- `.agents/skills/viewer-ui-guidelines/SKILL.md`
- `packages/core/src/ops/documents.ts`
- `packages/core/src/editing/comments.ts`
- `packages/core/src/app/lib/agent-bridge.ts`
- `packages/core/src/import/markdown.ts`
- `packages/core/src/cli/import.ts`
- `packages/mcp/README.md`
- `packages/mcp/src/tools.ts`

Current `llm-wiki-km` evidence additionally checked:

- `src/main/resources/static/inbox-ui.js`
- `src/main/resources/static/ask-ui.js`
- `src/main/resources/static/index.html`
- `src/main/java/org/km/llmwiki/ai/ask/AskApiRequest.java`
- `src/main/java/org/km/llmwiki/ai/ask/AskRequest.java`
- `src/main/java/org/km/llmwiki/ai/ask/AskApplicationService.java`
- `src/main/java/org/km/llmwiki/rag/RetrievalRequest.java`
- `src/main/java/org/km/llmwiki/search/SearchController.java`
- current MCP capability / parity contracts
- `AGENTS.md`
- `docs/evaluations/README.md`

## 3. Executive decision

| Pattern | Decision | Current owner / trigger |
| --- | --- | --- |
| Preserve current document context across handoff | **ADOPT** | #586 |
| Read-before-write + expected-value conflict check | **CURRENTLY COVERED**; retain as future MCP-write input | Proposal/Draft/Publish + currentness/revision |
| Surgical edit rather than blind whole-file rewrite | **CURRENTLY COVERED in governance spirit** | repair Proposal/Draft flow |
| Real rendered-output verification | **ADOPT principle / DEFER automation** | revisit after repeated Browser visual regressions |
| Source material first; never invent figures/citations | **CURRENTLY COVERED** | Evidence/Citation/insufficient-evidence governance |
| Inline anchored human comments for agent revision | **DEFER** | revisit if granular review friction becomes repeated |
| Shared Browser/MCP application operations | **CURRENTLY COVERED** | existing application-facing services |
| AGENTS vs task-specific Skills separation | **ADOPT principle / DEFER structural split** | revisit on instruction drift / repeated procedural duplication |
| Import adapter converges to one normal downstream representation | **CURRENTLY COVERED** | ingestion → normalization → retrieval/evidence pipeline |
| open-doc MCP write surface | **NO-GO NOW** | current MCP remains read-only |

The only finding promoted immediately to a new product Story is **#586**.

## 4. Current-context handoff

### 4.1 What open-doc does

open-doc's `current-doc` skill treats the user's current viewer state as an **ephemeral live cursor**, not as conversation memory.

The important contract is:

```text
user says "this document / this page / this element"
→ re-read current state now
→ verify freshness
→ use current document/page/selection
→ if stale or missing, do not guess
```

The skill explicitly warns against reusing a previous turn's context because the user may have navigated between turns.

Its current-state record contains document identity, rendered page index/number, title, source path, optional selected element, and an `updatedAt` timestamp. The source coordinates are interaction handles only; they do not become domain authority.

This is a strong design distinction:

```text
interaction context
≠ durable memory
≠ canonical identity authority
```

### 4.2 Current llm-wiki-km gap

The current Inbox UI renders a document-specific CTA for a READY_TO_USE row:

```javascript
use.href = "#/ask";
use.textContent = "開始提問";
```

The button is visually attached to one concrete source document, but it sends no `documentId` or source scope.

At the Ask boundary, `AskApiRequest` currently accepts only:

```text
question
retrievalMode
```

Therefore:

```text
Document A
→ click "開始提問"
→ Ask
→ document A identity is lost
```

This is not a retrieval bug. General Ask still behaves according to its selected retrieval mode. It is a **context-preserving UX gap**: the UI suggests continuity from one specific document, but application state does not preserve that continuity.

### 4.3 Decision

**ADOPT**, owned by #586.

The correct target is not a persistent "current document memory." It is a bounded request/navigation scope:

```text
READY_TO_USE source
→ explicit "ask this document" scope
→ backend revalidates workspace/document/currentness
→ retrieval/evidence constrained to that scope
→ UI visibly shows the scope
→ user may clear it and return to whole-knowledge-base Ask
```

The scope must not be implemented as hidden prompt text or a ranking hint. If the UI says "這份文件", the evidence boundary must actually enforce that claim.

## 5. Read-before-write and stale-write refusal

### 5.1 External pattern

open-doc's `write_document` and `write_text` support an `expected` value representing what the caller last read.

The flow is:

```text
read current content
→ compute edit
→ write(expected = last-read content)
→ disk changed?
   yes → 409 conflict
   no  → apply
```

The agent is expected to re-read and reconcile after a conflict.

This prevents two agents, or a human and an agent, from silently clobbering one another.

For small edits, open-doc also prefers `read_text → write_text` over replacing the entire file. That narrows the mutation surface and reduces accidental collateral changes.

### 5.2 Mapping to llm-wiki-km

The exact implementation should **not** be copied because `llm-wiki-km` has a different governance model.

Persistent knowledge already goes through stronger boundaries:

```text
intent / finding
→ currentness + eligibility revalidation
→ Proposal
→ Draft
→ Human Review
→ explicit Publish
```

Repair also requires consumption-window revalidation rather than trusting a stale finding as authorization.

Therefore the underlying safety principle is already present:

> A previous read or diagnostic result is not permanent write authority.

### 5.3 Decision

**CURRENTLY COVERED**.

Keep open-doc as future design evidence if MCP write is ever reconsidered. At that time, minimum expectations should include:

- application-service mutation only;
- expected revision/hash or equivalent optimistic concurrency;
- typed stale/conflict result;
- re-read + reconcile;
- smallest useful mutation capability;
- destructive/idempotent annotations as hints only;
- existing Proposal/Review/Publish governance preserved where canonical knowledge is affected.

This evaluation does **not** unlock MCP write.

## 6. Rendered-output verification

### 6.1 External pattern

open-doc states a practical limitation clearly:

> an agent that wrote the source cannot assume the rendered document is visually correct.

It therefore provides:

- `open-doc check` / MCP `check_layout`;
- true-size browser rendering;
- diagnostics for clipped content, blank pages, stranded headings, unreadable type, and failed images;
- source locations on findings;
- `render_page` screenshots when a visual inspection is necessary;
- non-zero exit for layout errors.

The important idea is not PDF layout itself. It is:

```text
source-level correctness
≠ user-visible rendered correctness
```

### 6.2 Current llm-wiki-km position

The project already uses multiple verification layers:

- Java contract/integration tests;
- Browser JS tests;
- packaged-JAR acceptance;
- explicit human Browser checkpoints for high-value UX flows;
- narrow-viewport/focus/CSP requirements in current Product UX work.

However, the repository does not currently have a general Playwright/Chromium browser-render harness.

### 6.3 Decision

**ADOPT the verification principle; DEFER generic visual automation.**

There is not yet enough repeated evidence to justify adding a browser runtime/dependency merely because another project has one.

Revisit if any of these become repeated:

- Browser JS tests stay green while packaged UI is visibly broken;
- #571 navigation changes repeatedly regress narrow viewport/focus/layout;
- human checkpoints repeatedly catch visual defects that deterministic browser rendering could detect;
- screenshots/rendered state become required completion evidence for multiple UI Stories.

At that point the correct question is not "should we add Playwright?" but:

> What is the minimum real-browser check that distinguishes the repeated failure class?

## 7. Source-first authoring and evidence honesty

open-doc's `create-doc` workflow gathers **substance before layout**:

- topic/purpose;
- audience;
- source material.

It explicitly prohibits inventing figures, quotes, citations, or customer names. Missing information should be represented as a placeholder/TODO rather than fabricated content.

This aligns with existing `llm-wiki-km` rules:

- provider output is not canonical authority;
- citations must reference admitted Evidence;
- insufficient evidence is a valid result;
- synthetic evidence has explicit authority limits;
- external evaluation claims do not become product facts without project-specific evidence.

**Decision: CURRENTLY COVERED / reinforcement.**

No new Issue is needed.

## 8. Anchored human comments for agent revision

### 8.1 External pattern

open-doc's Inspect mode can anchor a human note to a concrete source element. The note is stored as a structured marker, and `apply-comments`:

1. finds pending markers;
2. reads surrounding context;
3. processes comments oldest-first;
4. applies the smallest reasonable interpretation;
5. leaves ambiguous/unsupported comments unresolved;
6. removes only comments actually applied;
7. verifies the result.

This is a strong Human→Agent collaboration pattern because the human does not need to describe the target again in chat.

### 8.2 Potential use in llm-wiki-km

Possible future applications include:

- comment on one paragraph of a Wiki Draft;
- point at a specific diff block during review;
- attach a human instruction to a Quality finding;
- request a bounded revision without approving or rejecting the entire artifact.

### 8.3 Decision

**DEFER.**

The current Product UX priority is to make the overall Proposal → Draft → Review → Publish path understandable (#570). A paragraph-annotation subsystem would add identity, persistence, lifecycle, and stale-target concerns before there is evidence it is needed.

Revisit only if human dogfood repeatedly shows:

```text
"approve / reject is too coarse"
or
"I can see what is wrong, but I cannot tell the system which exact part to change"
```

If triggered, comments should remain review intent, not mutation authorization.

## 9. Shared Browser/MCP operations

open-doc places document operations in a shared `src/ops/` layer. Browser routes and MCP tools are thin transports over the same functions.

That rule prevents:

```text
Browser semantics
≠ MCP semantics
```

The same validation/conflict logic runs regardless of caller.

`llm-wiki-km` already follows the equivalent architecture:

- MCP Ask and REST Ask reuse the application-facing Ask boundary;
- Search/Inspector/Locator/Ask do not have a second MCP implementation;
- Browser is not domain authority;
- MCP adapters do not directly become SQLite/FS/Graph/provider authorities.

**Decision: CURRENTLY COVERED.**

## 10. AGENTS vs task Skills

### 10.1 External pattern

open-doc has a clear separation:

```text
AGENTS.md
→ stable framework/repository invariants

.agents/skills/
→ specialised procedures for working on the framework

packages/core/skills/
→ task workflows shipped to users/agents
```

It also has a canonical-copy rule: shipped skill sources live in one place and template copies are generated/synchronised rather than edited independently.

This reduces two common forms of drift:

1. the root agent guide becoming a giant task cookbook;
2. Claude/Codex/template copies of the same workflow diverging.

### 10.2 Current llm-wiki-km position

At audit time:

- `AGENTS.md` is roughly 36k characters;
- there are no project-local `.agents/skills`, `.claude/skills`, or equivalent repo-local task skills;
- `docs/evaluations/README.md` already says AGENTS should contain stable operational invariants rather than research details.

The length alone is **not** sufficient evidence that refactoring is needed.

### 10.3 Decision

**ADOPT as governance principle; DEFER structural split.**

Good future split shape, if triggered:

```text
AGENTS.md
→ invariant / authority / minimum gates / navigation

repo-local skill or playbook
→ repeatable specialised procedure
   e.g. Completion Audit
        external evaluation
        release acceptance
        MCP compatibility review
```

A split becomes justified only if there is evidence such as:

- the same procedural block is repeatedly copied into Issues/prompts;
- different AI clients interpret the same procedure inconsistently;
- task-specific rules keep growing in AGENTS;
- instruction drift causes real review/correctness failures;
- maintaining several hand-written copies becomes necessary.

If skills are introduced, there must be one canonical source. Do not create separate manually-maintained Claude/Codex/ECC variants.

## 11. Import convergence

open-doc's Markdown import does not create a separate "imported-document runtime." It converts Markdown into the same normal authored document representation consumed by outline, editing, rendering, and export.

The transferable architecture principle is:

```text
input adapter diversity
→ converge at one application representation
→ one downstream pipeline
```

`llm-wiki-km` already applies the equivalent rule:

```text
source format / parser
→ extraction + normalization
→ canonical source/chunks
→ shared retrieval/evidence/currentness
```

A PDF, Markdown, or other supported source should not create an independent retrieval/Ask pipeline.

**Decision: CURRENTLY COVERED.**

## 12. MCP write: useful evidence, not a trigger

open-doc exposes write-capable MCP tools and provides several safety ideas:

- explicit tool responsibilities;
- thin wrappers over shared application ops;
- read-before-write descriptions;
- stale-write conflicts;
- surgical text mutation;
- path safety;
- Host/Origin loopback protections.

These are useful future references.

They are **not** evidence that `llm-wiki-km` should add MCP write now.

The current project intentionally keeps MCP read-only because canonical mutation interacts with:

- Proposal/Review/Publish governance;
- action-risk and human authorization;
- workspace/currentness;
- provider/agent recursion;
- durable knowledge authority.

**Decision: NO-GO NOW.**

Revisit only from real product/agent workflow pain, not from feature parity.

## 13. What not to copy

Do not copy:

- React/TSX as canonical knowledge format;
- the PDF/page-layout engine;
- arbitrary disk-writing MCP tools;
- Browser selection as durable conversation memory;
- current-document context as citation/evidence authority;
- open-doc's write MCP simply because it works for document authoring;
- a full browser-render CI dependency without a repeated visual regression class;
- inline comments directly into Published Wiki canonical content;
- multiple manually-maintained skill copies;
- project architecture decisions that exist only to support open-doc's dual-runtime React/Vite model.

## 14. Adoption and revisit map

### ADOPT now

- #586: explicit Document → Ask context-preserving handoff.

### CURRENTLY COVERED

- stale/currentness revalidation;
- human-governed persistent mutation;
- source/evidence honesty;
- Browser/MCP shared application semantics;
- one downstream retrieval/evidence pipeline.

### DEFER with trigger

- general browser-render/visual automation;
- paragraph-level human comments;
- project-local task skills split;
- any MCP write surface.

### NO-GO now

- React document runtime;
- PDF layout subsystem;
- write-capable MCP;
- persistent "current document memory".

## 15. Completion impact

This evaluation itself changes no production runtime, schema, REST contract, MCP tool, CI default, or product authority.

The actionable lineage is:

```text
open-doc current-context design
+ current llm-wiki-km Inbox/Ask code evidence
→ #586
→ bounded document-scoped Ask Story
```

Everything else remains either reinforcement or trigger-gated input.

Refs #585 #586 #567 #570 #571.
