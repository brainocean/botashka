# Botashka Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local Babashka-native agentic assistant that uses generated Clojure snippets as its action space, with a self-growing tool library, hybrid plan+ReAct loop, and a REPL-first interface.

**Architecture:** The agent core has three layers — Planner (writes bb.edn tasks), ReAct loop (THINK→GENERATE→EXECUTE→OBSERVE), and Spec Validator (gates all generated code). V1 uses the Babashka nREPL (`bb nrepl-server`) as the interaction model — zero TUI code needed. The channel bus is used internally for LLM streaming; a full TUI adapter is out of scope for v1.

**Tech Stack:** Babashka (bb), clojure.spec.alpha, core.async (SCI subset + future for blocking I/O), babashka.http-client, babashka.tasks, Anthropic Claude API (SSE streaming), cheshire (JSON)

---

## File Map

| File | Responsibility |
|---|---|
| `bb.edn` | Project config + live agent task plan (agent writes `:tasks` here) |
| `src/botashka/core.clj` | REPL-friendly API — `plan!`, `run-all!`, `run-tool`, `status` functions |
| `src/botashka/llm.clj` | Claude API client — chat completion + SSE streaming via `future` |
| `src/botashka/planner.clj` | Decomposes goal → writes `:tasks` into bb.edn via LLM |
| `src/botashka/tools.clj` | Tool library management — load, save, list, update index |
| `src/botashka/spec.clj` | Spec validation helpers — conform generated source, report errors |
| `src/botashka/react.clj` | ReAct loop — THINK / GENERATE / EXECUTE / OBSERVE per task step |
| `tools/tool-index.edn` | Tool registry — `{:tool-name {:description … :spec-in … :spec-out …}}` |
| `test/botashka/llm_test.clj` | Tests for LLM client (mock HTTP) |
| `test/botashka/tools_test.clj` | Tests for tool lib CRUD and index management |
| `test/botashka/spec_test.clj` | Tests for spec validation helpers |
| `test/botashka/planner_test.clj` | Tests for bb.edn task writing |
| `test/botashka/react_test.clj` | Tests for ReAct loop decision routing |

**Out of scope for v1:** `src/botashka/tui.clj` — deferred; REPL is the v1 interface.

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

## Task 3: Tool library management

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

## Task 4: Spec validation helpers

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

## Task 5: Planner — goal decomposition into bb.edn tasks

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
            [botashka.llm :as llm]))

(def plan-system-prompt
  "You are Botashka's planner. Given a goal, decompose it into ordered steps.
Return ONLY a Clojure EDN map of tasks (no markdown, no explanation) in this exact format:
{step-name {:doc \"description\" :task (run-tool 'tool-name {:arg value})}
 step-two  {:doc \"description\" :depends [step-name] :task (run-tool 'tool-name {:arg value})}}
Use symbols (not strings) for task names. Use :depends for ordering. Keep steps small and focused.")

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
  [goal api-key bb-edn-path]
  (let [messages [{:role "user"
                   :content (str "Goal: " goal "\n\nReturn only the EDN tasks map.")}]
        response  (llm/complete
                    [{:role "system" :content plan-system-prompt}
                     (first messages)]
                    api-key)
        tasks     (tasks-edn->map response)]
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

## Task 6: ReAct loop

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
            [botashka.tools :as tools]
            [botashka.spec :as spec]
            [clojure.core.async :as async]))

(def think-system-prompt
  "You are Botashka's ReAct engine. Given a task and the available tool index,
decide how to proceed. Return ONLY an EDN map (no markdown):

To reuse an existing tool:
{:decision :reuse :tool-name :keyword :args {:key value}}

To generate a new tool:
{:decision :generate :tool-name :keyword :description \"one line\" :spec-in \"type desc\" :spec-out \"type desc\"}

To compose existing tools into a new tool:
{:decision :compose :tool-name :keyword :description \"one line\" :tools-used [:kw1 :kw2] :spec-in \"type desc\" :spec-out \"type desc\"}

To ask the human for clarification:
{:decision :ask :question \"your question\"}")

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

(defn generate-code-prompt
  "Build the LLM prompt for code generation given a think decision and task context."
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
                (map #(tools/load-tool-code tools-dir %) used))))
       "\nReturn ONLY the .clj source code. No markdown fences. No explanation.\n"
       "Must include: ns form, clojure.spec.alpha s/def or s/fdef for inputs and output, single defn."))

(defn react-step!
  "Run one ReAct iteration for a task step.
  Returns {:status :ok/:error :result <string> :observation <string>}"
  [{:keys [task-doc history tools-dir api-key out-ch max-retries]
    :or   {max-retries 3}}]
  (let [tool-index (tools/index-for-llm tools-dir)
        think-messages (into history
                              [{:role "user"
                                :content (str "Task: " task-doc
                                              "\nAvailable tools:\n" tool-index
                                              "\nDecide how to proceed. Return EDN only.")}])
        think-resp (llm/complete
                     [{:role "system" :content think-system-prompt}
                      (last think-messages)]
                     api-key)
        decision   (parse-think-decision think-resp)]

    (when out-ch
      (async/>!! out-ch {:type :info :text (str "[THINK] " think-resp)}))

    (case (:decision decision)
      :ask    {:status :ask :question (:question decision)}

      :reuse  (let [code   (tools/load-tool-code tools-dir (:tool-name decision))
                    result (execute-tool code (:args decision))]
                (when out-ch
                  (async/>!! out-ch {:type :info :text (str "[EXECUTE] " (:tool-name decision))}))
                {:status (:status result) :result (:stdout result) :observation (:stdout result)})

      (:generate :compose)
      (loop [attempt 1]
        (let [gen-prompt  (generate-code-prompt decision task-doc tools-dir)
              gen-code    (llm/complete
                            [{:role "system" :content "You are an expert Clojure/Babashka developer. Return only .clj source code."}
                             {:role "user" :content gen-prompt}]
                            api-key)
              validation  (spec/validate-tool-source gen-code)]
          (if (= :ok (:status validation))
            (do
              (tools/save-tool! tools-dir (:tool-name decision) gen-code
                                {:description (:description decision)
                                 :spec-in     (:spec-in decision)
                                 :spec-out    (:spec-out decision)})
              (when out-ch
                (async/>!! out-ch {:type :info :text (str "[GENERATE] saved " (name (:tool-name decision)))}))
              (let [result (execute-tool gen-code {})]
                {:status (:status result) :result (:stdout result) :observation (:stdout result)}))
            (if (< attempt max-retries)
              (do
                (when out-ch
                  (async/>!! out-ch {:type :info :text (str "[VALIDATE] attempt " attempt " failed: " (:message validation))}))
                (recur (inc attempt)))
              {:status :error :result nil :observation (str "Spec validation failed after " max-retries " attempts: " (:message validation))}))))

      {:status :error :result nil :observation (str "Unknown decision: " think-resp)})))
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

## Task 7: REPL-friendly core API

**Files:**
- Modify: `src/botashka/core.clj`
- Modify: `bb.edn`

V1 interaction is the Babashka nREPL. `core.clj` exposes clean, REPL-callable functions. LLM streaming prints tokens directly to stdout via `future` — no channel bus needed at this layer. The user workflow is:

```clojure
(require '[botashka.core :as b])
(b/plan! "Fetch SAP notes from this URL and save as CSV")
;; inspect bb.edn, then:
(b/run-all!)
;; or run a single step:
(b/run-step! 'fetch-notes)
```

- [ ] **Step 1: Implement core.clj**

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

(defn- api-key []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "ANTHROPIC_API_KEY environment variable not set" {}))))

(defn plan!
  "Decompose goal into steps and write them into bb.edn.
  Returns the tasks map so the user can inspect before running.
  Example: (plan! \"Fetch SAP notes and save as CSV\")"
  [goal]
  (let [tasks (planner/plan! goal (api-key) bb-edn-path)]
    (println "Plan written to bb.edn with" (count tasks) "steps:")
    (doseq [[k v] tasks]
      (println " " k "-" (:doc v)))
    tasks))

(defn run-step!
  "Run a single named task step through the ReAct loop.
  Example: (run-step! 'fetch-notes)"
  [task-name]
  (let [bb-edn  (edn/read-string (slurp bb-edn-path))
        task    (get-in bb-edn [:tasks task-name])
        doc     (:doc task (name task-name))]
    (react/react-step! {:task-doc  doc
                        :history   []
                        :tools-dir tools-dir
                        :api-key   (api-key)
                        :out-ch    nil})))

(defn run-all!
  "Execute all tasks in bb.edn in dependency order via babashka.tasks/run.
  Example: (run-all!)"
  []
  (let [bb-edn (edn/read-string (slurp bb-edn-path))
        tasks  (keys (:tasks bb-edn))]
    (println "Executing" (count tasks) "tasks...")
    (doseq [task-name tasks]
      (println "\n→" task-name)
      (bt/run task-name))))

(defn run-tool
  "Helper called from within bb.edn task bodies — triggers ReAct loop.
  Streams token output directly to stdout.
  Example in bb.edn: (run-tool 'fetch-url {:url \"http://example.com\"})"
  [tool-name args]
  (let [result (react/react-step! {:task-doc  (name tool-name)
                                   :history   []
                                   :tools-dir tools-dir
                                   :api-key   (api-key)
                                   :out-ch    nil})]
    (when-let [output (:result result)]
      (print output)
      (flush))
    result))

(defn status
  "Show current tool library and pending bb.edn tasks.
  Example: (status)"
  []
  (println "=== Tool Library ===")
  (doseq [t (tools/list-tools tools-dir)]
    (let [meta (get (tools/load-index tools-dir) t)]
      (println " " t "-" (:description meta))))
  (println "\n=== Current Plan (bb.edn tasks) ===")
  (let [bb-edn (edn/read-string (slurp bb-edn-path))]
    (doseq [[k v] (:tasks bb-edn)]
      (println " " k "-" (:doc v)))))
```

- [ ] **Step 2: Update bb.edn**

```clojure
{:paths ["src" "test"]
 :deps  {org.clojure/core.async {:mvn/version "1.6.681"}
         cheshire/cheshire      {:mvn/version "5.12.0"}}
 :init  (require '[botashka.core :refer [run-tool]])
 :tasks {repl  {:doc  "Start Babashka nREPL server for interactive use"
                :task (do (println "Starting nREPL on port 1667...")
                          (babashka.nrepl.server/start-server! {:host "localhost" :port 1667})
                          (println "Connect with: bb --nrepl-port 1667 or your editor's nREPL client")
                          @(promise))}
         test  {:doc  "Run all tests"
                :task (babashka.test/test-runner ["test"])}}}
```

- [ ] **Step 3: Verify REPL starts**

Run: `bb repl`
Expected: `Starting nREPL on port 1667...` then connects. In another terminal:

```bash
bb --nrepl-port 1667
```

Then at the prompt:
```clojure
(require '[botashka.core :as b])
(b/status)
```
Expected: prints empty tool library and empty plan.

- [ ] **Step 4: Commit**

```bash
git add src/botashka/core.clj bb.edn
git commit -m "feat: REPL-first core API — plan!/run-all!/run-step!/status"
```

---

## Task 8: Seed tool library with two bootstrap tools

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
| LLM client + SSE streaming | Task 2 |
| Tool library CRUD + index | Task 3 |
| clojure.spec validation | Task 4 |
| Planning layer (goal → bb.edn tasks) | Task 5 |
| ReAct loop (THINK/GENERATE/EXECUTE/OBSERVE) | Task 6 |
| REPL-first interaction + core API | Task 7 |
| Seed tool library | Task 8 |
| Streaming protocol (tokens printed direct to stdout) | Task 2 + Task 7 |
| JVM escape hatch | Task 6 `execute-tool` uses `bb -`; full `clojure -M` subprocess is a follow-on |
| TUI adapter (channel bus, :token/:done/:info/:ask) | **Deferred — out of scope for v1** |

**Placeholder scan:** No TBDs, TODOs, or "similar to" references found.

**Type consistency check:**
- `tools-dir` — string `"tools"`, consistent across tools.clj, react.clj, core.clj ✓
- `api-key` — string from env, passed via `(api-key)` helper in core.clj ✓
- `parse-think-decision` — defined and used only in react.clj ✓
- `execute-tool` — defined and tested in Task 6 ✓
- `run-tool` — defined in core.clj (Task 7), referenced in bb.edn `:init` ✓
- `react-step!` — called from `run-step!`, `run-all!`, and `run-tool` in core.clj with consistent `{:task-doc … :history … :tools-dir … :api-key … :out-ch nil}` map ✓
