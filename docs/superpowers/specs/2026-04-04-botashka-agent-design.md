# Botashka Agent — Design Spec

**Date:** 2026-04-04  
**Inspired by:** CodeAct (arXiv:2402.01030) — code-as-action agent framework  
**Runtime:** Babashka (Clojure scripting via SCI/GraalVM)  
**LLM:** Claude claude-sonnet-4-5 (Anthropic API)

---

## Overview

Botashka is a local, general-purpose agentic assistant — a Clojure-native alternative to tools like Claude Code or OpenClaw. Its core idea: instead of calling predefined JSON tools, the agent generates and reuses small, composable Clojure snippets as its action space (CodeAct style). Babashka provides the runtime; clojure.spec provides validation; the session-trace is the planning substrate.

---

## Goals

- General-purpose agentic assistant, runs locally by a single developer
- Clojure/Babashka as the primary runtime — minimal dependencies, fast startup
- Code generation as the primary action mechanism (not JSON tool calls)
- Self-growing tool library: agent generates, validates, saves, and reuses tools
- `(b/tasks)` REPL command prints current todo state; human can inspect progress at any time
- Single `emit!` output seam so future UIs (nREPL clients, Telegram, WeChat) require zero agent core changes

---

## Architecture

### Four Zones

1. **Human / REPL** — V1: `bb repl` with `start!` read-line loop. V2+: nREPL server — any nREPL client (CIDER, Calva, vim-iced) connects and receives output as `:out` messages.
2. **Agent Core** — three layers: Planner → ReAct Loop → Spec Validator.
3. **LLM** — Claude claude-sonnet-4-5 via Anthropic API. Used for planning decomposition and ReAct reasoning.
4. **Tool Library + Executor** — `tools/*.clj` files with `tools/tool-index.edn`, executed in Babashka/SCI. JVM `clojure -M` available as escape hatch when SCI limits are hit.


---

## Planning Layer

### Todo System

Botashka implements its own lightweight todo system. The live plan lives entirely in the **session-trace** as `:todo` and `:todo-update` events — no file on disk, no Babashka task runner involved at execution time.

When given a goal, the planner LLM decomposes it into ordered steps and appends a `:todo` event to the trace:

```clojure
{:type  :todo
 :tasks [{:name "fetch-notes" :doc "Fetch HTML from target URL"   :status :open :depends []              :notes nil}
         {:name "extract-ids" :doc "Parse SAP note IDs from HTML" :status :open :depends ["fetch-notes"] :notes nil}
         {:name "save-csv"    :doc "Write IDs to output.csv"      :status :open :depends ["extract-ids"] :notes nil}]}
```

The LLM updates individual tasks at any time via the `:todo-update` built-in — changing `:status` or adding `:notes`:

```clojure
{:type :todo-update :name "fetch-notes" :status :in-progress :notes "Got 404, retrying with proxy"}
```

The current todo state is always derived from the trace — the last `:todo` event merged with all subsequent `:todo-update` events. This is a pure function of the trace, consistent with `task-status`.

`run-goal!` iterates tasks in dependency order via a topological sort on `:depends`, calling `react-step!` for each. This replaces Babashka's task runner entirely at execution time.

**Human visibility** is provided by `(b/tasks)` — a REPL helper that prints the current todo state derived from the trace:

```
botashka> (b/tasks)
  ✓ fetch-notes   Fetch HTML from target URL
  ● extract-ids   Parse SAP note IDs from HTML   [got 404, retrying with proxy]
  ○ save-csv      Write IDs to output.csv
```

The agent can amend the plan mid-execution — see **Plan Mutation** below.

### Plan Mutation

The LLM is allowed to amend the plan mid-execution — for example when an `:observe` result reveals a different structure than expected, a tool fails, or the goal turns out to be more complex. Forcing it to finish the original plan anyway is brittle.

Plan mutation is **always explicit**. There are two levels:

**Lightweight update** — the LLM calls `:todo-update` to change `:status` or `:notes` on an existing task. Fast and cheap, no `:plan-amend` event.

**Structural replan** — when task names, dependencies, or the task set itself changes, the agent appends a `:plan-amend` event followed by a new `:todo` event with the revised task list:

```clojure
{:type      :plan-amend
 :reason    "fetch-url returned 404 — switching to scrape-via-proxy"
 :old-tasks [:fetch :parse]
 :new-tasks [:proxy-fetch :parse]}

{:type  :todo
 :tasks [{:name "proxy-fetch" :doc "Fetch via proxy" :status :open :depends []}
         {:name "parse"       :doc "Extract IDs"     :status :open :depends ["proxy-fetch"]}]}
```

After a structural replan, `select-events :think` includes `:plan-amend` events so the LLM has full context about what has already been tried and why it changed. Silent structural rewrites are not allowed.

`plan-amend!` in `planner.clj` is the entry point for structural replanning. It appends `:plan-amend` + `:todo` events and re-calls the LLM for a revised task list:

```clojure
(defn plan-amend!
  "Re-plan when a task fails or the goal requires revision.
  Appends :plan-amend + :todo to session-trace before calling the LLM, so the
  revised plan has full context about what failed and why."
  [ctx reason old-task-names]
  ...)
```

`core.clj` calls `plan-amend!` when `react-step!` returns `{:status :error}` and retries are exhausted.

### Subagents and Task Lists

For subagents spawned programmatically mid-session, the subagent receives a fresh `session-trace` atom and a `:todo` event as its starting state. It runs its own `run-goal!` loop against that trace — no external file writes required.

| Agent type | Todo state |
|---|---|
| Root agent | `:todo` events in shared session-trace |
| Programmatic subagent | `:todo` events in own fresh session-trace |

### Task Status

Task status is a **derived property of session-trace**, not a stored field:

```clojure
(defn task-status [session-trace task-name]
  (let [events (filter #(= (:task %) (name task-name)) session-trace)]
    (cond
      (some #(= :error (:type %)) events)   :error
      (some #(= :observe (:type %)) events) :finished
      (some #(= :think (:type %)) events)   :in-progress
      :else                                  :open)))
```

Status is a pure function of what already happened — it cannot be inconsistent with the trace. The four states match the standard agent todo model:

| Status | Derived from trace |
|---|---|
| `:open` | No events for this task yet |
| `:in-progress` | `:think` or `:execute` seen, no `:observe` yet |
| `:finished` | `:observe` seen |
| `:error` | `:error` event seen for this task |

Human display is provided by `(b/tasks)` — a REPL helper that derives and prints the current todo state from the trace.

---

## ReAct Loop

Each `bt/run` call triggers the ReAct loop for that task step. The loop has **4 steps**:

1. **THINK** — LLM receives: task context + `tools/tool-index.edn` (all tool descriptions) + conversation history. Outputs: reasoning + one of three decisions:

   | Decision | Meaning |
   |---|---|
   | **Reuse** | An existing tool matches exactly — no new code generated |
   | **Generate** | Novel logic needed — LLM writes a new `.clj` snippet |
   | **Compose** | New glue code that calls existing tools together — LLM writes the composition |

   Reuse skips step 2 entirely and goes straight to EXECUTE. Generate and Compose both go through step 2.

2. **GENERATE** (only for Generate and Compose decisions) — LLM writes a `.clj` snippet with a description comment, `:requires`, and clojure.spec definitions for input and output. **Spec validation happens immediately on the generated source** before anything else:
   - Syntactic check (`re-find`) confirms at least one `s/def` or `s/fdef` is present
   - `sci/eval-string` evaluates the source in Babashka's native SCI (where `clojure.spec.alpha` is available) to catch parse and runtime errors
   - Failure → error fed back to LLM for retry (max 3 attempts)
   - Pass → tool saved to `tools/`, entry appended to `tool-index.edn`

   The rule is simple: **any generated code is validated before execution, always** — whether it is novel logic or a composition of existing tools. A composed tool is saved to `tools/` like any other and becomes reusable.

3. **EXECUTE** — run the saved tool file via `bb -f tools/<name>.clj`. Args are passed as an EDN-encoded string on the command line and read inside the tool via `(first *command-line-args*)`. stdout/stderr captured as observation. If SCI limit hit → spawn `clojure -M` subprocess as escape hatch.

4. **OBSERVE** — result returned to LLM context. Loop continues until the task step is complete or needs human input.

---

## Tool Library

### File structure

Each tool is a single `.clj` file in `tools/`:

```clojure
;; description: Fetch the body of an HTTP URL as a string
;; requires: [babashka.http-client :as http]
;; spec-in:  string? (valid URL)
;; spec-out: string? (response body)

(ns tools.fetch-url
  (:require [babashka.http-client :as http]
            [clojure.spec.alpha :as s]))

(s/def ::url string?)
(s/def ::body string?)

(defn fetch-url [url]
  (:body (http/get url)))
```

### Tool index

`tools/tool-index.edn` — the index the LLM reads when deciding to reuse or generate:

```clojure
{:fetch-url {:description "Fetch the body of an HTTP URL as a string"
             :spec-in     "string? (valid URL)"
             :spec-out    "string? (response body)"}
 :write-file {:description "Write a string to a file at the given path, returns the path"
              :spec-in     "map with :path (string) and :content (string)"
              :spec-out    "string? (absolute path written)"}}
```

The LLM always receives `tool-index.edn` in its context. After generating a new tool, the agent appends its entry to the index before saving the `.clj` file.

### Tool lifecycle

- **Create** — generated by LLM, validated by spec, saved to `tools/`
- **Reuse** — LLM selects from index by reading descriptions; no embedding/search needed
- **Promote** — tool survives across sessions by being committed to git
- **Deprecate** — entry removed from `tool-index.edn` (file kept for reference)

### Built-in Tools

Built-ins are trusted infrastructure available in every session without generation or spec validation. They live in `tool-index.edn` alongside generated tools, marked `:builtin true`. THINK can select them like any other tool; the executor dispatches them directly to Clojure functions in `core.clj` / `llm.clj` instead of spawning a `bb eval` subprocess.

```clojure
;; tool-index.edn — built-ins listed alongside generated tools
{;; ── I/O ──────────────────────────────────────────────────────
 :read-file    {:builtin     true
                :description "Read a file at path, return content string"
                :spec-in     "string? (path)"
                :spec-out    "string? (content)"}
 :write-file   {:builtin     true
                :description "Write content string to a file at path, return path"
                :spec-in     "map with :path (string) and :content (string)"
                :spec-out    "string? (path written)"}
 :list-dir     {:builtin     true
                :description "List files in a directory, return seq of path strings"
                :spec-in     "string? (directory path)"
                :spec-out    "seq of strings"}
 :shell        {:builtin     true
                :confirm     true
                :description "Run a shell command, return {:stdout :stderr :exit}"
                :spec-in     "string? (shell command)"
                :spec-out    "map with :stdout :stderr :exit"}

 ;; ── Trace ─────────────────────────────────────────────────────
 :session-trace {:builtin     true
                 :description "Return the current session-trace as an EDN string"
                 :spec-in     "nil"
                 :spec-out    "string? (EDN)"}
 :task-status  {:builtin     true
                :description "Return the status of a named task (:open/:in-progress/:finished/:error)"
                :spec-in     "string? (task name)"
                :spec-out    "keyword?"}

 ;; ── Tool Library ──────────────────────────────────────────────
 :tool-index   {:builtin     true
                :description "Return the full tool-index.edn as a string"
                :spec-in     "nil"
                :spec-out    "string? (EDN)"}
 :save-tool    {:builtin     true
                :description "Save a named tool (code + meta) to the library"
                :spec-in     "map with :name :code :description :spec-in :spec-out"
                :spec-out    "string? (path saved)"}

 ;; ── LLM ───────────────────────────────────────────────────────
 :llm-complete {:builtin     true
                :confirm     true
                :description "Call Claude with {:system :messages}, return response string"
                :spec-in     "map with :system (string) and :messages (string)"
                :spec-out    "string? (LLM response)"}

 ;; ── Generated tools added below ───────────────────────────────
 :fetch-url    {:description "Fetch the body of an HTTP URL as a string"
                :spec-in     "string? (valid URL)"
                :spec-out    "string? (response body)"}}
```

**Confirmation policy** — two built-ins require explicit human approval before the agent can invoke them:

| Built-in | Risk | Confirmation |
|---|---|---|
| `:shell` | Arbitrary shell execution — destructive, irreversible | Yes — show command, await `y/n` |
| `:llm-complete` | Incurs API cost; may leak context to external service | Yes — show payload size, await `y/n` |
| All others | Read-only or local writes | No |

The `:confirm true` flag in the index is the signal. The executor checks it before dispatch and calls `emit! {:type :ask …}` to prompt the human. If denied, the agent receives `:status :denied` as the observation and must decide how to proceed.

**Executor dispatch:** the executor checks `:builtin true` and, for `:confirm true` tools, emits `:ask` and awaits `y/n` before dispatching the direct fn call. Denied → `{:status :denied}` returned as observation. Approved → direct fn call, result appended to session-trace as `:observe`.

---

## Output Layer — emit!

All agent output goes through a single `emit!` function in `core.clj`. This is the **UI seam** — the only thing that changes between v1 and v2:

```clojure
;; V1 — prints directly; works as nREPL :out automatically if running under bb nrepl-server
(defn emit! [event]
  (println (format-event event)))
```

`emit!` accepts structured event maps. `format-event` renders them as human-readable lines:

| Event type | Rendered output |
|---|---|
| `{:type :think :text "…"}` | `[thinking] …` |
| `{:type :reuse :text "fetch-url"}` | `[reuse] fetch-url` |
| `{:type :generate :text "parse-title"}` | `[generate] parse-title → saved tools/parse-title.clj` |
| `{:type :validate :status :ok}` | `[validate] ok` |
| `{:type :validate :status :fail :attempt 1 :message "…"}` | `[validate] attempt 1 failed: …` |
| `{:type :execute :text "fetch-url"}` | `[execute] fetch-url → done` |
| `{:type :answer :text "…"}` | `…` (printed as-is) |
| `{:type :error :text "…"}` | `[error] …` |

`react-step!` and all agent layers call `emit!` — none are aware of the transport. See **Future UI: nREPL Transport** for the v2 migration path.

---

## Session Trace

`session-trace` is the core runtime data structure — an append-only sequence of typed event maps representing everything that happened in a session. It serves three consumers: `llm-context` (select + render per call type), `task-status` (pure fn derivation), and `persist!` (EDN file at session end).

```clojure
;; session-trace: a vector of typed event maps (append-only)
[{:type :user-input  :text "fetch the title of https://example.com"}
 {:type :todo        :tasks [{:name "fetch" :doc "Fetch HTML" :status :open :depends [] :notes nil}
                              {:name "parse" :doc "Extract title" :status :open :depends ["fetch"] :notes nil}]}
 {:type :think       :text "Deciding how to proceed..." :task "fetch"}
 {:type :reuse       :tool :fetch-url :task "fetch"}
 {:type :execute     :tool :fetch-url :args {:url "https://example.com"} :task "fetch"}
 {:type :observe     :tool :fetch-url :result "<html>…</html>" :task "fetch"}
 {:type :todo-update :name "fetch" :status :finished :notes nil}
 {:type :think       :text "Deciding how to proceed..." :task "parse"}
 {:type :generate    :tool :parse-title :task "parse"}
 {:type :validate    :tool :parse-title :status :ok :task "parse"}
 {:type :execute     :tool :parse-title :args {:html "<html>…</html>"} :task "parse"}
 {:type :observe     :tool :parse-title :result "Example Domain" :task "parse"}
 {:type :assistant-out :text "Example Domain"}]
```

Every `emit!` call appends to `session-trace`. The trace is the single source of truth for replay, debugging, and LLM context assembly.

### Session Persistence

At session end, `core.clj` writes the full trace to `sessions/<session-id>.edn` on disk. The session ID is a timestamp-based string (e.g. `"2026-04-04T14:32:00"`). This allows post-session replay and debugging. The file is plain EDN — the same format as the in-memory vector. Persistence is write-once at session end; it is never read back during the same session.

```clojure
;; Written to sessions/2026-04-04T14:32:00.edn at session end
(spit (str "sessions/" session-id ".edn") (pr-str @session-trace))
```

Session files are gitignored by default. Promoting a session for debugging purposes (e.g. to file a bug report) is an explicit user action.

### File context — @path syntax

Users can reference files inline using `@path` syntax:

```
🤖 botashka> refactor @src/botashka/react.clj to use the new llm-context API
```

`core.clj` parses `@path` tokens from the raw input before appending `:user-input`. For each token it reads the file and prepends a `:file-context` event:

```clojure
[{:type :file-context :path "src/botashka/react.clj" :content "…full file text…"}
 {:type :user-input   :text "refactor src/botashka/react.clj to use the new llm-context API"}]
```

The `@path` token in `:user-input` is replaced with the plain path string (no `@`). `select-events` for `:think` includes all `:file-context` events that precede the current `:user-input`, so the LLM always sees referenced file contents when reasoning about a task.

Because file contents can be large, the **truncate** step in the context pipeline is the primary defence against token overflow. Each `:file-context` entry is truncated to a configurable character limit before rendering; the limit defaults to 8 000 characters per file.

### Event types

| Type | When | Key fields |
|---|---|---|
| `:file-context` | User references `@path` in input | `:path`, `:content` |
| `:user-input` | User types a goal | `:text` |
| `:todo` | Planner sets or replans the task list | `:tasks` (vec of `{:name :doc :status :depends :notes}`) |
| `:todo-update` | LLM updates a single task's status or notes | `:name`, `:status`, `:notes` |
| `:plan-amend` | Agent makes a structural replan | `:reason`, `:old-tasks`, `:new-tasks` |
| `:think` | LLM decides reuse/generate/compose | `:text`, `:task` |
| `:reuse` | LLM selects existing tool | `:tool`, `:task` |
| `:generate` | LLM writes new tool | `:tool`, `:task` |
| `:validate` | Spec check result | `:tool`, `:status`, `:attempt`, `:message`, `:task` |
| `:execute` | Tool runs | `:tool`, `:args`, `:task` |
| `:observe` | Tool result captured | `:tool`, `:result`, `:task` |
| `:assistant-out` | Final answer to user | `:text` |
| `:error` | Any failure | `:text` |
| `:ask` | Agent needs human clarification or confirmation | `:text` |

---

## LLM Context Assembly

`llm-context` is a pure function that assembles the full API payload for a given LLM call — system prompt plus the relevant slice of `session-trace` rendered as messages. The pipeline is: **select** (filter session-trace by relevant event types) → **truncate** (cap each `:file-context` entry at 8 000 chars; cap `:observe` results at 4 000 chars) → **render** (format as string). No embedding, no vector search.

```clojure
;; botashka/llm_context.clj
(defn llm-context
  "Given the full session-trace and the event type of the upcoming LLM call,
  return a map {:system <string> :messages <string>} ready for the LLM API.
  System prompt is a constant per call type; messages are selected from the trace."
  [session-trace event-type]
  {:system   (system-prompt event-type)
   :messages (render-trace (select-events session-trace event-type))})
```

The system prompt is **configuration, not history** — it is fixed per call type and never stored in `session-trace`. The Anthropic API takes it as a separate top-level `system` field, so it belongs outside the messages array.

`llm_context.clj` owns all of:
- `system-prompt` — constants per call type (replaces the scattered `think-system-prompt`, `plan-system-prompt` in react.clj / planner.clj)
- `select-events` — filter pipeline per call type
- `render-trace` — formats selected events as a string
- `llm-context` — composes the above into the final API payload map

Each event type routes to its own pipeline:

| LLM call | System prompt | Context selected from trace |
|---|---|---|
| `:plan` | Planner instructions | file-context + user-input only |
| `:think` | ReAct THINK instructions | file-context + user-input + todo (current task list) + plan-amend history + last `:observe` + tool-index (read from filesystem, appended as final message) |
| `:generate` | Code generation instructions | last `:think` decision only |

---

## Project Structure

```
botashka/
├── src/
│   ├── botashka/core.clj         ← start!, emit!, run-goal!, topo-sort, session-trace atom
│   ├── botashka/planner.clj      ← LLM → :todo event writer, plan-amend!
│   ├── botashka/todo.clj         ← todo state derivation: current-todo, task-status pure fns
│   ├── botashka/react.clj        ← ReAct loop (think/generate/execute/observe)
│   ├── botashka/tools.clj        ← tool lib management (load/save/list)
│   ├── botashka/builtins.clj     ← built-in tool dispatch (read-file, shell, todo-update, llm-complete, …)
│   ├── botashka/spec.clj         ← spec validation helpers
│   ├── botashka/llm.clj          ← Claude API client (non-streaming v1)
│   └── botashka/llm_context.clj  ← context assembly — llm-context pure fn + filter pipelines
└── tools/
    ├── tool-index.edn            ← tool registry (built-ins + generated tools)
    ├── fetch-url.clj
    ├── write-file.clj
    └── ...
```

`tui.clj` is not present in v1 — `emit!` in `core.clj` is the output layer.

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Runtime | Babashka (SCI) | Fast startup, no JVM overhead, real Clojure |
| JVM escalation | `clojure -M` subprocess | Escape hatch for SCI limits, explicit boundary |
| Action space | Generated Clojure snippets | CodeAct: more expressive than JSON tool calls |
| Tool types | Generated tools + built-in tools | Built-ins are trusted infrastructure (`:builtin true` in index); generated tools must pass spec validation; executor dispatches differently for each |
| Confirmation policy | `:confirm true` on `:shell` and `:llm-complete` | High-risk built-ins require `y/n` before execution; others run freely |
| Tool selection | LLM reads index (built-ins + generated) | Keeps decision in one place, no embedding overhead |
| Spec validation | Syntactic `re-find` for `s/def`/`s/fdef` + `sci/eval-string` | Avoids macro-interception issues; `clojure.spec.alpha` is natively available in Babashka SCI |
| Planning substrate | Own todo system — `:todo`/`:todo-update` events in session-trace | Babashka has no programmatic in-memory task API; rewriting an external file on every LLM update is fragile and unnecessary |
| Plan mutation | Two levels: lightweight `:todo-update` (status/notes only) and structural `plan-amend!` (new `:todo` event) | Fast updates are cheap; structural changes always produce an auditable `:plan-amend` event |
| Dependency ordering | Topological sort on `:depends` in `core.clj` | Replaces Babashka's task runner at execution time; trivial to implement, no file I/O |
| Task status | Derived from `session-trace` via `task-status` pure fn | Trace is authoritative; `(b/tasks)` renders current state for human inspection |
| Task output | Passed explicitly between tasks via session scratch (`sessions/<id>/scratch.edn`); executor writes result under task name key | Survives subprocess boundary; deterministic, inspectable, no dynamic var leakage |
| Session persistence | Trace written to `sessions/<id>.edn` at session end | Plain EDN; replay and debug without a DB |
| Runtime data structure | `session-trace` (append-only event vector) | Single source of truth for replay, debug, and context assembly |
| Context assembly | `llm-context` pure fn in `llm_context.clj`; ctx map `{:system :messages}` | Selects relevant trace slice per LLM call — no embedding, no vector search |
| LLM interface | `complete` accepts `{:system <string> :messages <string>}` ctx map | Matches `llm-context` output directly; `anthropic-version` header required |
| Communication | `emit!` function (v1 println → nREPL `:out` within eval op in v2) | Eval-scoped `:out` delivery; no custom ops or broadcast protocol needed for v1/v2 |
| LLM v1 | Non-streaming `complete` wrapping SSE | Simple for v1; `stream-chat` is the v2 upgrade path |
| V1 UI | `bb repl` + `start!` loop | Zero TUI code; `emit!` is the seam for future adapters |

---

## Future UI: nREPL Transport

The preferred v2 UI path is **nREPL**, not a custom TUI. When Babashka runs `bb nrepl-server`, any nREPL client (CIDER, Calva, vim-iced, a custom terminal client) connects and receives Botashka output as standard nREPL `:out` messages — one per `println`. This means:

- **Editor integration is free.** CIDER/Calva users get Botashka inline in their editor with zero extra code — just `(b/start!)` in the REPL.
- **`:out` messages are eval-scoped.** nREPL delivers `:out` messages to the client that initiated the active `eval` op. This means `println` → `:out` works reliably for any `emit!` call within the `start!` eval. Out-of-band broadcast to arbitrary connected clients is **not** supported by the stock protocol and is not a V2 goal.
- **Custom TUI becomes a thin nREPL client.** It evaluates `(b/start!)` and renders the `:out` stream. No custom protocol needed.

> **V2 scope is intentionally narrow:** swap `emit!` to print within a long-lived nREPL `eval` op. Multi-client broadcast or push-to-arbitrary-session are V3 concerns if they arise.

### nREPL streaming is built in — no extensions needed

nREPL's protocol is inherently message-based and async. During a single `eval` request, the server sends multiple response messages as they are produced:

| Message | When sent |
|---|---|
| `{:out "…"}` | One per `println`/`flush` — delivered to the eval's initiating client |
| `{:err "…"}` | stderr |
| `{:value "…"}` | Final return value |
| `{:status "done"}` | Completion sentinel |

For Botashka: each `(emit! event)` → `(println …)` → one `:out` message delivered to the client that started the `(b/start!)` eval. For token-level LLM streaming, each `(print token)(flush)` → one `:out` message per token. **No protocol extensions, custom middleware, or new ops required for this use case.**

nREPL 1.3 (August 2024) specifically improves this path:
- Custom async executors — the `future`-based SSE reader integrates cleanly with nREPL's executor model
- Improved session/dynamic bindings — `binding [*out*]` from non-eval threads (e.g. `future`) is reliably captured
- Built-in client prints all output — confirms `:out` stream is stable from any thread

### The emit! migration path

V1 `emit!` prints directly — already works as nREPL `:out` automatically when running under `bb nrepl-server`:

```clojure
;; V1 — works as-is under nREPL; println → :out message automatically
(defn emit! [event]
  (println (format-event event)))
```

V2 `emit!` is the same in practice — `println` inside a nREPL eval already routes to `:out`. An explicit `nrepl.transport/send` dispatch is only needed if Botashka moves to a custom op or needs to route to a specific session:

```clojure
;; V2 optional — explicit session dispatch for custom-op scenarios only
(defn emit! [event]
  (nrepl.transport/send *session* {:op "out" :out (format-event event)}))
```

For token streaming, bind `*out*` in the `future` to the nREPL session's output stream — nREPL 1.3 handles this reliably:

```clojure
(future
  (binding [*out* (nrepl.middleware.session/session-out :out session)]
    (doseq [token (sse-token-seq response)]
      (print token)
      (flush))))  ; each flush → :out message to nREPL client
```

### UI Roadmap

| Version | Interface | How |
|---|---|---|
| V1 | `bb repl` — `start!` read-line loop | Zero extra code — `emit!` prints to stdout |
| V2 | `bb nrepl-server` — any nREPL client | Swap `emit!` body; CIDER/Calva/vim-iced work immediately |
| V3 | Custom TUI / Telegram / WeChat | Thin nREPL client that renders `:out` messages |

---

## Out of Scope (V1)

- nREPL server mode — deferred to v2; v1 uses `bb repl` directly
- Custom TUI / Telegram / WeChat — deferred to v3; implemented as thin nREPL clients
- Subagent parallelism via channels
- Tool versioning / rollback
- Embedding-based tool search
