# Botashka implementation plan: from code tools to a usable local coding agent

## Purpose

Build a small, single-user Clojure/Babashka coding agent that works in one explicit workspace. It has a bounded model/tool loop, tools implemented as self-describing Babashka artifacts, inspectable durable run records, and an approval-gated mutation workflow.

This plan uses Pi as design evidence, not as an implementation target. The detailed comparison is in [`docs/pi-capability-analysis.md`](../docs/pi-capability-analysis.md). Pi demonstrates a coherent loop—session history → provider request → tool calls/results → next turn—and durable event records. Its TypeScript package topology, TUI, extension host, unrestricted OS-permission model, and QuickJS code mode are deliberately outside Botashka's initial scope.

## Current baseline

Already working:

- `botashka.agent/run!` runs a sequential OpenAI-compatible Chat Completions tool loop with an eight-turn default cap.
- `botashka.provider/complete!` isolates the current HTTP/provider protocol.
- `tools/read-file.clj` and `tools/list-files.clj` are standalone artifacts with an EDN stdin/stdout contract.
- Every code tool responds to `{:command :describe}` with its tool name, description, and JSON-shaped parameter schema.
- `botashka.tools/code-tool-registry` discovers `tools/*.clj`, asks each artifact for metadata, and invokes it through `botashka.tool-runtime` as a `bb` subprocess.
- The agent can recover from a missing file by listing the workspace and reading the discovered file.

Current gaps:

- Tool metadata is discovered, but the host does not validate its shape or validate call arguments before spawning a tool.
- A tool process has no host-enforced deadline, output-size limit, or structured distinction between invalid protocol output and a tool business error beyond the current basic decode/process failures.
- Runs exist only in memory; traces are not persisted incrementally and cannot be resumed or evaluated later.
- There is no user-facing CLI, cancellation, explicit provider-error terminal result, or deterministic fake-provider test seam.
- No mutation policy, preview/approval flow, patch application, or verification workflow exists.

## Product boundary

### v1 promise

Given a workspace and a prompt, Botashka can inspect that workspace through approved read-only code tools, explain what it found, and leave an append-only local run record that shows every host-observed decision and result.

### v1 invariants

1. A model proposes tool calls; the host validates, authorizes, executes, and records them.
2. Every tool receives one EDN input map on stdin and writes exactly one EDN result map on stdout. Diagnostics use stderr only.
3. Tool input contains an absolute canonical workspace root. Workspace tools reject paths outside it after canonicalization.
4. The model never gains arbitrary shell, filesystem, process, or network access merely by emitting text or adding tool metadata.
5. Every terminal run reports a truthful status: `:completed`, `:step-limit`, `:cancelled`, `:rejected`, `:provider-error`, or `:failed`.
6. A trace records host facts, never unlimited file contents, credentials, or a model claim presented as fact.
7. Mutation is serial and requires a preview plus explicit approval. Denial produces a tool result and changes nothing.

### Explicit non-goals

- Pi-compatible TUI, RPC server, plugin/extension host, package marketplace, or multi-provider catalog.
- Arbitrary shell execution as a fallback for missing tools.
- Model-authored in-process Clojure evaluation or `load-string`.
- MCP, OAuth, remote tool catalogs, session branching, automatic compaction, parallel tool dispatch, subagents, or workflow graphs.

## Target seams

Keep the current data-first shape. Add modules only where they hide real variation or effect complexity.

| Module | Interface | Owns | Initial adapter |
| --- | --- | --- | --- |
| `provider` | `complete(request) -> completion or provider failure` | HTTP protocol, credential lookup, timeout/error classification | Existing OpenAI-compatible endpoint |
| `tool-runtime` | `describe(program)`, `invoke(program, input, limits)` | subprocess startup, stdin/stdout EDN protocol, deadline, output limits, failure conversion | `bb` subprocess |
| `tool-registry` | `discover(tool-dir) -> registry`, `validate(call)`, `execute(call, context)` | metadata validation, duplicate names, argument/output validation, name lookup | `tools/*.clj` |
| `policy` | `decide(proposed-call, run) -> :allow/:ask/:deny` | capability class, workspace restriction, approval state | allow read-only; deny all else |
| `run` | `run(options) -> terminal-result` | conversation sequence, limits, terminal transition, trace facts | Existing synchronous loop, refactored behind this interface |
| `trace-store` | `start!`, `append!`, `finish!` | run ID, atomic append, redaction, result file | one local run directory per run |
| `cli` | command arguments/stdin -> terminal result and rendered output | workspace resolution, user intent, human confirmation, exit code | Babashka command |
| `mutation` | `propose`, `approve`, `apply`, `verify` | patch identity, preview, approved application, rollback rules | unified diff inside workspace |

`provider`, tool artifact scripts, and `tool-runtime` already exist. Do not introduce a lifecycle/component manager until at least two durable resources—such as trace storage and cancellation-aware process handling—have real startup and shutdown dependencies.

## Delivery sequence

### Phase 1 — Harden the code-tool protocol

**Problem.** A discovered tool currently supplies arbitrary metadata and can emit arbitrary-size stdout; the host only detects process failure or EDN parse failure.

**Build.**

1. Define stable `clojure.spec.alpha` specs for artifact description, invocation input, normal result, and host-generated error result.
2. Make `describe!` validate description fields: nonblank unique name, nonblank description, JSON-object parameter schema, and no unknown contract fields.
3. Make the registry report malformed artifacts and duplicate names as structured discovery failures; do not expose those tools to the model.
4. Extend `tool-runtime` with configurable subprocess timeout and bounded stdout/stderr capture. Convert timeout, nonzero exit, malformed EDN, and oversized output into distinct host errors.
5. Validate tool-call JSON arguments against each artifact's declared input contract before a process starts. Start with the schemas actually used by local tools; do not build a general JSON Schema engine without a concrete need.
6. Add optional artifact-declared output schema only after the two existing tools have a stable result shape; then validate output before returning it to the model.

**Acceptance evidence.** Deterministic checks prove malformed metadata, duplicate names, invalid arguments, outside-workspace paths, malformed output, timeout, and output overflow produce one structured tool result without crashing the run. Existing `read_file` and `list_files` remain discoverable and executable.

**Non-goal.** Generated source safety proof. A subprocess protects agent-process state, not the user's machine.

### Phase 2 — Make the agent loop a truthful run state machine

**Problem.** `agent/run!` mixes provider wire handling, tool dispatch, and terminal state; provider failures can be misread as malformed assistant messages.

**Build.**

1. Define data contracts for run options, normalized provider response, tool proposal, tool result, trace event, and terminal result.
2. Refactor the loop so a provider adapter returns either normalized completion data or a classified provider failure. The loop must end as `:provider-error`, not throw or invent a final answer.
3. Insert registry validation/execution and policy decision between a proposed call and a tool invocation.
4. Preserve the provider-required assistant tool-call message and tool result message pairing in conversation state.
5. Add a run deadline and cancellation signal in addition to the existing turn cap. Process cancellation must terminate the active tool process and produce `:cancelled` or deadline failure deterministically.
6. Keep calls sequential. One trace sequence gives unambiguous ordering and makes later approval behavior testable.
7. Add a fake provider adapter seam and deterministic tests for completion, unknown tool, bad JSON arguments, rejected call, tool failure, provider failure, step limit, deadline, and cancellation transitions.

**Acceptance evidence.** A fake provider script drives each terminal state without network access. A real provider smoke run reads `README.md` through discovered tools and returns its title with a complete in-memory trace.

**Non-goal.** Streaming response rendering or queued/steering messages. Pi has these because its interactive CLI needs them; Botashka needs a reliable terminal state machine first.

### Phase 3 — Persist inspectable runs and expose a CLI

**Problem.** A REPL-only, in-memory trace cannot be inspected after failure or used as evidence for behavior changes.

**Build.**

1. Create `runs/<run-id>/` for each CLI run, with `request.edn`, append-only `trace.edn` or JSONL, and final `result.edn`.
2. Append every trace event atomically when it occurs; write the terminal result once. Redact credentials and truncate large tool payloads before persistence.
3. Add a small Babashka CLI accepting a prompt, explicit workspace, model override, turn/deadline limits, and run directory. It prints tool activity and final status while retaining the canonical files.
4. Resolve and display the canonical workspace plus discovered read-only tool names before the first model request. Record visible session-level consent for this capability set.
5. Provide `--run <id-or-path>` inspection that renders a stored trace; resume is intentionally deferred.
6. Map terminal status to documented process exit codes.

**Acceptance evidence.** A CLI invocation creates one run directory during execution, including when the provider or a tool fails. The rendered trace agrees with persisted event order. An inspection invocation reads the stored run without making a provider request.

**Non-goal.** Pi-style JSONL session tree, named sessions, branch/fork/clone, automatic context compaction, or a full TUI.

### Phase 4 — Add a read-only coding-tool set and scenario evaluation

**Problem.** Two navigation tools can demonstrate the loop but cannot reliably support normal repository inspection.

**Build.**

1. Add `tools/search-text.clj` using a literal query and workspace-relative path. Return stable, bounded matches with paths, line ranges, and excerpts; do not expose arbitrary regex or shell syntax first.
2. Give each tool a compact operational contract: capability class, expected errors, maximum output, and an executable smoke input. Keep this metadata inside the artifact.
3. Build a fixed local scenario corpus with a fake provider plus expected terminal status, tool sequence, and observable answer constraints:
   - read known file title;
   - recover from a missing file;
   - reject outside-root traversal;
   - report malformed arguments;
   - stop at turn/deadline limit;
   - search a known literal and read the selected file.
4. Run the corpus in one documented command. Store only deterministic fixtures and fake-provider responses; real-provider smoke stays separate.

**Acceptance evidence.** The corpus detects a broken provider-message pairing, tool-policy violation, invalid trace order, or wrong terminal status. A real CLI smoke can answer a repository question with `list_files`, `search_text`, and `read_file`.

**Non-goal.** Benchmark claims, autonomous planning, or a large tool catalog. Tool search/discovery à la Pi code mode is useful only once catalog size makes prompt cost observable.

### Phase 5 — Controlled mutation through generated code tools

**Problem.** A coding agent becomes useful when it can change a repository, but direct generated source or unrestricted file writes hide intent and make rollback/verification weak.

**Build.**

1. Add `tools/propose-patch.clj`: consume an intended unified diff plus original file identity, validate that targets are normalized paths inside the workspace, and return a canonical preview. It must not mutate the workspace.
2. Add a host-held approval token tied to the run ID, canonical patch digest, workspace, and expiration. The model cannot create or alter it.
3. Add `tools/apply-patch.clj`, executable only with a still-valid token. It applies exactly the approved digest; reject stale content, changed target baseline, or paths outside the workspace.
4. Use policy `:ask` for every mutation. The CLI displays the complete diff, affected paths, and verification command before accepting `yes` or `no`.
5. After an accepted application, run only user-approved verification commands or a narrowly declared project check. Record command, exit code, bounded output, and final working-tree diff in trace evidence.
6. Permit a model to author a new Babashka tool source only as an untrusted proposed patch under this same workflow. Before use, its artifact must pass Phase 1 discovery validation and an explicit human review/approval. It receives only the OS permissions of its subprocess; therefore source review and a workspace/process policy remain required.

**Acceptance evidence.** Rejected mutation leaves the tree byte-for-byte unchanged. An approved fixture patch changes only its declared in-workspace files, rejects a baseline mismatch, and records a verification result. A human can review a proposed new tool's source, metadata, and smoke input before it enters `tools/`.

**Non-goal.** Arbitrary `bash`, arbitrary file overwrite, automatically committing/pushing, or unattended generated-tool execution.

### Phase 6 — Context control, then interoperability only when justified

**Problem.** Longer tasks will eventually exceed useful model context, and external capabilities may become necessary; both expand the state/trust surface.

**Build only after observed need.**

1. Add an explicit task list if traces show lost progress across multi-step tasks. It is run data, not hidden prompt text.
2. Add bounded context selection first: preserve system instruction, active task list, policy decisions, and complete tool-call/result pairs. Test these invariants before summarization.
3. Add compaction as a new append-only summary fact; retain original trace/history. Do not silently rewrite prior evidence.
4. Evaluate MCP only for a specific needed server. Require explicit project/user trust, a narrow configured capability set, connection lifecycle ownership, and policy/approval parity with native tools.
5. Add semantic tool retrieval only when the native/MCP catalog demonstrably exceeds the provider tool-budget. Then expose `search-tools` and `describe-tool` as host-controlled discovery, not arbitrary metadata injection.

**Acceptance evidence.** Context tests prove call/result pairing and policy facts survive selection/compaction. An MCP addition has one justified user task and deterministic connection/policy failure tests.

**Non-goal.** Pi code mode, session trees, generic extension loading, multi-agent delegation, parallel mutation, or a workflow engine. Each needs independent evidence that the sequential run cannot express the required behavior.

## First next slice

Implement **Phase 1: Harden the code-tool protocol**.

It is the correct next slice because the newly established direction—tools as self-contained Babashka artifacts—currently lacks the host-side contract enforcement required before adding more tools, persistence, CLI surface, or model-authored artifacts. It also creates the deterministic test seam needed for every later phase.

Order within the slice:

```text
artifact metadata specs
→ bounded describe/invoke subprocess runtime
→ discovery and duplicate/error handling
→ argument validation
→ deterministic hostile-input tests
→ real read/list tool smoke
```

## Pi decisions recorded

| Pi mechanism | Botashka decision | Reason |
| --- | --- | --- |
| Agent loop and provider boundary | Adopt now | Matches existing `agent`/`provider` split; improves explicit terminal/error transitions. |
| Append-only session/event data | Adopt now, linear per-run first | Preserves evidence without session-tree complexity. |
| Tool registry and discovery | Adopt now, local artifact protocol | Already aligned with `tools/*.clj`; metadata stays next to executable behavior. |
| Code mode | Defer | Its QuickJS sandbox, injected tools, dynamic discovery, and parallel aggregation solve later scale/context problems, not current correctness gaps. |
| Extensions | Do not copy as default | Pi extensions run with full process permissions; Botashka should use explicit code artifacts plus policy and review. |
| MCP | Defer | Native artifact protocol must stabilize before accepting remote lifecycle/auth/trust complexity. |
| TUI/RPC/multi-package platform | Do not copy for v1 | A CLI plus persisted trace yields more immediate leverage for a local Clojure tool. |
