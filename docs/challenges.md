# Botashka — Design Challenges & Known Weaknesses

This document records the harshest honest critique of the current design, collected from adversarial design reviews. These are not solved problems — they are open challenges that must be confronted before or during implementation.

---

## Summary Verdict

This is a **brittle code-runner disguised as an agent.** It executes unsandboxed, LLM-authored code with superficial validation, no isolation, and no reproducibility guarantees. The "simplicity" choices — single `emit!`, EDN-over-CLI, append-only trace, manual `git commit` promotion — don't eliminate complexity; they defer it into failure handling, safety, and maintainability.

---

## 1. Fundamental Concept Weaknesses

- **CodeAct via SCI with unsandboxed eval** makes execution correctness and safety entirely dependent on LLM output. There is no principled boundary between "plan" and "execution."
- **"Append-only trace as single source of truth" is false.** The real state is the mutable filesystem and network modified by arbitrary tools. The trace cannot reconstruct world state — it records intent and stdout, not effects.
- **The subprocess boundary breaks the designed state flow.** `*result*` doesn't survive `bb -f`. Tasks cannot compose except via out-of-band I/O, directly contradicting the THINK/GENERATE/EXECUTE loop's implied dataflow model.
- **Single `emit!` over-centralizes heterogeneous concerns** — planning logs, tool output, UI events — into an untyped sink. This guarantees implicit contracts and tight coupling, not the clean transport seam the design claims.

---

## 2. False Economy in the "Simplicity" Claims

- **Regex validation is performative safety.** `re-find` for `s/def`/`s/fdef` plus `sci/eval-string` shifts complexity into post-hoc damage control when arbitrary code runs — it doesn't prevent damage.
- **Manual `git commit` as the promotion gate externalizes quality entirely.** No tests, no versioning, no deprecation path — and silent breakage of any existing plan that references a tool that changed.
- **Rewriting `bb.edn` on `plan-amend!` creates two conflicting "truths".** The append-only trace is immutable; `bb.edn` is mutable. The design claims the trace is authoritative but lets `bb.edn` diverge from it silently.
- **EDN-on-CLI for args avoids IPC complexity but reintroduces quoting limits, OS differences, and payload size constraints** that will fail non-pathologically with any realistic tool input — nested maps, strings with newlines, binary data.

---

## 3. The LLM Reliability Problem

- **Every critical path assumes well-formed EDN.** Planner output, THINK decisions, tool args, generated source — a single malformed token stalls the loop or corrupts the trace. There is no deterministic parse-error recovery path.
- **GENERATE "validation" runs the code as the validation.** A syntax-correct but incorrect or malicious tool passes by construction. Validation and execution are the same operation with different framing.
- **Failure handling is recursively LLM-driven.** `plan-amend!` on failure asks the same model that failed to generate a better plan. A bad model response yields failure loops with no deterministic escape.
- **Composability decisions require the LLM to recall tool capabilities** from a truncated, drifting `tool-index.edn`. Misclassification between `:reuse` and `:generate` on a stale index silently degrades every execution.

---

## 4. Security Theater

- **`re-find` for `s/def`/`s/fdef` is trivially satisfied** by prepending a dummy spec while embedding arbitrary destructive side effects. It provides near-zero real protection and creates false confidence.
- **`sci/eval-string` with no sandbox, no capability restrictions, and no timeout is equivalent to "execute arbitrary local code."** The design acknowledges this gap and does nothing about it.
- **`:confirm true` is meaningless** when the same agent that writes the code decides whether to set the flag. The human gate is optional and unenforced by anything in the system.
- **`git commit` as a human gate is not enforced by any part of the system.** Tools can be used before being committed. The "promotion" step is advisory, not mechanical.
- **nREPL V2 `println`→`:out` scoping doesn't contain behavior.** It changes where text goes. It cannot prevent exfiltration, filesystem writes, or destructive I/O.

---

## 5. FP Discipline Illusions

- **"`llm-context` is a pure function" is marketing.** It depends on mutable files (`tool-index.edn`, `tools/*.clj`, `bb.edn`) read at call time. Results are not referentially transparent and will silently change across calls in the same session.
- **"Append-only trace" is undermined by truncation and end-of-run persistence.** You cannot replay or audit causal chains from a trace that drops events at 8k/4k character limits and vanishes entirely on crash.
- **The tool library has no version pinning.** Replaying a session trace against a changed `tools/` directory is non-deterministic. The trace records what the agent *said* it would do, not what code actually ran.
- **`emit!` blurs effects and values.** Mixing planning logs, tool results, and UI events in one untyped sink erases exactly the effect/value boundary that FP discipline is supposed to enforce.

---

## 6. Scalability and Growth Assumptions

- **Truncation at 8k/4k guarantees loss of important earlier context.** Planning quality degrades as sessions grow. There is no mechanism to determine *which* prior events matter — the design just drops the oldest ones.
- **`tool-index.edn` becomes stale and noisy as the library grows.** Naming collisions, overlapping capabilities, and tool drift will degrade LLM selection quality in ways the design has no mechanism to detect or correct.
- **One `bb -f` subprocess per task precludes fine-grained timeouts, cooperative cancellation, or parallelism.** There is no quota or resource limit on any tool invocation.
- **The design has no model for long-running or background tasks.** Every tool is assumed to complete synchronously, return stdout, and exit. Any realistic agent use case — watching a build, polling an API, streaming results — is architecturally unaddressed.

---

## 7. Gap Between Spec and Reality

- **`:compose` requires reliable cross-task dataflow**, but `*result*` is subprocess-scoped and doesn't survive `bb -f`. Composition in practice reduces to filesystem passing — which the design never specifies.
- **"Args as EDN on command line" will intermittently fail** on nested maps containing strings with quotes, newlines, or binary content. The design treats this as a solved problem.
- **"Persist trace at session end" contradicts append-only durability.** A crash between the last event and the write call makes the "single source of truth" disappear entirely.
- **"Human-editable `bb.edn`"** is incompatible with an automated planner that rewrites it on every `plan-amend!`. After two re-plans, `bb.edn` will not be coherent for either humans or agents.
- **Tool promotion via `git commit` is temporally decoupled from execution.** A commit after planning silently changes runtime behavior; the trace never records the tool version (commit SHA) that was actually used.

---

## 8. Competitive Reality Check

- **No typed tool interfaces or schema-validated arguments.** Serious alternatives treat JSON Schema-validated function calling as table stakes. This design reinvents an inferior version with looser contracts.
- **No sandboxing, capability scoping, timeouts, or resource limits.** Mature systems provide subprocess isolation, seccomp, containers, or explicit capability filters. This design has none.
- **No tool versioning or test gating.** Artifact versioning, CI checks, and deprecation policies are standard for reproducible agent systems. `git commit` is not a substitute.
- **No structured observability.** Competitors track structured events, spans, and error taxonomies. `emit!` + truncated EDN is not a substitute for real agent observability.
- **No concurrent or parallel task execution.** Any agent use case involving independent subtasks is architecturally impossible without redesigning the core loop.

---

## Falsification Tests

These are the 7 scenarios most likely to break the system immediately. They should be the first things attempted after a working v1 exists:

| Test | What It Exposes |
|---|---|
| Generate a tool with `s/def` + destructive I/O | "Validation" passes; unsafe code runs unimpeded |
| Force planner to emit malformed EDN | Loop stalls; no deterministic recovery path |
| Create a tool with an infinite loop | No timeout; process hangs indefinitely |
| Change a promoted tool after a plan references it | Sessions become non-reproducible silently |
| Kill the process mid-execution | Trace persists nothing; entire audit trail gone |
| Grow session past 8k/4k truncation limits | LLM loses critical prior state; decisions degrade without warning |
| Pass large/nested EDN as CLI args | Quoting/size failure; task silently drops or corrupts args |

---

## Additional Design-Level Concerns

### From Prior Brainstorming Reviews

**`*result*` dynamic var:**
Dynamic bindings are thread-local and do not cross `bb -f` subprocess boundaries under any circumstances. The current design acknowledges this but leaves the gap unresolved. Inter-task data flow via dynamic var is architecturally broken for the primary execution path.

**Per-invocation process spawning:**
50ms × N tasks is noise for coarse plans, but will penalize workloads chaining many small tools — file stat/grep/format loops, tight retry sequences, multi-step refactors. No path to optimization exists without architectural change.

**`plan-amend!` as first-line failure recovery:**
LLM re-planning should not be the first recovery step. Task failures are almost always wrong args, tool bugs, or environment problems — all of which are better served by a constrained retry-with-feedback loop before escalating to full re-planning. Jumping to `plan-amend!` on first error wastes a full LLM round-trip and often regenerates the same plan with different tool names.

**The tool library as unbounded free-form code:**
The biggest long-term risk: using free-form generated code as the primary action space without a stable, versioned capability boundary. As the library grows, tools will drift, overlap, and conflict. The LLM will generate subtly different versions of the same tool across sessions. Refactoring a core tool breaks everything that calls it — silently. There is no shared contract, no stability tier, and no composability primitive beyond "call this function."

**Session trace growth:**
The append-only model breaks down when tools embed large payloads (stdout, diffs, file blobs) and long runs accumulate to tens of MB. The LLM sees a truncated slice, but memory, I/O, and postmortems suffer. No compaction, no payload spillover, no rolling window strategy is specified.

---

---

# Developer Response — Rebuttal Scorecard

*The developer wrote a point-by-point rebuttal in `challenges-response.md`. The following is an independent evaluation of how well each rebuttal holds up.*

---

## Meta-Argument Verdict

The developer's core framing — *"the review applies production multi-tenant standards to a v1 local tool"* — **partially holds**.

The critique did overreach in places. `:confirm true` semantics, `llm-context` purity, and the subprocess model were legitimate pushbacks. The single-operator trust model is a valid design choice, not a flaw.

But the meta-argument doesn't cover everything. **These items are v1 prerequisites, not v2 niceties** — deferring them is not a framing error, it's a risk:

1. **Durable trace persistence** — data loss on crash is not acceptable for a system that calls the trace "the single source of truth."
2. **Explicit retry escalation ladder** — without a defined retry→plan-amend→ask-human path, the failure loop is unbounded; that is a v1 reliability problem.
3. **Concrete timeout/cancellation** — the rebuttal says "wrap with `timeout`" but this is unspecified and untested. A hanging tool that blocks the entire session is a v1 problem.

---

## Rebuttal Scores

| Claim | Verdict | Reasoning |
|---|---|---|
| **Append-only trace** ("ops not state, like a DB log") | **Partially holds** | The framing is valid for an audit log. But without durable incremental append, replay and crash recovery remain fragile. Good that the developer accepts the fix. |
| **Unsandboxed eval** ("Claude Code does it too") | **Partially holds — leans rationalization** | Acceptable for a single-operator tool with enforced confirmation. But "Claude Code does it too" is a rationalization. Minimal guardrails (diffs, cwd fences) reduce foot-guns without contradicting the local-tool premise. |
| **`re-find` validation** ("validation ≠ sandboxing") | **Holds** | Clean separation of type/shape validation from security. The original critique conflated two distinct concerns. The developer is right. |
| **`:confirm true`** ("set by registry, not LLM") | **Holds** | If the executor unconditionally enforces registry-defined flags with no bypass path, the LLM cannot disable confirmation. This is a legitimate design invariant — verify it with tests. |
| **`llm-context` purity** ("inputs are injected") | **Holds** | If inputs are injected and no I/O occurs inside the function, it is pure. The original critique misread the call boundary. |
| **`sci/eval-string`** ("isolated context, not production") | **Partially holds** | SCI can be tightly restricted and is not equivalent to production execution — *but only if* namespaces are whitelisted and FS/network are truly excluded. As currently specified ("no capability restrictions"), eval can still burn unbounded CPU and time. |
| **Per-`bb -f` subprocess** ("PIDs, `timeout`, `pmap`") | **Holds** | Timeouts, cancellation, and parallelism via existing APIs are sufficient. No architectural redesign required — just disciplined implementation when needed. |
| **Truncation** ("everyone truncates") | **Partially holds — leans rationalization** | Context limits are universal, but the design issue is the *truncation policy* (what gets pinned, what gets summarized, what gets dropped). "Everyone truncates" does not address quality regressions from naive oldest-first FIFO loss. |
| **Incremental trace persistence** ("one-line fix") | **Holds** | Accepting and fixing is correct. "One line" understates the need for atomic writes and rotation, but the direction is right. |

---

## The 3 Challenges That Remain Genuinely Open

These are unresolved regardless of the v1/v2 framing — the developer's rebuttal does not adequately address them:

### 1. Context Management Policy (not just truncation existence)

The design specifies "truncate at 8k/4k" but no pin/prioritize/summarize policy. Oldest-first FIFO loss will silently degrade LLM decision quality on any non-trivial session. The rebuttal says "smarter windowing is v2" but doesn't acknowledge that naive truncation actively harms the quality of the primary agent loop — this is a v1 correctness concern, not a scalability concern.

### 2. Irreversible Action Safeguards Beyond `:confirm true`

`:confirm true` covers `:shell` and `:llm-complete`. Generated tools can write files, make network calls, modify state — and none of those go through a confirmation gate. The rebuttal says "the human is the last gate" but the human sees only the tool name and args, not what the tool will actually do. There is no diff preview, no cwd fence, no allow/deny policy for generated tool side effects. This gap is unaddressed.

### 3. Robust Process Supervision

The rebuttal handles the simple case (PIDs, `timeout`). It does not address: cleanup of grandchild processes spawned inside a tool, deterministic log collation when a tool produces output on multiple streams, or backpressure when stdout is large. These are not edge cases — they are normal tool behavior for any realistic agent workload.

---

## Net Assessment of the Rebuttal

The developer's response is above average quality. It correctly identifies where the critique overreached, accepts the real issues cleanly, and draws the right two-item fix list. 

**Strongest parts:** the `:confirm true` correction, the `llm-context` purity defense, and the honest acceptance of incremental trace persistence as the one v1 blocker.

**Weakest parts:** the "Claude Code does it too" rationalization on sandboxing, and the hand-wave on context management policy (the truncation policy gap is a v1 concern, not a v2 one).

**The developer's two-item pre-implementation fix list is correct:**
1. Incremental trace persistence — append atomically on every `append-event!`, not at session end.
2. Explicit retry escalation ladder — retry → retry-with-context → `plan-amend!` → ask human, with bounded attempts at each level.

To that list, one item should be added:
3. **SCI namespace whitelist** — explicitly declare which namespaces are accessible in the validation context; verify no FS/network access is possible; add a timeout. The current "no capability restrictions" spec makes the `sci/eval-string` safety claim indefensible.
