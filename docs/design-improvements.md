# Botashka — Design Improvement Suggestions

Taking a step back from the review/response cycle and looking at the design holistically. These are the improvements worth making *before* implementation starts — not more review debate, just concrete gaps and fixes.

---

## 1. The LLM Response Contract Is Missing (Critical)

The spec specifies what goes *into* every LLM call (system prompt + selected trace events), but never specifies what comes back. THINK, GENERATE, and PLAN all call the LLM — but the expected output format is nowhere defined.

What does a THINK response look like? Does it return:

```clojure
;; Option A — free text + structured decision embedded
"I'll reuse fetch-url since it already handles HTTP GET requests.
DECISION: {:action :reuse :tool :fetch-url}"

;; Option B — pure structured EDN
{:reasoning "fetch-url already handles this"
 :action :reuse
 :tool :fetch-url}

;; Option C — tagged block in prose
<decision>
{:action :reuse :tool :fetch-url}
</decision>
```

Without a specified output contract, the parser in `react.clj` will be ad-hoc — and every LLM quirk (extra whitespace, markdown fencing, unicode quotes) becomes a parsing failure. This is the highest-probability source of bugs in the entire system.

**Recommendation:** Define a response schema for each LLM call type and add it to the spec. A simple approach for Babashka: instruct the LLM to wrap the structured part in `<edn>...</edn>` tags, parse with a regex + `edn/read-string`. This is a one-line extraction and survives reasoning prose before/after the tag.

```clojure
;; THINK response contract
;; LLM wraps structured output in <edn>...</edn>
;; Prose reasoning is allowed before and after — it's logged as :think :text
{:action  :reuse | :generate | :compose
 :tool    keyword?      ;; for :reuse
 :name    string?       ;; for :generate/:compose — suggested tool name
 :reason  string?}      ;; brief justification

;; PLAN response contract
{:tasks [{:name string? :doc string? :depends [keyword?]}]}

;; GENERATE response contract — the tool source is the entire response body
;; (no wrapper needed — the whole response IS the .clj file content)
```

---

## 2. `*result*` Needs a Concrete Replacement

The spec acknowledges `*result*` doesn't survive `bb -f` and says "use file passing" — but never specifies what the file passing mechanism actually is. This is not an implementation detail; it is the inter-task composition model. Leaving it as "TBD" means every task author (the LLM) will invent their own convention.

**Recommendation:** Define a session scratch map — a single EDN file at `sessions/<id>/scratch.edn` that the executor manages. After each task completes, the executor writes the result under the task's name key. The next task reads it by name.

```clojure
;; sessions/<id>/scratch.edn — managed by executor, never by tools directly
{:fetch-notes "<html>...</html>"
 :extract-ids [1234567 2345678]}
```

The executor injects the scratch path as a bound var or CLI arg. Tools read their inputs from scratch and write their outputs back. This is deterministic, inspectable, and survives subprocess boundaries.

Drop `*result*` from the design entirely — it's a leaky abstraction that works in exactly one narrow case (linear two-task plans where both tasks run in the same process). The scratch map works in all cases.

---

## 3. `llm-context` Return Type Is Wrong

The spec says `llm-context` returns `{:system <string> :messages <string>}` where `:messages` is a plain string. But the Anthropic API `messages` field takes an array of `{:role :content}` objects:

```clojure
;; What the spec says llm-context returns:
{:system   "You are a ReAct agent..."
 :messages "User input: fetch example.com\nPlan: [...]\nLast result: ..."}

;; What the Anthropic API actually needs:
{:system   "You are a ReAct agent..."
 :messages [{:role "user"    :content "fetch example.com"}
            {:role "assistant" :content "..."}
            {:role "user"    :content "[Last result]: ..."}]}
```

Passing a raw string as `:messages` would require the `llm.clj` caller to re-parse it — defeating the purpose of `llm-context` as the assembly point.

**Recommendation:** Change the return type to `{:system string? :messages [{:role string? :content string?}]}`. The `render-trace` step should produce the messages array directly, not a flat string. This is cleaner and maps directly to the API without an intermediate serialization step.

A natural rendering:
- All trace events up to the current one → `{:role "user" :content <rendered>}` (single user message)
- Or interleaved user/assistant turns if the trace has prior `:assistant-out` events

For v1, the simplest correct form is a single user message containing all rendered context — clean, unambiguous, and what the API expects.

---

## 4. Dangling Numbered List in Planning Layer

After removing the ASCII planning diagram, the prose introduction was replaced but the numbered list below it was left starting at `2.`:

```
The agent uses bb.edn as its live, human-readable plan...

2. Agent writes these as :tasks entries into bb.edn...
3. Human can inspect and edit bb.edn...
4. Agent executes each task via (babashka.tasks/run 'task-name)...
5. Agent can amend the plan mid-execution...
```

Point 1 disappeared with the diagram. Fix: convert to a prose paragraph or renumber from 1.

---

## 5. SCI Whitelist Belongs in the Spec Now

The pre-implementation fix list (from `challenges-response.md`) includes:

> **SCI namespace whitelist** — declare which namespaces are accessible in validation context; exclude FS/network; add execution timeout.

This decision was accepted in round 2, but it's still not in the spec. The spec currently says:

> `sci/eval-string` evaluates the source in Babashka's native SCI (where `clojure.spec.alpha` is available)

This needs to be the complete whitelist. A reasonable v1 baseline:

```clojure
;; Allowed in the SCI validation context (read-only, no side effects)
#{clojure.core
  clojure.spec.alpha
  clojure.string
  clojure.set
  clojure.edn}
;; Explicitly excluded: babashka.fs, babashka.process, babashka.http-client,
;;                      clojure.java.io, java.io, java.net, System
;; Timeout: 5s
```

Note: the execution context (actual `bb -f` run) is unrestricted because it runs as the developer's own process — the whitelist applies only to the *validation* step.

---

## 6. `render-trace` Output Format Is Unspecified

The spec says `render-trace` "formats selected events as a string" — but provides no example of what that string looks like. Since this string becomes the LLM's context for every reasoning step, the format is not an implementation detail; it directly shapes LLM behaviour.

**Recommendation:** Add a concrete rendering example per event type. A minimal, information-dense format:

```
[user-input] fetch the title of https://example.com

[plan]
  1. fetch — Fetch HTML from URL
  2. parse — Extract <title> from HTML

[think:fetch] Reusing fetch-url — it already handles HTTP GET.

[execute:fetch] fetch-url {:url "https://example.com"}
[observe:fetch] <html><head><title>Example Domain</title>...

[think:parse] Generating parse-title — no existing tool extracts HTML titles.

[tool-index]
  :fetch-url  — Fetch the body of an HTTP URL as a string
  :write-file — Write a string to a file at the given path
```

The format should be:
- **Deterministic** — same input, same output every time
- **Tag-prefixed** — `[type:task]` prefix makes the event type and task unambiguous to the LLM
- **Compact** — truncated payloads get a `… (truncated)` suffix, not silent loss

---

## 7. Planner Calling Convention Is Inconsistent

`plan-amend!` takes `session-trace` and `emit!` as separate positional args:

```clojure
(defn plan-amend! [reason old-task-names api-key bb-edn-path session-trace emit!] ...)
```

But the rest of the design passes context as a map. This function has 6 positional args — it will be painful to call, easy to mis-order, and impossible to extend without breaking callers.

**Recommendation:** introduce a simple context map threaded through the agent core:

```clojure
;; Agent context — passed to all core fns
{:api-key      "..."
 :bb-edn-path  "bb.edn"
 :session-id   "2026-04-04T14:32:00"
 :scratch-path "sessions/2026-04-04T14:32:00/scratch.edn"
 :emit!        (fn [event] ...)
 :trace        (atom [])}
```

Every function that currently takes `api-key`, `bb-edn-path`, `session-trace`, and `emit!` as separate args takes `ctx` instead. This is the idiomatic Clojure pattern for threading configuration + side-effecting state through a pipeline without a monad.

```clojure
;; Before
(defn plan-amend! [reason old-tasks api-key bb-edn-path session-trace emit!] ...)

;; After
(defn plan-amend! [ctx reason old-tasks] ...)
```

This also makes `append-event!` consistent — it takes `ctx` and mutates `(:trace ctx)`.

---

## 8. The 7-Item Fix List Should Live in the Spec

The pre-implementation fix list is the most actionable output of the entire review cycle. It currently lives only in `challenges-response.md`. Since the spec is the implementation contract, the fix list should appear there — either as a "Known Gaps" section or folded directly into the relevant sections as `> **v1 requirement:**` callouts.

Having the spec and the fix list in separate files means implementers can read the spec and miss the constraints.

---

## Summary Table

| # | Issue | Where | Impact | Fix complexity |
|---|---|---|---|---|
| 1 | LLM response contract unspecified | `spec.md` | High — parsing is ad-hoc | Add response schema per call type |
| 2 | `*result*` composition model broken | `spec.md` | High — inter-task data flow undefined | Replace with scratch map |
| 3 | `llm-context` returns wrong type for API | `spec.md` | High — API call will fail | Change `:messages` to `[{:role :content}]` |
| 4 | Dangling numbered list (starts at 2) | `spec.md` | Low — cosmetic | Fix list numbering |
| 5 | SCI whitelist accepted but not written | `spec.md` | Medium — safety claim hollow | Add whitelist + timeout to spec |
| 6 | `render-trace` format unspecified | `spec.md` | Medium — LLM quality depends on it | Add rendering example |
| 7 | Planner calling convention has 6 positional args | `spec.md` | Medium — fragile API | Introduce `ctx` map |
| 8 | Fix list not in spec | `spec.md` | Medium — implementers miss constraints | Add "Known Gaps" section |

Items 1, 2, and 3 should be fixed in the spec before any code is written. The rest are improvements that will pay off during implementation.
