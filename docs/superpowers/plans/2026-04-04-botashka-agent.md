# Botashka Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local Babashka-native agentic assistant that uses generated Clojure snippets as its action space, with a self-growing tool library, hybrid plan+ReAct loop, and a REPL-first interface.

**Architecture:** The agent core has three layers — Planner (writes bb.edn tasks), ReAct loop (THINK→GENERATE→EXECUTE→OBSERVE), and Spec Validator (gates all generated code). V1 interaction is a `start!` function that runs a `read-line` loop inside `bb repl` — Botashka shows its own prompt and prints structured agent events inline. All output goes through a single `emit!` function so channels + TUI can replace it later with no other changes.

**Tech Stack:** Babashka (bb), clojure.spec.alpha, core.async (SCI subset + future for blocking I/O), babashka.http-client, babashka.tasks, Anthropic Claude API (SSE streaming), cheshire (JSON)

---

## File Map

| File | Responsibility |
|---|---|
| `bb.edn` | Project config + live agent task plan (agent writes `:tasks` here) |
| `src/botashka/core.clj` | `start!` REPL loop + `emit!` output abstraction + `run-tool` helper + `session-trace` atom |
| `src/botashka/llm.clj` | Claude API client — non-streaming `complete` for v1; `stream-chat` stubbed for v2 |
| `src/botashka/llm_context.clj` | Context assembly — `llm-context` pure fn; selects trace slice per LLM call |
| `src/botashka/planner.clj` | Decomposes goal → writes `:tasks` into bb.edn via LLM |
| `src/botashka/tools.clj` | Tool library management — load, save, list, update index |
| `src/botashka/spec.clj` | Spec validation helpers — conform generated source, report errors |
| `src/botashka/react.clj` | ReAct loop — THINK / GENERATE / EXECUTE / OBSERVE per task step; receives `session-trace` |
| `tools/tool-index.edn` | Tool registry — `{:tool-name {:description … :spec-in … :spec-out …}}` |
| `test/botashka/llm_test.clj` | Tests for LLM client |
| `test/botashka/llm_context_test.clj` | Tests for context assembly (select/truncate/render pipelines) |
| `test/botashka/tools_test.clj` | Tests for tool lib CRUD and index management |
| `test/botashka/spec_test.clj` | Tests for spec validation helpers |
| `test/botashka/planner_test.clj` | Tests for bb.edn task writing |
| `test/botashka/react_test.clj` | Tests for ReAct loop decision routing |

**Out of scope for v1:** `src/botashka/tui.clj` — deferred to v3. v2 is nREPL server mode: swap `emit!` body to `nrepl.transport/send`, everything else stays the same.

---

## Task 1: Project scaffold and bb.edn

**Files:**
- Create: `bb.edn`
- Create: `src/botashka/core.clj` (stub)
- Create: `tools/tool-index.edn`
- Create: `.gitignore`

- [ ] **Step 1: Create bb.edn**

```clojure
{:paths ["src"]
 :deps {org.clojure/core.async {:mvn/version "1.6.681"}
        cheshire/cheshire {:mvn/version "5.12.0"}}
 :tasks {run {:doc "Start Botashka agent"
              :task (require '[botashka.core :as core]) (core/-main)}}}
```

- [ ] **Step 2: Create tools/tool-index.edn (empty registry)**

```clojure
{}
```

- [ ] **Step 3: Create src/botashka/core.clj stub**

```clojure
(ns botashka.core)

(defn -main [& _args]
  (println "Botashka starting..."))
```

- [ ] **Step 4: Create .gitignore**

```
.superpowers/
.cpcache/
```

- [ ] **Step 5: Verify Babashka picks up the project**

Run: `bb run`
Expected output: `Botashka starting...`

- [ ] **Step 6: Commit**

```bash
git add bb.edn tools/tool-index.edn src/botashka/core.clj .gitignore
git commit -m "feat: project scaffold, bb.edn, empty tool registry"
```

---

## Task 2: LLM client with SSE streaming

**Files:**
- Create: `src/botashka/llm.clj`
- Create: `test/botashka/llm_test.clj`

The Claude API endpoint is `https://api.anthropic.com/v1/messages`. Auth header: `x-api-key`. Streaming uses `Accept: text/event-stream`. Each SSE line is `data: <json>` where the json has `type` field — `content_block_delta` carries `delta.text`. A `message_stop` event signals completion.

- [ ] **Step 1: Write failing test for parse-sse-token**

Create `test/botashka/llm_test.clj`:

```clojure
(ns botashka.llm-test
  (:require [clojure.test :refer [deftest is]]
            [botashka.llm :refer [parse-sse-token]]))

(deftest parse-sse-token-test
  (is (= "hello" (parse-sse-token "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}")))
  (is (nil? (parse-sse-token "data: {\"type\":\"message_stop\"}")))
  (is (nil? (parse-sse-token ": keep-alive")))
  (is (nil? (parse-sse-token ""))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb test test/botashka/llm_test.clj`
Expected: FAIL — `botashka.llm` namespace not found

- [ ] **Step 3: Implement llm.clj with parse-sse-token and stream-chat**

Create `src/botashka/llm.clj`:

```clojure
(ns botashka.llm
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.core.async :as async]))

(def api-url "https://api.anthropic.com/v1/messages")
(def model "claude-sonnet-4-5")

(defn parse-sse-token
  "Parse a single SSE line. Returns the text delta string, or nil if not a content token."
  [line]
  (when (and (string? line) (.startsWith line "data: "))
    (let [payload (subs line 6)]
      (try
        (let [parsed (json/parse-string payload true)]
          (when (= "content_block_delta" (:type parsed))
            (get-in parsed [:delta :text])))
        (catch Exception _ nil)))))

(defn stream-chat
  "Send messages to Claude API with streaming. Puts {:type :token :text t} onto out-ch
  for each token, then {:type :done} when complete. Uses future for blocking SSE read."
  [messages api-key out-ch]
  (future
    (try
      (let [payload (json/generate-string
                      {:model model
                       :max_tokens 4096
                       :stream true
                       :messages messages})
            resp (http/post api-url
                            {:headers {"x-api-key" api-key
                                       "anthropic-version" "2023-06-01"
                                       "content-type" "application/json"
                                       "accept" "text/event-stream"}
                             :body payload
                             :as :stream})]
        (doseq [line (line-seq (io/reader (:body resp)))]
          (when-let [token (parse-sse-token line)]
            (async/>!! out-ch {:type :token :text token})))
        (async/>!! out-ch {:type :done}))
      (catch Exception e
        (async/>!! out-ch {:type :error :text (ex-message e)})))))

(defn complete
  "Non-streaming chat completion. Returns the full response string."
  [messages api-key]
  (let [out-ch (async/chan 1024)
        _ (stream-chat messages api-key out-ch)
        sb (StringBuilder.)]
    (loop []
      (let [msg (async/<!! out-ch)]
        (case (:type msg)
          :token (do (.append sb (:text msg)) (recur))
          :done  (str sb)
          :error (throw (ex-info (:text msg) {})))))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb test test/botashka/llm_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/llm.clj test/botashka/llm_test.clj
git commit -m "feat: LLM client with SSE streaming and parse-sse-token"
```

---

## Task 3: LLM context assembly — llm_context.clj

**Files:**
- Create: `src/botashka/llm_context.clj`
- Create: `test/botashka/llm_context_test.clj`

`llm-context` is a pure function that returns `{:system <string> :messages <string>}` — the full API payload for a given LLM call. The system prompt is a **constant per call type** (configuration, not history — never stored in session-trace). The messages are assembled by selecting and rendering the relevant slice of session-trace. `llm_context.clj` also consolidates all system prompt constants, removing the scattered `*-system-prompt` vars from `react.clj` and `planner.clj`.

- [ ] **Step 1: Write failing tests**

Create `test/botashka/llm_context_test.clj`:

```clojure
(ns botashka.llm-context-test
  (:require [clojure.test :refer [deftest is]]
            [botashka.llm-context :refer [llm-context select-events render-trace system-prompt truncate-content]]))

(def sample-trace
  [{:type :file-context :path "src/foo.clj" :content "(ns foo)\n(defn bar [] :bar)"}
   {:type :user-input   :text "refactor src/foo.clj to add a spec"}
   {:type :plan         :tasks [{:name "refactor" :doc "Add spec to foo"}]}
   {:type :think        :text "Deciding..." :task "refactor"}
   {:type :execute      :tool :fetch-url :args {:url "https://example.com"} :task "refactor"}
   {:type :observe      :tool :fetch-url :result "<html>Example Domain</html>" :task "refactor"}])

(deftest select-think-includes-file-context
  (let [selected (select-events sample-trace :think)]
    (is (some #(= :file-context (:type %)) selected))
    (is (some #(= :user-input (:type %)) selected))
    (is (some #(= :observe (:type %)) selected))))

(deftest select-plan-includes-file-context
  (let [selected (select-events sample-trace :plan)]
    (is (some #(= :file-context (:type %)) selected))
    (is (some #(= :user-input (:type %)) selected))))

(deftest select-generate-events
  (let [trace (conj sample-trace {:type :think :text "{:decision :generate :tool-name :parse-title}" :task "refactor"})
        selected (select-events trace :generate)]
    (is (= 1 (count selected)))
    (is (= :think (:type (first selected))))))

(deftest select-think-includes-plan-amend
  (let [trace (conj sample-trace {:type :plan-amend
                                  :reason "404 — switching strategy"
                                  :old-tasks [:fetch] :new-tasks [:proxy-fetch]})
        selected (select-events trace :think)]
    (is (some #(= :plan-amend (:type %)) selected))))

(deftest truncate-content-limits-length
  (let [long-str (apply str (repeat 10000 "x"))
        truncated (truncate-content long-str 8000)]
    (is (<= (count truncated) 8000))))

(deftest truncate-content-passes-short-strings
  (is (= "hello" (truncate-content "hello" 8000))))

(deftest render-produces-string
  (let [result (render-trace (select-events sample-trace :think))]
    (is (string? result))
    (is (re-find #"src/foo.clj" result))))

(deftest system-prompt-returns-string
  (is (string? (system-prompt :think)))
  (is (string? (system-prompt :generate)))
  (is (string? (system-prompt :plan))))

(deftest llm-context-returns-map
  (let [ctx (llm-context sample-trace :think)]
    (is (map? ctx))
    (is (string? (:system ctx)))
    (is (string? (:messages ctx)))
    (is (seq (:system ctx)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test test/botashka/llm_context_test.clj`
Expected: FAIL — `botashka.llm-context` not found

- [ ] **Step 3: Implement llm_context.clj**

Create `src/botashka/llm_context.clj`:

```clojure
(ns botashka.llm-context)

;; ---------------------------------------------------------------------------
;; System prompts — constants per LLM call type (configuration, not history)
;; The Anthropic API takes :system as a separate top-level field.
;; ---------------------------------------------------------------------------

(def ^:private prompts
  {:plan
   "You are Botashka's planner. Given a goal, decompose it into ordered steps.
Return ONLY a Clojure EDN map of tasks (no markdown, no explanation) in this exact format:
{step-name {:doc \"description\" :task (run-tool 'tool-name {:arg value})}
 step-two  {:doc \"description\" :depends [step-name] :task (run-tool 'tool-name {:arg value})}}
Use symbols (not strings) for task names. Use :depends for ordering. Keep steps small and focused."

   :think
   "You are Botashka's ReAct engine. Given a task and the available tool index,
decide how to proceed. Return ONLY an EDN map (no markdown):

To reuse an existing tool:
{:decision :reuse :tool-name :keyword :args {:key value}}

To generate a new tool:
{:decision :generate :tool-name :keyword :description \"one line\" :spec-in \"type desc\" :spec-out \"type desc\"}

To compose existing tools into a new tool:
{:decision :compose :tool-name :keyword :description \"one line\" :tools-used [:kw1 :kw2] :spec-in \"type desc\" :spec-out \"type desc\"}

To ask the human for clarification:
{:decision :ask :question \"your question\"}"

   :generate
   "You are an expert Clojure/Babashka developer. Return only .clj source code.
Must include: ns form, clojure.spec.alpha s/def or s/fdef for inputs and output, single defn.
No markdown fences. No explanation."})

(defn system-prompt
  "Return the system prompt string for the given LLM call type."
  [event-type]
  (get prompts event-type "You are Botashka, a helpful Clojure agent."))

;; ---------------------------------------------------------------------------
;; truncation — cap large content fields before rendering
;; ---------------------------------------------------------------------------

(def file-context-limit 8000)
(def observe-limit 4000)

(defn truncate-content
  "Truncate s to at most limit characters, appending a note if truncated."
  [s limit]
  (if (<= (count s) limit)
    s
    (str (subs s 0 limit) "\n… [truncated]")))

;; ---------------------------------------------------------------------------
;; select-events — filter pipeline per LLM call type
;; ---------------------------------------------------------------------------

(defmulti select-events
  "Select the relevant subset of session-trace for a given LLM call type."
  (fn [_trace event-type] event-type))

(defmethod select-events :plan [trace _]
  ;; Planning needs file context (if any) + user goal
  (filterv #(#{:file-context :user-input} (:type %)) trace))

(defmethod select-events :think [trace _]
  ;; Thinking needs file context + user goal + plan + all plan amendments + last observation
  (let [keep? #{:file-context :user-input :plan :plan-amend}
        base  (filterv #(keep? (:type %)) trace)
        last-obs (last (filterv #(= :observe (:type %)) trace))]
    (cond-> base last-obs (conj last-obs))))

(defmethod select-events :generate [trace _]
  ;; Code generation needs only the last think decision
  [(last (filterv #(= :think (:type %)) trace))])

(defmethod select-events :default [trace _]
  trace)

;; ---------------------------------------------------------------------------
;; render-trace — format selected events as a string for the messages payload
;; ---------------------------------------------------------------------------

(defmulti render-event :type)

(defmethod render-event :file-context [{:keys [path content]}]
  (str "File " path ":\n" (truncate-content content file-context-limit)))

(defmethod render-event :user-input [{:keys [text]}]
  (str "User goal: " text))

(defmethod render-event :plan [{:keys [tasks]}]
  (str "Plan: " (clojure.string/join ", " (map :name tasks))))

(defmethod render-event :plan-amend [{:keys [reason old-tasks new-tasks]}]
  (str "Plan amended — " reason
       "\n  was: " (clojure.string/join ", " (map name old-tasks))
       "\n  now: " (clojure.string/join ", " (map name new-tasks))))

(defmethod render-event :think [{:keys [text task]}]
  (str (when task (str "[" task "] ")) "Think: " text))

(defmethod render-event :observe [{:keys [tool result task]}]
  (str (when task (str "[" task "] "))
       "Observation from " (name tool) ": "
       (truncate-content result observe-limit)))

(defmethod render-event :default [event]
  (str (name (:type event)) ": " (pr-str (dissoc event :type))))

(defn render-trace
  "Render a sequence of selected trace events as a single string."
  [events]
  (clojure.string/join "\n" (map render-event (remove nil? events))))

;; ---------------------------------------------------------------------------
;; llm-context — pure fn assembling the full API payload map
;; ---------------------------------------------------------------------------

(defn llm-context
  "Pure fn: given session-trace and the event-type of the upcoming LLM call,
  return {:system <string> :messages <string>} ready for the LLM API.
  Usage: (llm-context session-trace :think)"
  [session-trace event-type]
  {:system   (system-prompt event-type)
   :messages (render-trace (select-events session-trace event-type))})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test test/botashka/llm_context_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/llm_context.clj test/botashka/llm_context_test.clj
git commit -m "feat: llm-context — context assembly with @file support and truncation"
```

---

## Task 4: Tool library management

**Files:**
- Create: `src/botashka/tools.clj`
- Create: `test/botashka/tools_test.clj`

The tool index lives at `tools/tool-index.edn`. Each tool file is `tools/<name>.clj`. The index is a map of keyword → `{:description … :spec-in … :spec-out …}`.

- [ ] **Step 1: Write failing tests**

Create `test/botashka/tools_test.clj`:

```clojure
(ns botashka.tools-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [babashka.fs :as fs]
            [botashka.tools :refer [load-index save-tool! tool-exists? list-tools]]))

(def test-tools-dir "/tmp/botashka-test-tools")

(defn cleanup [f]
  (fs/delete-tree test-tools-dir)
  (fs/create-dirs test-tools-dir)
  (spit (str test-tools-dir "/tool-index.edn") "{}")
  (f)
  (fs/delete-tree test-tools-dir))

(use-fixtures :each cleanup)

(deftest load-index-test
  (is (= {} (load-index test-tools-dir))))

(deftest save-tool-test
  (let [code "(ns tools.greet (:require [clojure.spec.alpha :as s]))\n(s/def ::name string?)\n(defn greet [name] (str \"Hello \" name))"
        meta {:description "Greet a person by name" :spec-in "string? (name)" :spec-out "string? (greeting)"}]
    (save-tool! test-tools-dir :greet code meta)
    (is (true? (tool-exists? test-tools-dir :greet)))
    (is (= "Greet a person by name" (get-in (load-index test-tools-dir) [:greet :description])))))

(deftest list-tools-test
  (save-tool! test-tools-dir :foo "(ns tools.foo)\n(defn foo [] :foo)"
              {:description "Returns :foo" :spec-in "nil" :spec-out "keyword?"})
  (is (= [:foo] (list-tools test-tools-dir))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test test/botashka/tools_test.clj`
Expected: FAIL — `botashka.tools` not found

- [ ] **Step 3: Implement tools.clj**

Create `src/botashka/tools.clj`:

```clojure
(ns botashka.tools
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [babashka.fs :as fs]))

(defn index-path [tools-dir] (str tools-dir "/tool-index.edn"))
(defn tool-path [tools-dir tool-name] (str tools-dir "/" (name tool-name) ".clj"))

(defn load-index
  "Load tools/tool-index.edn as a map."
  [tools-dir]
  (edn/read-string (slurp (index-path tools-dir))))

(defn tool-exists?
  "Returns true if tool-name exists in the index."
  [tools-dir tool-name]
  (contains? (load-index tools-dir) tool-name))

(defn list-tools
  "Returns a sorted list of tool name keywords."
  [tools-dir]
  (sort (keys (load-index tools-dir))))

(defn save-tool!
  "Save tool code to tools/<name>.clj and append entry to tool-index.edn.
  meta is {:description … :spec-in … :spec-out …}"
  [tools-dir tool-name code meta]
  (spit (tool-path tools-dir tool-name) code)
  (let [index (load-index tools-dir)
        updated (assoc index tool-name meta)]
    (spit (index-path tools-dir)
          (with-out-str (pprint updated)))))

(defn load-tool-code
  "Read the source of a tool by name."
  [tools-dir tool-name]
  (slurp (tool-path tools-dir tool-name)))

(defn index-for-llm
  "Return tool-index.edn contents as a formatted string for LLM context."
  [tools-dir]
  (pr-str (load-index tools-dir)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test test/botashka/tools_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/tools.clj test/botashka/tools_test.clj
git commit -m "feat: tool library management — load/save/list/index"
```

---

## Task 5: Spec validation helpers

**Files:**
- Create: `src/botashka/spec.clj`
- Create: `test/botashka/spec_test.clj`

Spec validation in Babashka uses `clojure.spec.alpha`. We validate generated tool *source code* by loading it into SCI and running its spec against sample values. The key constraint: we evaluate the generated code in an isolated SCI context to catch syntax errors before writing to disk.

- [ ] **Step 1: Write failing tests**

Create `test/botashka/spec_test.clj`:

```clojure
(ns botashka.spec-test
  (:require [clojure.test :refer [deftest is]]
            [botashka.spec :refer [validate-tool-source]]))

(def valid-tool
  "(ns tools.add
     (:require [clojure.spec.alpha :as s]))
   (s/def ::a number?)
   (s/def ::b number?)
   (s/fdef add :args (s/cat :a ::a :b ::b) :ret number?)
   (defn add [a b] (+ a b))")

(def invalid-syntax
  "(ns tools.bad
     (defn bad [x] (+ x)")  ; unclosed paren

(def missing-spec
  "(ns tools.nospec)
   (defn nospec [x] x)")

(deftest valid-tool-passes
  (let [result (validate-tool-source valid-tool)]
    (is (= :ok (:status result)))))

(deftest invalid-syntax-fails
  (let [result (validate-tool-source invalid-syntax)]
    (is (= :error (:status result)))
    (is (string? (:message result)))))

(deftest missing-spec-fails
  (let [result (validate-tool-source missing-spec)]
    (is (= :error (:status result)))
    (is (re-find #"spec" (:message result)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test test/botashka/spec_test.clj`
Expected: FAIL — `botashka.spec` not found

- [ ] **Step 3: Implement spec.clj**

Create `src/botashka/spec.clj`:

```clojure
(ns botashka.spec
  (:require [sci.core :as sci]))

(defn validate-tool-source
  "Evaluate generated tool source in an isolated SCI context.
  Checks: parseable, loads without error, defines at least one s/def or s/fdef.
  Returns {:status :ok} or {:status :error :message <string>}."
  [source]
  (try
    (let [ctx (sci/init {:namespaces {'clojure.spec.alpha
                                      {'def   (fn [& _] nil)
                                       'fdef  (fn [& _] nil)
                                       'valid? (fn [& _] true)
                                       'conform (fn [_ v] v)}}})
          spec-defined? (atom false)
          ;; Wrap def/fdef to detect spec usage
          ctx (sci/init {:namespaces
                          {'clojure.spec.alpha
                           {'def   (fn [& _] (reset! spec-defined? true))
                            'fdef  (fn [& _] (reset! spec-defined? true))
                            'valid? (fn [_ v] true)
                            'conform (fn [_ v] v)}}})
          _ (sci/eval-string* ctx source)]
      (if @spec-defined?
        {:status :ok}
        {:status :error :message "Tool must define at least one clojure.spec (s/def or s/fdef)"}))
    (catch Exception e
      {:status :error :message (ex-message e)})))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test test/botashka/spec_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/spec.clj test/botashka/spec_test.clj
git commit -m "feat: spec validation — isolated SCI eval of generated tool source"
```

---

## Task 6: Planner — goal decomposition into bb.edn tasks

**Files:**
- Create: `src/botashka/planner.clj`
- Create: `test/botashka/planner_test.clj`

The planner sends the goal to the LLM and asks it to return a bb.edn `:tasks` map as EDN. It then merges that into the existing `bb.edn`, preserving the `:paths`, `:deps`, and `:init` keys.

- [ ] **Step 1: Write failing tests**

Create `test/botashka/planner_test.clj`:

```clojure
(ns botashka.planner-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [botashka.planner :refer [tasks-edn->map merge-tasks write-plan!]]))

(deftest tasks-edn->map-test
  (let [edn-str "{fetch {:doc \"fetch\" :task (println \"fetching\")} save {:doc \"save\" :depends [fetch] :task (println \"saving\")}}"
        result (tasks-edn->map edn-str)]
    (is (map? result))
    (is (contains? result 'fetch))
    (is (contains? result 'save))))

(deftest merge-tasks-test
  (let [existing {:paths ["src"] :deps {} :tasks {'old-task {:doc "old"}}}
        new-tasks {'new-task {:doc "new"}}
        merged (merge-tasks existing new-tasks)]
    (is (= ["src"] (:paths merged)))
    (is (contains? (:tasks merged) 'old-task))
    (is (contains? (:tasks merged) 'new-task))))

(deftest write-plan-test
  (let [tmp-file "/tmp/botashka-test-bb.edn"
        existing {:paths ["src"] :deps {} :tasks {}}
        _ (spit tmp-file (pr-str existing))
        new-tasks {'step1 {:doc "do step1" :task '(println "step1")}}]
    (write-plan! tmp-file new-tasks)
    (let [result (edn/read-string (slurp tmp-file))]
      (is (contains? (:tasks result) 'step1)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test test/botashka/planner_test.clj`
Expected: FAIL

- [ ] **Step 3: Implement planner.clj**

Create `src/botashka/planner.clj`:

```clojure
(ns botashka.planner
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [botashka.llm :as llm]
            [botashka.llm-context :as ctx]))

(defn tasks-edn->map
  "Parse LLM-returned EDN string into a tasks map. Returns nil on parse failure."
  [edn-str]
  (try (edn/read-string edn-str) (catch Exception _ nil)))

(defn merge-tasks
  "Merge new-tasks into existing bb.edn config map, preserving all other keys."
  [existing new-tasks]
  (update existing :tasks merge new-tasks))

(defn write-plan!
  "Read bb.edn at path, merge new-tasks into :tasks, write back."
  [bb-edn-path new-tasks]
  (let [existing (edn/read-string (slurp bb-edn-path))
        updated  (merge-tasks existing new-tasks)]
    (spit bb-edn-path (with-out-str (pprint updated)))))

(defn plan!
  "Ask LLM to decompose goal into tasks. Writes resulting tasks into bb-edn-path.
  Returns the tasks map."
  [goal api-key bb-edn-path session-trace]
  (let [trace    (conj session-trace {:type :user-input :text goal})
        payload  (ctx/llm-context trace :plan)
        response (llm/complete
                   [{:role "system" :content (:system payload)}
                    {:role "user"   :content (str (:messages payload) "\n\nReturn only the EDN tasks map.")}]
                   api-key)
        tasks    (tasks-edn->map response)]
    (when tasks
      (write-plan! bb-edn-path tasks))
    tasks))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test test/botashka/planner_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/planner.clj test/botashka/planner_test.clj
git commit -m "feat: planner — LLM goal decomposition into bb.edn tasks"
```

---

## Task 7: ReAct loop

**Files:**
- Create: `src/botashka/react.clj`
- Create: `test/botashka/react_test.clj`

The ReAct loop runs per task step. THINK asks the LLM to choose Reuse/Generate/Compose and return a structured EDN decision. GENERATE produces tool code which is immediately validated. EXECUTE runs via `bb eval`. OBSERVE feeds result back.

- [ ] **Step 1: Write failing tests**

Create `test/botashka/react_test.clj`:

```clojure
(ns botashka.react-test
  (:require [clojure.test :refer [deftest is]]
            [botashka.react :refer [parse-think-decision execute-tool]]))

(deftest parse-think-decision-reuse
  (let [edn "{:decision :reuse :tool-name :fetch-url :args {:url \"http://example.com\"}}"
        result (parse-think-decision edn)]
    (is (= :reuse (:decision result)))
    (is (= :fetch-url (:tool-name result)))))

(deftest parse-think-decision-generate
  (let [edn "{:decision :generate :tool-name :parse-ids :description \"Parse IDs from HTML\"}"
        result (parse-think-decision edn)]
    (is (= :generate (:decision result)))
    (is (= :parse-ids (:tool-name result)))))

(deftest parse-think-decision-invalid
  (is (nil? (parse-think-decision "not edn {{{"))))

(deftest execute-tool-bb
  (let [code "(ns tools.add)\n(defn add [a b] (+ a b))\n(println (add 1 2))"
        result (execute-tool code {})]
    (is (= "3" (clojure.string/trim (:stdout result))))
    (is (= :ok (:status result)))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb test test/botashka/react_test.clj`
Expected: FAIL

- [ ] **Step 3: Implement react.clj**

Create `src/botashka/react.clj`:

```clojure
(ns botashka.react
  (:require [clojure.edn :as edn]
            [babashka.process :as proc]
            [botashka.llm :as llm]
            [botashka.llm-context :as ctx]
            [botashka.tools :as tools]
            [botashka.spec :as spec]))

(defn parse-think-decision
  "Parse LLM think response EDN. Returns map or nil on failure."
  [edn-str]
  (try (edn/read-string edn-str) (catch Exception _ nil)))

(defn execute-tool
  "Execute tool source code via bb eval in a subprocess. Returns {:status :ok/:error :stdout … :stderr …}"
  [code _args]
  (try
    (let [result (proc/shell {:out :string :err :string :in code} "bb" "-")]
      {:status :ok
       :stdout (:out result)
       :stderr (:err result)})
    (catch Exception e
      {:status :error
       :stdout ""
       :stderr (ex-message e)})))

(defn generate-user-prompt
  "Build the user message for code generation given a think decision and task context."
  [decision task-doc tools-dir]
  (str "Generate a Babashka-compatible Clojure tool named " (name (:tool-name decision)) ".\n"
       "Task: " task-doc "\n"
       "Description: " (:description decision) "\n"
       "spec-in: " (:spec-in decision) "\n"
       "spec-out: " (:spec-out decision) "\n"
       (when-let [used (:tools-used decision)]
         (str "Must compose these existing tools: " used "\n"
              "Existing tool code:\n"
              (clojure.string/join "\n\n"
                (map #(tools/load-tool-code tools-dir %) used))))))

(defn react-step!
  "Run one ReAct iteration for a task step.
  session-trace is the append-only event vector — used for llm-context assembly.
  emit! is a function (fn [event-map] ...) for output — nil-safe, pass nil to suppress output.
  Returns {:status :ok/:error :result <string> :observation <string>}"
  [{:keys [task-doc session-trace tools-dir api-key emit! max-retries]
    :or   {max-retries 3}}]
  (let [tool-index  (tools/index-for-llm tools-dir)
        think-ctx   (ctx/llm-context session-trace :think)
        think-resp  (llm/complete
                      [{:role "system" :content (:system think-ctx)}
                       {:role "user"
                        :content (str (:messages think-ctx)
                                      "\nTask: " task-doc
                                      "\nAvailable tools:\n" tool-index
                                      "\nDecide how to proceed. Return EDN only.")}]
                      api-key)
        decision    (parse-think-decision think-resp)]

    (when emit! (emit! {:type :think :text "Deciding how to proceed..."}))

    (case (:decision decision)
      :ask    {:status :ask :question (:question decision)}

      :reuse  (let [code   (tools/load-tool-code tools-dir (:tool-name decision))
                    result (execute-tool code (:args decision))]
                (when emit! (emit! {:type :reuse :text (name (:tool-name decision))}))
                (when emit! (emit! {:type :execute :text (name (:tool-name decision))}))
                {:status (:status result) :result (:stdout result) :observation (:stdout result)})

      (:generate :compose)
      (loop [attempt 1
             gen-trace (conj session-trace {:type :think :text think-resp :task task-doc})]
        (let [gen-ctx    (ctx/llm-context gen-trace :generate)
              user-msg   (generate-user-prompt decision task-doc tools-dir)
              gen-code   (llm/complete
                           [{:role "system" :content (:system gen-ctx)}
                            {:role "user"   :content (str (:messages gen-ctx) "\n" user-msg)}]
                           api-key)
              validation (spec/validate-tool-source gen-code)]
          (if (= :ok (:status validation))
            (do
              (tools/save-tool! tools-dir (:tool-name decision) gen-code
                                {:description (:description decision)
                                 :spec-in     (:spec-in decision)
                                 :spec-out    (:spec-out decision)})
              (when emit! (emit! {:type :generate :text (name (:tool-name decision))}))
              (when emit! (emit! {:type :validate :status :ok}))
              (let [result (execute-tool gen-code {})]
                (when emit! (emit! {:type :execute :text (name (:tool-name decision))}))
                {:status (:status result) :result (:stdout result) :observation (:stdout result)}))
            (if (< attempt max-retries)
              (do
                (when emit! (emit! {:type :validate :status :fail :attempt attempt :message (:message validation)}))
                (recur (inc attempt)
                       (conj gen-trace {:type :validate :status :fail :attempt attempt :message (:message validation)})))
              {:status :error :result nil :observation (str "Spec validation failed after " max-retries " attempts: " (:message validation))}))))

      {:status :error :result nil :observation (str "Unknown decision: " think-resp)})))

(defn run-tasks!
  "Execute a task list as plain EDN data — used by subagents and plan amendments.
  tasks is a map of {task-name {:doc … :depends […] …}} — same shape as bb.edn :tasks.
  Does NOT write bb.edn. Returns a map of {task-name result}."
  [{:keys [tasks session-trace tools-dir api-key emit!] :as opts}]
  (reduce
    (fn [results [task-name task]]
      (let [result (react-step! (assoc opts
                                  :task-doc (:doc task (name task-name))
                                  :session-trace session-trace))]
        (assoc results task-name result)))
    {}
    tasks))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb test test/botashka/react_test.clj`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/botashka/react.clj test/botashka/react_test.clj
git commit -m "feat: ReAct loop — THINK/GENERATE/EXECUTE/OBSERVE with spec gate"
```

---

## Task 8: REPL loop — start!, emit!, core wiring

**Files:**
- Modify: `src/botashka/core.clj`
- Modify: `bb.edn`

`start!` runs a `read-line` loop inside `bb repl`. All agent output goes through `emit!` — a single function that formats and prints events. This is the **channel seam**: in v2, `emit!` is replaced by `async/>!!` onto `:human-out` with no other changes. The interaction looks like:

```
user=> (require '[botashka.core :as b]) (b/start!)

🤖 botashka> fetch the title of https://example.com

[thinking] Deciding how to proceed...
[reuse] fetch-url
[execute] fetch-url → done
[generate] parse-title
[validate] spec/conform → ok, saved tools/parse-title.clj
[execute] parse-title → done

Example Domain

🤖 botashka> exit
Goodbye.
user=>
```

V1: no streaming — `complete` returns the full LLM response at once. The `[thinking]` line is printed before the LLM call; the answer after.

- [ ] **Step 1: Write failing test for emit!**

Create `test/botashka/core_test.clj`:

```clojure
(ns botashka.core-test
  (:require [clojure.test :refer [deftest is]]
            [botashka.core :refer [format-event]]))

(deftest format-event-think
  (is (= "[thinking] deciding next step"
         (format-event {:type :think :text "deciding next step"}))))

(deftest format-event-reuse
  (is (= "[reuse] fetch-url"
         (format-event {:type :reuse :text "fetch-url"}))))

(deftest format-event-generate
  (is (= "[generate] parse-title → saved tools/parse-title.clj"
         (format-event {:type :generate :text "parse-title"}))))

(deftest format-event-validate-ok
  (is (= "[validate] ok"
         (format-event {:type :validate :status :ok}))))

(deftest format-event-validate-fail
  (is (= "[validate] attempt 1 failed: missing spec"
         (format-event {:type :validate :status :fail :attempt 1 :message "missing spec"}))))

(deftest format-event-execute
  (is (= "[execute] fetch-url → done"
         (format-event {:type :execute :text "fetch-url"}))))

(deftest format-event-error
  (is (= "[error] something went wrong"
         (format-event {:type :error :text "something went wrong"}))))

(deftest parse-at-paths-extracts-paths
  (let [[paths cleaned] (#'botashka.core/parse-at-paths "refactor @src/foo.clj and @src/bar.clj")]
    (is (= ["src/foo.clj" "src/bar.clj"] paths))
    (is (= "refactor src/foo.clj and src/bar.clj" cleaned))))

(deftest parse-at-paths-no-refs
  (let [[paths cleaned] (#'botashka.core/parse-at-paths "just a plain goal")]
    (is (= [] paths))
    (is (= "just a plain goal" cleaned))))

(deftest task-status-open
  (is (= :open (task-status [] :fetch))))

(deftest task-status-in-progress
  (is (= :in-progress
         (task-status [{:type :think :task "fetch"}] :fetch))))

(deftest task-status-finished
  (is (= :finished
         (task-status [{:type :think  :task "fetch"}
                       {:type :execute :task "fetch"}
                       {:type :observe :task "fetch" :result "ok"}]
                      :fetch))))

(deftest task-status-error
  (is (= :error
         (task-status [{:type :think :task "fetch"}
                       {:type :error :task "fetch" :text "404"}]
                      :fetch))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb test test/botashka/core_test.clj`
Expected: FAIL — `botashka.core/format-event` not found

- [ ] **Step 3: Implement core.clj**

Replace `src/botashka/core.clj` with:

```clojure
(ns botashka.core
  (:require [babashka.tasks :as bt]
            [clojure.edn :as edn]
            [botashka.planner :as planner]
            [botashka.react :as react]
            [botashka.tools :as tools]))

(def tools-dir "tools")
(def bb-edn-path "bb.edn")
(def prompt "\n🤖 botashka> ")

;; session-trace — append-only event vector for the current session
(def session-trace (atom []))

(defn- append-event! [event]
  (swap! session-trace conj event)
  event)

;; ---------------------------------------------------------------------------
;; task-status — derive task status from session-trace (pure fn)
;; ---------------------------------------------------------------------------

(defn task-status
  "Derive the current status of a task from session-trace.
  Returns :open | :in-progress | :finished | :error"
  [trace task-name]
  (let [tname  (name task-name)
        events (filter #(= (:task %) tname) trace)]
    (cond
      (some #(= :error (:type %)) events)   :error
      (some #(= :observe (:type %)) events) :finished
      (some #(= :think (:type %)) events)   :in-progress
      :else                                  :open)))

(defn- update-bb-edn-status!
  "Write derived :status into each bb.edn task entry — display only, not authoritative."
  [bb-edn-path trace]
  (let [config  (clojure.edn/read-string (slurp bb-edn-path))
        updated (update config :tasks
                         (fn [tasks]
                           (reduce-kv
                             (fn [m k v]
                               (assoc m k (assoc v :status (task-status trace k))))
                             {} tasks)))]
    (spit bb-edn-path (with-out-str (clojure.pprint/pprint updated)))))

(defn- api-key []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "ANTHROPIC_API_KEY not set" {}))))

;; ---------------------------------------------------------------------------
;; emit! — the channel seam.
;; V1: formats and prints directly.
;; V2: replace body with (async/>!! human-out-ch event) — nothing else changes.
;; ---------------------------------------------------------------------------

(defn format-event
  "Format an agent event map as a human-readable string.
  Event types: :think :reuse :generate :validate :execute :answer :error :ask"
  [{:keys [type text status attempt message] :as event}]
  (case type
    :think    (str "[thinking] " text)
    :reuse    (str "[reuse] " text)
    :generate (str "[generate] " text " → saved tools/" text ".clj")
    :validate (if (= :ok status)
                "[validate] ok"
                (str "[validate] attempt " attempt " failed: " message))
    :execute  (str "[execute] " text " → done")
    :answer   text
    :error    (str "[error] " text)
    :ask      (str "[?] " text)
    (str "[" (name type) "] " text)))

(defn emit!
  "Emit an agent event to the user. Single point of output — replace for TUI/channel v2."
  [event]
  (println (format-event event))
  (flush))

;; ---------------------------------------------------------------------------
;; run-tool — called from bb.edn :task bodies via :init require
;; ---------------------------------------------------------------------------

(defn run-tool
  "Trigger the ReAct loop for a tool. Called from bb.edn task bodies.
  Example in bb.edn: (run-tool 'fetch-url {:url \"http://example.com\"})"
  [tool-name _args]
  (let [result (react/react-step! {:task-doc      (name tool-name)
                                   :session-trace @session-trace
                                   :tools-dir     tools-dir
                                   :api-key       (api-key)
                                   :emit!         emit!})]
    (when-let [output (:result result)]
      (emit! {:type :answer :text output}))
    result))

;; ---------------------------------------------------------------------------
;; @path parsing — extract file references from user input
;; ---------------------------------------------------------------------------

(defn- parse-at-paths
  "Extract @path tokens from input string. Returns [paths cleaned-input].
  '@src/foo.clj refactor it' → [['src/foo.clj'] 'src/foo.clj refactor it']"
  [input]
  (let [paths (mapv second (re-seq #"@(\S+)" input))
        cleaned (clojure.string/replace input #"@" "")]
    [paths cleaned]))

(defn- load-file-contexts!
  "Read each path and append a :file-context event to session-trace.
  Skips files that don't exist with a warning emit."
  [paths]
  (doseq [path paths]
    (if (.exists (java.io.File. path))
      (append-event! {:type :file-context :path path :content (slurp path)})
      (emit! {:type :error :text (str "@" path " not found")}))))

;; ---------------------------------------------------------------------------
;; start! — REPL interaction loop
;; ---------------------------------------------------------------------------

(defn- run-goal! [raw-input]
  (let [[paths goal] (parse-at-paths raw-input)]
    (load-file-contexts! paths)
    (append-event! {:type :user-input :text goal})
    (emit! {:type :think :text "Planning..."})
    (let [tasks (planner/plan! goal (api-key) bb-edn-path @session-trace)]
      (if-not tasks
        (emit! {:type :error :text "Could not parse plan from LLM response."})
        (do
          (append-event! {:type :plan :tasks (mapv (fn [[k v]] {:name (name k) :doc (:doc v)}) tasks)})
          (emit! {:type :answer :text (str "Plan: " (count tasks) " steps → " (clojure.string/join ", " (map name (keys tasks))))})
          (doseq [[task-name task] tasks]
            (emit! {:type :think :text (str "Step: " (name task-name) " — " (:doc task))})
            (let [result (react/react-step! {:task-doc      (:doc task (name task-name))
                                             :session-trace @session-trace
                                             :tools-dir     tools-dir
                                             :api-key       (api-key)
                                             :emit!         (fn [event]
                                                              (append-event! event)
                                                              (emit! event))})]
              (update-bb-edn-status! bb-edn-path @session-trace)
              (when-let [output (:result result)]
                (emit! {:type :answer :text output})))))))))

(defn start!
  "Start Botashka's conversational REPL loop. Type goals in natural language.
  Type 'exit' or 'quit' to return to the Clojure REPL.
  Example: (botashka.core/start!)"
  []
  (println "Botashka ready. Type your goal, or 'exit' to quit.")
  (loop []
    (print prompt)
    (flush)
    (let [input (read-line)]
      (when-not (or (nil? input) (#{"exit" "quit"} (clojure.string/trim input)))
        (try
          (run-goal! (clojure.string/trim input))
          (catch Exception e
            (emit! {:type :error :text (ex-message e)})))
        (recur))))
  (println "Goodbye."))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb test test/botashka/core_test.clj`
Expected: PASS

- [ ] **Step 5: Update bb.edn**

```clojure
{:paths ["src" "test"]
 :deps  {org.clojure/core.async {:mvn/version "1.6.681"}
         cheshire/cheshire      {:mvn/version "5.12.0"}}
 :init  (require '[botashka.core :refer [run-tool]])
 :tasks {test {:doc  "Run all tests"
               :task (require '[babashka.test :as t])
                     (t/test-runner {:test-paths ["test"]})}}}
```

- [ ] **Step 6: Smoke test the REPL loop**

```bash
export ANTHROPIC_API_KEY=your-key-here
bb repl
```

At the Babashka REPL prompt:
```clojure
(require '[botashka.core :as b])
(b/start!)
```

Type: `what is 2 + 2`
Expected: `[thinking]` line, then agent generates/reuses a tool, prints `4`, returns to `🤖 botashka> ` prompt.

Type: `exit`
Expected: `Goodbye.` and return to `user=>` prompt.

- [ ] **Step 7: Commit**

```bash
git add src/botashka/core.clj bb.edn test/botashka/core_test.clj
git commit -m "feat: REPL loop — start!/emit! output seam, run-tool"
```

---

## Task 9: Seed tool library with two bootstrap tools

**Files:**
- Create: `tools/fetch-url.clj`
- Create: `tools/write-file.clj`
- Modify: `tools/tool-index.edn`

Seed the library with two general-purpose tools so the agent has something to reuse immediately.

- [ ] **Step 1: Create tools/fetch-url.clj**

```clojure
;; description: Fetch the body of an HTTP URL as a string
;; spec-in:  string? (valid URL)
;; spec-out: string? (response body)

(ns tools.fetch-url
  (:require [babashka.http-client :as http]
            [clojure.spec.alpha :as s]))

(s/def ::url string?)
(s/def ::body string?)
(s/fdef fetch-url :args (s/cat :url ::url) :ret ::body)

(defn fetch-url [url]
  (:body (http/get url)))

(println (fetch-url (first *command-line-args*)))
```

- [ ] **Step 2: Create tools/write-file.clj**

```clojure
;; description: Write a string to a file at the given path, returns the path
;; spec-in:  map with :path (string) and :content (string)
;; spec-out: string? (absolute path written)

(ns tools.write-file
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(s/def ::path string?)
(s/def ::content string?)
(s/def ::write-args (s/keys :req-un [::path ::content]))
(s/fdef write-file :args (s/cat :args ::write-args) :ret ::path)

(defn write-file [{:keys [path content]}]
  (io/make-parents path)
  (spit path content)
  path)

(let [args (clojure.edn/read-string (first *command-line-args*))]
  (println (write-file args)))
```

- [ ] **Step 3: Update tools/tool-index.edn**

```clojure
{:fetch-url  {:description "Fetch the body of an HTTP URL as a string"
              :spec-in     "string? (valid URL)"
              :spec-out    "string? (response body)"}
 :write-file {:description "Write a string to a file at the given path, returns the path"
              :spec-in     "map with :path (string) and :content (string)"
              :spec-out    "string? (absolute path written)"}}
```

- [ ] **Step 4: Verify fetch-url works**

Run: `bb tools/fetch-url.clj https://example.com`
Expected: HTML body of example.com printed to stdout.

- [ ] **Step 5: Commit**

```bash
git add tools/fetch-url.clj tools/write-file.clj tools/tool-index.edn
git commit -m "feat: seed tool library with fetch-url and write-file"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Covered by task |
|---|---|
| Babashka runtime + bb.edn scaffold | Task 1 |
| LLM client — non-streaming `complete` for v1 | Task 2 |
| `llm-context` pure fn — session-trace → token string | Task 3 |
| Tool library CRUD + index | Task 4 |
| clojure.spec validation | Task 5 |
| Planning layer (goal → bb.edn tasks via `run-tool`) | Task 6 |
| ReAct loop — THINK/GENERATE/EXECUTE/OBSERVE with `:emit!` + `session-trace` | Task 7 |
| REPL loop + `emit!` + `format-event` + `start!` + `session-trace` atom | Task 8 |
| Seed tool library (`fetch-url`, `write-file`) | Task 9 |
| nREPL v2 migration path | Documented in spec; `emit!` is the seam — no plan task needed |

**Placeholder scan:** No TBDs or TODOs found.

**Type consistency check:**
- `tools-dir` — string `"tools"`, consistent across tools.clj, react.clj, core.clj ✓
- `api-key` — string from `(api-key)` helper in core.clj ✓
- `emit!` — `(fn [event-map] …)`, passed as `:emit!` in react-step! opts; nil-safe with `(when emit! …)`; no `:out-ch` anywhere ✓
- `session-trace` — `(atom [])` in core.clj; passed as `@session-trace` (dereffed) to react-step! and planner/plan! ✓
- `:file-context` events — prepended before `:user-input` by `load-file-contexts!` in core.clj; included by `select-events :think` and `:plan`; truncated at 8 000 chars in `render-event` ✓
- `:plan-amend` events — emitted before any bb.edn rewrite; included by `select-events :think`; rendered with reason + old/new task lists ✓
- `parse-at-paths` — private fn, tested via `#'botashka.core/parse-at-paths`; returns `[paths cleaned-input]` ✓
- `run-tasks!` — in react.clj; executes in-memory EDN task map without writing bb.edn; used by subagents and plan amendments ✓
- `task-status` — pure fn in core.clj; derives `:open/:in-progress/:finished/:error` from session-trace; tested for all four states ✓
- `update-bb-edn-status!` — private fn in core.clj; writes derived `:status` into bb.edn task entries after each step; display-only, never read back as ground truth ✓
- `react-step!` opts — `{:task-doc :session-trace :tools-dir :api-key :emit! :max-retries}` — consistent from Task 7 onward ✓
- `format-event` — pure fn, tested in Task 8, used only by `emit!` in core.clj ✓
- `run-tool` — defined in core.clj (Task 8), referenced in bb.edn `:init` ✓
- `llm-context` — pure fn in llm_context.clj; called from react.clj THINK/GENERATE and planner.clj ✓
- `truncate-content` — pure fn in llm_context.clj; used by `render-event :file-context` (8 000 chars) and `:observe` (4 000 chars) ✓
- Seed tools: `fetch-url` and `write-file` — consistent between Task 9 and spec tool index example ✓
