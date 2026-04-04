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
- Clean channel abstraction so future UIs (Telegram, WeChat) require zero agent core changes

---

## Architecture

### Four Zones

1. **Human / TUI** — stdin/stdout terminal interface. Communicates with agent via core.async channels.
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
5. Agent can amend the plan mid-execution by rewriting `bb.edn` and re-running

Each task entry has a `:doc` string describing its purpose, making `bb.edn` a human-readable progress log.

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

---

## ReAct Loop

Each `bt/run` call triggers the ReAct loop for that task step:

1. **THINK** — LLM receives: task context + `tools/tool-index.edn` (all tool descriptions) + conversation history. Outputs: reasoning + decision — reuse existing tool, generate new tool, or ask human.

2. **ACT (reuse)** — Load `tools/<name>.clj`, bind args, execute in SCI directly.  
   **ACT (generate)** — LLM writes a `.clj` snippet with a description comment, `:requires`, and clojure.spec definitions for input and output.

3. **VALIDATE** (generated tools only) — `spec/conform` on the new tool with sample inputs. Failure → error fed back to LLM for retry (max 3 attempts). Pass → tool saved to `tools/`, entry appended to `tool-index.edn`.

4. **EXECUTE** — `bb eval tools/<name>.clj` with bound args. stdout/stderr captured as observation. If SCI limit hit → spawn `clojure -M` subprocess as escape hatch.

5. **OBSERVE** — result returned to LLM context. Loop continues until the task step is complete or needs human input.

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
 :write-csv {:description "Write a seq of maps to a CSV file at path"
             :spec-in     "map with :rows (seq of maps) and :path (string)"
             :spec-out    "string? (absolute path written)"}}
```

The LLM always receives `tool-index.edn` in its context. After generating a new tool, the agent appends its entry to the index before saving the `.clj` file.

### Tool lifecycle

- **Create** — generated by LLM, validated by spec, saved to `tools/`
- **Reuse** — LLM selects from index by reading descriptions; no embedding/search needed
- **Promote** — tool survives across sessions by being committed to git
- **Deprecate** — entry removed from `tool-index.edn` (file kept for reference)

---

## Channel Layer

Four core.async channels form the communication bus:

| Channel | Direction | Purpose |
|---|---|---|
| `:human-in` | stdin → agent | user input, commands, confirmations |
| `:human-out` | agent → stdout/TUI | progress, results, questions (including token stream) |
| `:llm-req` / `:llm-resp` | agent ↔ Claude API | queued, rate-limit aware, streaming |
| `:tool-exec` | ReAct loop → executor | fire tool, receive result |

V1 ships TUI only (stdin/stdout). Telegram/WeChat adapters slot in later by putting/taking on `:human-in`/`:human-out` — zero agent core changes required.

### Streaming Protocol

Claude API responses stream as SSE (server-sent events). Botashka handles this with `future` (a real JVM thread, available in Babashka) reading the chunked HTTP response and putting token messages onto `:human-out`:

```clojure
;; botashka/llm.clj — streaming LLM response onto channel
(future
  (let [resp (http/post api-url {:body payload :as :stream})]
    (doseq [chunk (line-seq (io/reader (:body resp)))]
      (when-let [token (parse-sse-token chunk)]
        (async/>!! human-out-ch {:type :token :text token})))
    (async/>!! human-out-ch {:type :done})))
```

`future` is used instead of a `go` block because reading from a Java `InputStream` is blocking I/O — a `go` block would starve the core.async thread pool. `future` gives a dedicated JVM thread safe for blocking ops.

The `:human-out` channel carries a simple message protocol:

| Message | Meaning |
|---|---|
| `{:type :token :text "..."}` | Next streamed token — print without newline |
| `{:type :done}` | Response complete — print newline, re-show prompt |
| `{:type :info :text "..."}` | Non-streamed status message (tool execution, plan step, etc.) |
| `{:type :ask :text "..."}` | Agent needs human input — TUI prompts and puts reply on `:human-in` |

The TUI adapter (`botashka/tui.clj`) runs a go-loop reading from `:human-out` and handles each message type — printing tokens inline for streaming, full lines for info/ask messages. Future UI adapters (Telegram, WeChat) implement the same protocol against the same channels.

---

## Project Structure

```
botashka/
├── bb.edn                    ← live plan (agent writes here)
├── src/
│   ├── botashka/core.clj     ← entry point, channel bus, main loop
│   ├── botashka/planner.clj  ← LLM → bb.edn task writer
│   ├── botashka/react.clj    ← ReAct loop (think/act/observe)
│   ├── botashka/tools.clj    ← tool lib management (load/save/list)
│   ├── botashka/spec.clj     ← spec validation helpers
│   ├── botashka/llm.clj      ← Claude API client
│   └── botashka/tui.clj      ← stdin/stdout TUI adapter
└── tools/
    ├── tool-index.edn        ← tool registry (name → description + specs)
    ├── fetch-url.clj
    ├── write-csv.clj
    └── ...
```

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Runtime | Babashka (SCI) | Fast startup, no JVM overhead, real Clojure |
| JVM escalation | `clojure -M` subprocess | Escape hatch for SCI limits, explicit boundary |
| Action space | Generated Clojure snippets | CodeAct: more expressive than JSON tool calls |
| Tool selection | LLM reads index | Keeps decision in one place, no embedding overhead |
| Validation | clojure.spec | Machine-checkable, instruments agent's own tools |
| Planning | bb.edn tasks + `bt/run` | Human-inspectable, editable, dep-ordered |
| Communication | core.async channels | UI-agnostic, backpressure, composable |
| V1 UI | TUI (stdin/stdout) | Simplest first; channel abstraction enables future adapters |

---

## Out of Scope (V1)

- Telegram / WeChat adapters
- Subagent parallelism via channels
- Tool versioning / rollback
- Embedding-based tool search
