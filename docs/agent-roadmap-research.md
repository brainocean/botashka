# Research: a minimal local coding agent

**Scope.** This note is a roadmap for a small, local, single-user coding/tool-using agent in this repository—not a multi-tenant service, autonomous deployment system, or framework. “**Source-derived fact**” is tied to a cited source; “**Recommendation**” is an engineering choice for this v1.

## Starting point: the supplied tutorial

The [ZenoAGI tutorial](https://ai.zenoagi.com/agent/practice/build-your-own) is a useful learning sequence: it presents the core as a model/tool loop, then adds recoverable tool errors and a step cap (v1), a todo tool (v2), and context compression (v3). Its concrete coding-agent example confines file paths to a workspace, allowlists read-only shell commands, avoids a shell, truncates tool output, and feeds tool results back into the conversation. **[Source-derived facts]**

Its strongest contribution is pedagogical: implement the small loop before introducing a framework. However, it uses a Chat Completions-shaped example to maximize compatibility and treats the tool dispatcher as application code. Current provider guidance should remain the API/protocol authority: OpenAI’s [function-calling guide](https://platform.openai.com/docs/guides/function-calling) documents tool definitions, model tool calls, and sending tool outputs back; the tutorial itself notes that newer OpenAI projects should evaluate the Responses API. **[Source-derived facts]**

The tutorial’s later additions are valuable, but are not all day-one requirements. In particular, external planning, summaries, memory files, sub-agents, and MCP broaden state and failure modes. The MCP specification describes stateful JSON-RPC connections, capability negotiation, and multiple feature types—not merely a replacement for an in-process function dispatcher. **[Source-derived fact: MCP specification](https://modelcontextprotocol.io/specification/2025-06-18)**

## Minimum viable control loop

### What the loop must preserve

1. Construct a request from the system instruction, the user request, the allowed tool schemas, and the current turn history.
2. Ask the model for either a final response or zero or more tool calls.
3. For every requested call, validate and execute it in trusted host code; append a result associated with that call to the history.
4. Stop only on a final model response or a host-enforced terminal condition (step, time, cancellation, or approval refusal).

The first three steps match the model/tool interaction described by the [ZenoAGI tutorial](https://ai.zenoagi.com/agent/practice/build-your-own) and the [OpenAI function-calling guide](https://platform.openai.com/docs/guides/function-calling). **[Source-derived facts]**

**Recommendation — conservative loop.** Keep one explicit, synchronous loop owned by the CLI process. Return a structured terminal result (`:completed`, `:step-limit`, `:cancelled`, `:rejected`, or `:provider-error`) rather than hiding limits behind a generic final answer. Start with one tool call at a time even if a model can request several: this makes ordering, approval, and trace review unambiguous. Add concurrency only for independently safe, read-only tools once its trace semantics are designed.

## Tool contract and execution boundary

### Contract

A tool definition should have a stable name, short purpose, JSON-shaped input schema, explicit required fields, and documented result/error shape. The model proposes the tool and arguments; the host decides whether and how to invoke it. The ZenoAGI tutorial explicitly distinguishes model-generated tool requests from host-side execution and uses a dispatcher, bounded workspace paths, command allowlisting, timeouts, and output truncation. **[Source-derived facts](https://ai.zenoagi.com/agent/practice/build-your-own)**

The MCP [tools specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools) requires an `inputSchema`, permits an `outputSchema`, and says clients should validate results before passing them to a model; it also treats annotations as untrusted unless from a trusted server. **[Source-derived facts]**

**Recommendation — v1 tools.** Implement direct, typed host functions rather than shell access or MCP:

- `list-files` and `read-file`, rooted at one canonical workspace;
- optionally `search-text`, also rooted and output-bounded;
- no write, patch, arbitrary command, network, or process-spawning tool in the initial usable version.

Canonicalize paths before authorization; reject paths outside the root, directories where a file is required, oversized reads, invalid encodings as appropriate, unknown tool names, malformed arguments, and schema-invalid arguments. Use fixed byte/character output limits and a per-call deadline. Convert expected failures into concise structured results the model can use; preserve exception details only in the local trace. OpenAI documents `strict: true` for schema-constrained function arguments in its [function-calling guide](https://platform.openai.com/docs/guides/function-calling); **recommendation:** still perform host-side validation and authorization, because a valid schema instance is not proof that the requested operation is permitted.

## State, trace, and context

The tutorial retains the assistant tool-call message and corresponding tool result in history; it warns that compaction must not split the pairing boundary. **[Source-derived facts](https://ai.zenoagi.com/agent/practice/build-your-own)** The MCP specification additionally lists cancellation, progress tracking, error reporting, and logging as protocol utilities. **[Source-derived fact](https://modelcontextprotocol.io/specification/2025-06-18)**

**Recommendation — state model.** Maintain three separate local values:

- **Conversation state:** only the provider-compatible messages/tool-call records required for the next request.
- **Execution state:** step count, deadline, allowed workspace, approval decision, and cancellation flag.
- **Append-only trace:** request metadata, model response metadata, proposed calls, validation decisions, tool input digest/redacted input, result summary, elapsed time, and terminal status.

Keep the trace as local EDN or JSON Lines per run, with a run ID. Do not put secrets, full file contents, or unredacted credentials in it. Print a compact per-step trace to the terminal and retain the complete trace for debugging/evaluation. Start with a hard context-size budget and refuse/stop clearly rather than automatic summarization; add compaction only after tests establish that tool-call/result relationships and essential facts survive it.

## Validation and human approval

The tutorial recommends a unified dispatcher that turns malformed JSON, unknown tools, argument mismatches, and tool failures into feedback rather than crashing the loop, plus a hard step cap. **[Source-derived facts](https://ai.zenoagi.com/agent/practice/build-your-own)** The MCP specification requires explicit user consent before exposing user data to servers and before invoking tools, and recommends robust consent and authorization flows. **[Source-derived facts](https://modelcontextprotocol.io/specification/2025-06-18)**

**Recommendation — approval policy.** For read-only in-process workspace tools, obtain one visible session-level consent when the agent starts and display the workspace root and allowed capabilities. Require per-call approval before any future write, shell/process, network, credential, or MCP-server capability; show the exact operation and normalized arguments. A rejection must create a tool result stating that the host denied the call, not silently substitute another action. Treat model output and external tool metadata as untrusted input; neither can expand capabilities.

## Evaluation before optimization

The tutorial advises collecting a small task set with expected results and running it after prompt changes; it also recommends mocking the model to test the executor. **[Source-derived facts](https://ai.zenoagi.com/agent/practice/build-your-own)** OpenAI’s [evaluation guide](https://platform.openai.com/docs/guides/evals) presents evaluations as a way to measure system quality; its [function-calling guide](https://platform.openai.com/docs/guides/function-calling) is the provider reference for the calling interface. **[Source-derived facts]**

**Recommendation — two layers of checks.** First, deterministic tests for path confinement, argument validation, output bounds, timeout/limit handling, approval denial, and tool-result serialization. Second, a small fixed scenario corpus with recorded expected observable outcomes: answer a fact from a file, refuse an outside-root read, recover from a missing file, stop at a step limit, and decline an unapproved mutation. Score final answer correctness, tool-policy compliance, and terminal state separately. Keep model/provider/version, prompt revision, tool definitions, and trace ID with each run so regressions are attributable.

## Incremental learning and implementation roadmap

| Stage | Build and learn | Explicit non-goals / exit evidence |
| --- | --- | --- |
| 0. Provider spike | One model request with a static prompt; record raw response shape. Learn the selected provider’s current API from its official guide. | No tools. A documented request/response sample exists. |
| 1. Deterministic tool host | Workspace-rooted `list-files` and `read-file`; schema and path validation; unit-test the host without a model. | No shell, writes, network, MCP, memory, or planner. Invalid input cannot leave workspace. |
| 2. Agent loop | Attach schemas, preserve call/result history, execute bounded calls, surface final answer; add step/time/cancel limits. | Single sequential call only. Traces show each decision and terminal state. |
| 3. Usable local CLI | Session consent, clear approval UX, redacted JSONL/EDN traces, fixed scenario corpus. | Read-only capabilities only. Scenarios cover normal, denied, malformed, and bounded cases. |
| 4. Controlled mutation | Add a narrow edit/write tool only after per-call approval and rollback/preview behavior are designed and tested. | Never add arbitrary shell as a shortcut. |
| 5. Context and planning | Add a simple explicit task list only when real tasks demonstrate lost progress; add compaction only with pairing/invariant tests. | No autonomous sub-agents or persistent memory by default. |
| 6. Interoperability | Evaluate MCP only when a needed integration cannot reasonably be a local typed function; apply MCP consent/capability rules. | Do not expose broad roots or auto-trust server tool descriptions. |

**Recommendation — implementation order.** Complete stages 0–3 as the v1. They retain the tutorial’s valuable minimal-loop learning path while moving safety boundaries, traceability, consent, and evaluation ahead of convenience features. Stages 4–6 should be evidence-driven additions, each with a newly justified capability and accompanying deterministic plus scenario-level checks.

## Harness engineering: make the loop restartable and falsifiable

The reference repository treats a reliable coding-agent environment as five cooperating subsystems: **instructions, state, verification, scope, and session lifecycle**. Its concrete harness uses a short agent entrypoint, a machine-readable feature list with evidence, an initialization command, and a persisted session log. **[Source-derived facts: local reference `learn-harness-engineering/README.md`, `projects/project-01/solution/`]**

**Recommendation — two products, one repository.** Botashka has two distinct responsibilities which must not be conflated:

1. The **agent runtime** accepts a user task, mediates model/tool turns, enforces capability policy, and emits an execution trace.
2. The **repository harness** makes work on the runtime itself repeatable across human and coding-agent sessions.

The runtime is not yet a general-purpose coding harness. Conversely, repository files such as `feature_list.json` are not model memory. This split gives each module one interface and prevents session notes from becoming implicit runtime control state.

### Minimal repository harness

| Subsystem | Artifact | Interface/invariant | Initial acceptance evidence |
| --- | --- | --- | --- |
| Instructions | `AGENTS.md` plus focused `docs/` notes | Root file routes readers to product, architecture, and verification facts; it does not duplicate them. | A new session can identify the active feature, startup command, and definition of done without chat history. |
| Scope | `feature_list.json` | Exactly one feature is `in-progress`; every feature has an ID, dependencies, acceptance criteria, status, and evidence. Status values are `not-started`, `in-progress`, `blocked`, or `pass`. | The active feature's acceptance criteria map to runnable checks or an explicit smoke scenario. |
| State | `progress.md` | Append one handoff entry per work session: goal, completed work, decisions, verification evidence, blocker, and next action. It is historical evidence, never a second feature list. | A fresh session resumes the active feature without reopening completed work. |
| Verification | `bb` tasks or a documented command set | One command performs deterministic checks; a separate smoke command exercises the CLI's changed path. | A feature cannot be marked `pass` without recorded command output/result and smoke evidence. |
| Lifecycle | `init.sh` or `bb bootstrap` | Verifies prerequisites and baseline checks before mutation; failure leaves the workspace untouched. | A clean clone either reaches a known-ready state or reports the first failed prerequisite. |

The reference uses progressive disclosure: a short root instruction file points to focused architecture and product documents rather than becoming a monolithic prompt. **[Source-derived fact: local reference `projects/project-01/solution/AGENTS.md`; harness-creator `SKILL.md`]**

**Decision.** Add one short `AGENTS.md` when the first implementation slice begins. It SHOULD route agents to focused documents instead of duplicating them. Do not create agent-specific parallel instruction files unless distinct runtimes require different instructions; duplicated facts will drift. Add `feature_list.json`, `progress.md`, and a Babashka bootstrap/check task in the same slice.

### Runtime architecture: deep modules and seams

The following modules place volatile provider/process/file-system details behind seams. These are interfaces to design and test first, not necessarily namespaces to create on day one.

| Module | Interface owned by the module | Implementation hidden behind it | v1 adapter |
| --- | --- | --- | --- |
| `provider` | `complete(request)` returns either a completion or provider error | HTTP, authentication, provider response shape, retryable transport classification | One selected provider's tool-calling endpoint |
| `tool-registry` | `describe()`, `validate(call)`, `execute(call, execution-context)` | schemas, handler lookup, canonical paths, output caps, deadlines | `list-files`, `read-file` |
| `policy` | `decide(proposed-call, session)` returns `allow`, `ask`, or `deny` | capability classes, normalized-argument inspection, approval records | read-only calls allowed inside workspace; everything else denied |
| `conversation` | `append-model!`, `append-tool-result!`, `next-request()` | provider message serialization and tool-call/result pairing | provider-specific message adapter |
| `run` | `run(task, run-options) -> terminal-result` | turn loop, step/deadline enforcement, cancellation, trace emission | synchronous CLI loop |
| `trace` | `append!(event)`, `close!(terminal-result)` | event IDs, timestamps, redaction, atomic append, storage format | per-run EDN or JSONL file |
| `harness-state` | feature and handoff file update conventions | repository progress representation | local JSON/Markdown files |

**Recommendation — keep `run` deep.** Its caller supplies only task text and explicit options; callers do not coordinate message order, tool IDs, permissions, limits, or trace persistence. Tests cross the same `run` interface with fake `provider` and `tool-registry` adapters. A second adapter, not speculative abstraction, justifies a public seam.

### Runtime state and event contracts

Use one run directory, for example `runs/<run-id>/`, with immutable facts separated from resumable runtime state:

| File/value | Writer | Purpose | Must not contain |
| --- | --- | --- | --- |
| `trace.jsonl` or `trace.edn` | `trace` module only | Append-only audit and debug record | API keys, raw credentials, unlimited file/tool output |
| `request.edn` | CLI at run start | Normalized task, workspace root, selected model, limits, runtime version | provider secret |
| `result.edn` | `run` module on terminal transition | One structured terminal result and trace reference | an invented success after a limit/error |
| in-memory conversation | `conversation` module only | Provider-compatible current context | repository project state |
| `feature_list.json` / `progress.md` | human or repository-workflow agent | Development progress of Botashka | live agent-loop state |

Every trace event SHOULD have `:run-id`, monotonic `:seq`, timestamp, event type, and a redacted payload. Initial event types: `:run-start`, `:model-request`, `:model-response`, `:tool-proposed`, `:tool-approved`, `:tool-denied`, `:tool-result`, `:limit-hit`, and `:run-finished`. The trace records host facts, not an assertion that a model's explanation is true.

The reference's safety pattern defaults unclassified tools to non-read-only and non-concurrent, and treats concurrency as a property of an individual call rather than merely its tool name. **[Source-derived fact: local reference `skills/harness-creator/references/tool-registry-pattern.md`]** For this roadmap, no batch or parallel dispatch exists before its ordering and approval semantics are explicitly tested.

### Bootstrap and capability progression

The reference separates bootstrap into dependency-ordered stages and treats user consent as the trust boundary before sensitive subsystems activate. **[Source-derived fact: local reference `skills/harness-creator/references/lifecycle-bootstrap-pattern.md`]** Adapt that idea without hooks or background work:

| Bootstrap stage | Host action | Enabled capability | Failure behavior |
| --- | --- | --- | --- |
| 1. Minimal context | Resolve workspace, load non-secret config, allocate run ID and trace | None | Stop before model request |
| 2. Read-only registry | Register and self-check file/list/search tools | Workspace reads only | Stop in safe mode |
| 3. Consent | Print normalized workspace and allowed capabilities; record user choice | Calls permitted by policy | End run as `:rejected` on refusal |
| 4. Provider | Load API credential only after explicit local configuration/consent; make request | Model completion | End as `:provider-error`, retain redacted trace |
| 5. Future mutation | Require a separate capability plus per-call approval | Narrow patch/write only | Do not fall back to shell |

No project-defined hooks, environment-loaded scripts, or external MCP servers run in phases 0–5. They enlarge the trusted computing base without advancing the first read-only proof.

### Expanded delivery roadmap

| Phase | Smallest deliverable | Harness addition | Runtime acceptance criteria | Exit / non-goals |
| --- | --- | --- | --- | --- |
| A. Product contract | `docs/PRODUCT.md`: local CLI, read-only workspace assistant, inputs/outputs, explicit non-goals | First `feature_list.json` containing all phases | Every later choice traces to a stated v1 requirement | No code, no provider choice hidden in prose |
| B. Baseline lifecycle | `bb bootstrap` or equivalent documented command | `progress.md`; baseline verification command; definition of done in `AGENTS.md` | A fresh clone reports prerequisites and baseline status deterministically | No LLM request yet |
| C. Provider probe | One non-tool model request, fixture of sanitized response shape | Record provider/model/config assumptions as a feature evidence entry | Success, malformed response, auth error, and timeout produce distinct terminal values | No dispatcher, no retries beyond transport-safe policy |
| D. Deterministic tool host | Workspace-rooted `list-files` and `read-file` | Tool contract fixtures and hostile-input tests | Outside-root traversal, directories-as-files, missing files, oversized reads, bad arguments, and unknown tools are rejected without crashing | No model, shell, write, network, MCP |
| E. Single-turn mediation | Serialize schemas; decode one model tool request; validate, authorize, invoke, return one result | Trace schema and redaction rules | The assistant tool-call record and result retain the provider-required association; failures become structured results | One call only; no `while` loop |
| F. Bounded agent loop | Sequential multi-turn `run` with final-answer, step-limit, deadline, cancellation, and approval-refusal terminal states | Per-run trace directory and smoke command | Model can answer a file fact after one or more reads; every non-success terminal state is visible to caller and trace | No planner, summaries, automatic retries, or parallel calls |
| G. Scenario evaluation | Fixed local scenario corpus; fake provider adapter for deterministic loop tests | Feature evidence requires scenario IDs and observed result | Score answer correctness, policy compliance, trace integrity, and terminal state independently | No benchmark claims from a handful of demos |
| H. Controlled edits | A narrow `propose-patch` then approved `apply-patch` flow | Per-call approval record, diff preview, rollback procedure | Denial causes no mutation; approved patch changes only a normalized in-root target; post-write verification runs | No arbitrary command execution |
| I. Context and work planning | Explicit task list and bounded context selection | Context budget ledger and compaction invariant tests | Important tool-call/result pairs and fixed policy/instruction blocks survive compaction | No opaque "memory" or autonomous re-planning |
| J. External integration | One justified MCP or other connector adapter | Connector threat model, consent, and scenario coverage | External tool metadata cannot grant capabilities; failures remain attributable | No automatic server discovery or broad filesystem grants |
| K. Graphs and delegation | Explicit workflow graph only when one loop demonstrably cannot express required routing | Ownership and shared-state protocol | Each node has inputs, outputs, terminal states, verification, and one owner | No multi-agent fan-out as a performance ornament |

### Evaluation ladder and gates

The reference separates feature status from evidence and requires runnable verification before completion. **[Source-derived fact: local reference `feature_list.json`, `AGENTS.md`, and `init.sh`]** Apply that discipline to agent behavior:

| Gate | Evidence | Failure that it detects |
| --- | --- | --- |
| Pure tool checks | Deterministic assertions around paths, schemas, output bounds, and errors | A host tool violates its own contract |
| Protocol fixtures | Captured/synthetic provider responses for plain answer, tool call, malformed call, and multiple calls | Conversation serialization or call/result pairing error |
| Loop tests | Fake provider emits a scripted sequence; fake clock/cancellation adapter controls limits | Incorrect terminal transition, hidden retry, missed limit |
| CLI smoke | Real provider against a fixture workspace, manually inspect final answer and trace | Credentials/configuration and real protocol mismatch |
| Scenario corpus | Fixed tasks with expected answer, allowed calls, forbidden calls, and expected terminal state | Prompt/policy regression masked by plausible prose |
| Mutation drill, later | Approval deny/allow, diff, failed verification, rollback | Write capability escaping its preview/approval policy |

An agent feature is `pass` only when its deterministic gate and its applicable smoke/scenario gate have evidence. A provider call that merely returns text is not evidence that the tool loop is correct.

### Context and session policy

The reference frames context as a budget with four operations—select, write, compress, and isolate—and recommends loading metadata first, instructions when activated, and resources on demand. **[Source-derived fact: local reference `skills/harness-creator/references/context-engineering-pattern.md`]**

For the first loop, select only: system instruction, user task, tool schemas, and correctly paired turn history, each with explicit size caps. Write durable development knowledge to harness files, not the model conversation. Do not compact automatically until tests prove the following invariants:

1. No tool result survives while its corresponding assistant tool-call record is removed.
2. The current workspace root, capability policy, user task, and terminal limits remain present.
3. A compaction event identifies the source turn range and summary version.
4. The original uncompressed trace remains available locally.

Delegation, background tasks, and multi-agent state are post-v1 work. Add a graph only after a documented task requires routing, separate verification, or recovery that cannot remain comprehensible in one loop.

### First implementation backlog

The first active feature SHOULD be `provider-probe`, followed strictly by `read-only-tool-host` and `bounded-read-agent`. Each feature needs dependencies and acceptance evidence before implementation starts:

| Feature ID | Depends on | Done when |
| --- | --- | --- |
| `provider-probe` | A, B | A sanitized real request/response sample and typed terminal error mapping are recorded. |
| `read-only-tool-host` | B | Both tools pass hostile-input tests and cannot access beyond canonical workspace root. |
| `bounded-read-agent` | `provider-probe`, `read-only-tool-host` | A real CLI run reads a fixture file, answers correctly, produces a redacted trace, and terminates correctly under step/deadline limits. |
| `scenario-evals` | `bounded-read-agent` | Fixed scenarios pass with a fake provider; one real-provider smoke run is recorded separately. |

Only then choose whether controlled edits are worth the additional policy, preview, rollback, and verification complexity.

## Source index

- ZenoAGI, “[Build a Minimal Agent from Scratch](https://ai.zenoagi.com/agent/practice/build-your-own)” — supplied tutorial and its v0–v3 progression.
- OpenAI, “[Function calling](https://platform.openai.com/docs/guides/function-calling)” — official provider tool-calling reference.
- OpenAI, “[Evals](https://platform.openai.com/docs/guides/evals)” — official evaluation guidance.
- Model Context Protocol, “[Specification, 2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18)” — protocol, capabilities, consent, and tool-safety requirements.
- Model Context Protocol, “[Tools, 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)” — official tool schemas, result validation, and trust guidance.
- Local reference, `learn-harness-engineering/README.md` — five-subsystem harness model and progressive learning path.
- Local reference, `learn-harness-engineering/projects/project-01/solution/` — concrete `AGENTS.md`, `feature_list.json`, `init.sh`, and session-log patterns.
- Local reference, `learn-harness-engineering/skills/harness-creator/` — tool safety, lifecycle/bootstrap, and context-budget patterns.
