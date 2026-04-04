# Botashka Agent — Design Spec

**Date:** 2026-04-04  
**Inspired by:** CodeAct (arXiv:2402.01030) — code-as-action agent framework  
**Runtime:** Babashka (Clojure scripting via SCI/GraalVM)  
**LLM:** Claude claude-sonnet-4-5 (Anthropic API)

---

## Overview

Botashka is a local, general-purpose agentic assistant — a Clojure-native alternative to tools like Claude Code or OpenClaw. Its core idea: instead of calling predefined JSON tools, the agent generates and reuses small, composable Clojure snippets as its action space (CodeAct style). Babashka provides the runtime; clojure.spec provides validation; bb.edn tasks provide the planning substrate.

---

## Goals

- General-purpose agentic assistant, runs locally by a single developer
- Clojure/Babashka as the primary runtime — minimal dependencies, fast startup
- Code generation as the primary action mechanism (not JSON tool calls)
- Self-growing tool library: agent generates, validates, saves, and reuses tools
- Human-readable, inspectable plan (bb.edn) the user can edit at any time
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

The agent uses `bb.edn` as its live, human-readable plan. When given a goal:

1. LLM decomposes the goal into ordered steps with `:depends` chains
2. Agent writes these as `:tasks` entries into `bb.edn` (pure EDN write — no special API needed)
3. Human can inspect and edit `bb.edn` before or during execution
4. Agent executes each task via `(babashka.tasks/run 'task-name)` — Babashka handles dependency ordering automatically
5. Agent can amend the plan mid-execution — see **Plan Mutation** below

Each task entry has a `:doc` string describing its purpose, making `bb.edn` a human-readable progress log.

**bb.edn is the human interaction layer, not the execution substrate.** The agent can run tasks without bb.edn — it executes task lists as plain in-memory EDN data. bb.edn is the window the human looks through: inspectable, editable, committed to git.

Example `bb.edn` written by the agent (`run-tool` is a Botashka-provided helper defined in `:init` that triggers the ReAct loop for a named tool with given args):
```clojure
{:init (require '[botashka.core :refer [run-tool]])
 :tasks
 {fetch-notes  {:doc "Fetch HTML from target URL"
                :task (run-tool 'fetch-url {:url url})}
  extract-ids  {:depends [fetch-notes]
                :doc "Parse SAP note IDs from HTML"
                :task (run-tool 'parse-sap-ids {:html *result*})}
  save-csv     {:depends [extract-ids]
                :doc "Write IDs to output.csv"
                :task (run-tool 'write-csv {:rows *result*})}}}
```

### Plan Mutation

The LLM is allowed to amend the plan mid-execution — for example when an `:observe` result reveals a different structure than expected, a tool fails, or the goal turns out to be more complex. Forcing it to finish the original plan anyway is brittle.

Plan mutation is **always explicit**: the agent emits a `:plan-amend` event before rewriting bb.edn, recording why the plan changed and what was replaced:

```clojure
{:type      :plan-amend
 :reason    "fetch-url returned 404 — switching to scrape-via-proxy"
 :old-tasks [:fetch :parse]
 :new-tasks [:proxy-fetch :parse]}
```

`select-events :think` includes `:plan-amend` events so the LLM has full context about what has already been tried and why it changed. Silent rewrites are not allowed — every plan change must produce a `:plan-amend` event in the trace.

### Subagents and Task Lists

Babashka does not support multiple bb.edn files in a single project — `bb` looks for one bb.edn at the project root. For subagents spawned programmatically mid-session, bb.edn is not used. Instead, the subagent's task list is passed as a plain in-memory EDN map (same shape as bb.edn `:tasks`) and executed directly via `react-step!` calls in a loop — no file write needed.

```clojure
;; subagent task list — plain EDN data, never written to bb.edn
{fetch  {:doc "Fetch the page" :task …}
 parse  {:doc "Extract the data" :depends [fetch] :task …}}
```

The parent agent writes bb.edn (human-visible, editable). Subagents carry their task list as data. If a human-inspectable plan per subagent is needed, `bb --config .sessions/<task-id>/bb.edn run` can point Babashka at a per-subagent config file — but this is an escape hatch, not the default path.

| Agent type | Task list format | bb.edn written? |
|---|---|---|
| Root agent | bb.edn `:tasks` map | Yes — human-editable |
| Programmatic subagent | In-memory EDN map | No |
| Human-inspectable subagent | Separate bb.edn via `--config` | Yes — explicit opt-in |

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
   - `spec/conform` on the generated code structure and sample inputs
   - Failure → error fed back to LLM for retry (max 3 attempts)
   - Pass → tool saved to `tools/`, entry appended to `tool-index.edn`

   The rule is simple: **any generated code is validated before execution, always** — whether it is novel logic or a composition of existing tools. A composed tool is saved to `tools/` like any other and becomes reusable.

3. **EXECUTE** — run the tool (reused or freshly generated+validated) via `bb eval tools/<name>.clj` with bound args. stdout/stderr captured as observation. If SCI limit hit → spawn `clojure -M` subprocess as escape hatch.

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

`session-trace` is the core runtime data structure — an append-only sequence of typed event maps representing everything that happened in a session.

```clojure
;; session-trace: a vector of typed event maps (append-only)
[{:type :user-input  :text "fetch the title of https://example.com"}
 {:type :plan        :tasks [{:name "fetch" :doc "Fetch HTML"} {:name "parse" :doc "Extract title"}]}
 {:type :think       :text "Deciding how to proceed..." :task "fetch"}
 {:type :reuse       :tool :fetch-url :task "fetch"}
 {:type :execute     :tool :fetch-url :args {:url "https://example.com"} :task "fetch"}
 {:type :observe     :tool :fetch-url :result "<html>…</html>" :task "fetch"}
 {:type :think       :text "Deciding how to proceed..." :task "parse"}
 {:type :generate    :tool :parse-title :task "parse"}
 {:type :validate    :tool :parse-title :status :ok :task "parse"}
 {:type :execute     :tool :parse-title :args {:html "<html>…</html>"} :task "parse"}
 {:type :observe     :tool :parse-title :result "Example Domain" :task "parse"}
 {:type :assistant-out :text "Example Domain"}]
```

Every `emit!` call appends to `session-trace`. The trace is the single source of truth for replay, debugging, and LLM context assembly.

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
| `:plan` | Planner decomposes goal | `:tasks` |
| `:think` | LLM decides reuse/generate/compose | `:text`, `:task` |
| `:reuse` | LLM selects existing tool | `:tool`, `:task` |
| `:generate` | LLM writes new tool | `:tool`, `:task` |
| `:validate` | Spec check result | `:tool`, `:status`, `:attempt`, `:message`, `:task` |
| `:execute` | Tool runs (bb eval subprocess) | `:tool`, `:args`, `:task` |
| `:observe` | Tool result captured | `:tool`, `:result`, `:task` |
| `:plan-amend` | Agent revises the plan mid-execution | `:reason`, `:old-tasks`, `:new-tasks` |
| `:assistant-out` | Final answer to user | `:text` |
| `:error` | Any failure | `:text` |
| `:ask` | Agent needs human clarification | `:text` |

---

## LLM Context Assembly

`llm-context` is a pure function that assembles the full API payload for a given LLM call — system prompt plus the relevant slice of `session-trace` rendered as messages.

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
| `:think` | ReAct THINK instructions | file-context + user-input + plan + plan-amend history + tool-index + last `:observe` |
| `:generate` | Code generation instructions | last `:think` decision only |

The pipeline is: **select** (filter session-trace by relevant event types) → **truncate** (cap each `:file-context` entry at 8 000 chars; cap `:observe` results at 4 000 chars) → **render** (format as string). No embedding, no vector search.

---

## Project Structure

```
botashka/
├── bb.edn                        ← live plan (agent writes here)
├── src/
│   ├── botashka/core.clj         ← start! loop, emit!, format-event, run-tool, session-trace atom
│   ├── botashka/planner.clj      ← LLM → bb.edn task writer
│   ├── botashka/react.clj        ← ReAct loop (think/generate/execute/observe)
│   ├── botashka/tools.clj        ← tool lib management (load/save/list)
│   ├── botashka/spec.clj         ← spec validation helpers
│   ├── botashka/llm.clj          ← Claude API client (non-streaming v1)
│   └── botashka/llm_context.clj  ← context assembly — llm-context pure fn + filter pipelines
└── tools/
    ├── tool-index.edn            ← tool registry (name → description + specs)
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
| Tool selection | LLM reads index | Keeps decision in one place, no embedding overhead |
| Validation | clojure.spec | Machine-checkable, instruments agent's own tools |
| Planning substrate | bb.edn = human layer; in-memory EDN = execution layer | bb.edn is the human-editable window; subagents run from data, not files |
| Plan mutation | Allowed, but only via explicit `:plan-amend` event | Silent rewrites lose reasoning chain; `:plan-amend` makes history queryable |
| Subagent task lists | In-memory EDN map passed to `react-step!` loop | No bb.edn needed; `--config` available as opt-in for human-inspectable subagents |
| Runtime data structure | `session-trace` (append-only event vector) | Single source of truth for replay, debug, and context assembly |
| Context assembly | `llm-context` pure fn in `llm_context.clj` | Selects relevant trace slice per LLM call — no embedding, no vector search |
| Communication | `emit!` function (v1 println → nREPL `:out` in v2) | Single seam — transport swappable without touching agent core |
| LLM v1 | Non-streaming `complete` wrapping SSE | Simple for v1; `stream-chat` is the v2 upgrade path |
| V1 UI | `bb repl` + `start!` loop | Zero TUI code; `emit!` is the seam for future adapters |

---

## Future UI: nREPL Transport

The preferred v2 UI path is **nREPL**, not a custom TUI. When Babashka runs `bb nrepl-server`, any nREPL client (CIDER, Calva, vim-iced, a custom terminal client) connects and receives Botashka output as standard nREPL `:out` messages — one per `println`. This means:

- **Editor integration is free.** CIDER/Calva users get Botashka inline in their editor with zero extra code — just `(b/start!)` in the REPL.
- **Custom TUI becomes a thin nREPL client.** It connects to the nREPL server and renders `:out` messages. No custom protocol, no channel plumbing at the UI layer.
- **Any nREPL client is a valid Botashka UI** — the protocol is already defined.

### nREPL streaming is built in — no extensions needed

nREPL's protocol is inherently message-based and async. During a single `eval` request, the server sends multiple response messages as they are produced:

| Message | When sent |
|---|---|
| `{:out "…"}` | One per `println`/`flush` — delivered immediately |
| `{:err "…"}` | stderr |
| `{:value "…"}` | Final return value |
| `{:status "done"}` | Completion sentinel |

For Botashka: each `(emit! event)` → `(println …)` → one `:out` message pushed to the client in real time. For token-level LLM streaming, each `(print token)(flush)` → one `:out` message per token. **No protocol extensions, custom middleware, or new ops required.**

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

V2 `emit!` swaps the body for explicit session dispatch — zero other changes in the codebase:

```clojure
;; V2 — explicit nREPL session dispatch
(defn emit! [event]
  (nrepl.transport/send *session* {:op "out" :out (format-event event)}))
```

For token streaming in v2, bind `*out*` in the `future` to the nREPL session's output stream — nREPL 1.3 handles this reliably:

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
