(ns botashka.agent-test
  (:require [botashka.agent :as agent]
            [botashka.bus :as bus]
            [botashka.chat :as chat]
            [botashka.clef-client :as clef-client]
            [botashka.clef-policy :as clef-policy]
            [botashka.command-executor :as command-executor]
            [botashka.model-client :as model-client]
            [botashka.run :as run]
            [botashka.tools :as tools]
            [botashka.trace :as trace]
            [botashka.workspace :as ws]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]))

(defn- test-command-executor
  [complete! clock]
  (command-executor/command-executor {:complete! complete!
                                      :clock clock}))

(deftest agent-run-emits-normalized-terminal-log
  (let [times (atom ["2026-10-31T10:00:00Z"
                     "2026-10-31T10:00:01Z"
                     "2026-10-31T10:00:02Z"
                     "2026-10-31T10:00:03Z"
                     "2026-10-31T10:00:04Z"])
        clock #(let [time (first @times)]
                 (swap! times rest)
                 time)
        result (agent/run! {:command-executor! (test-command-executor
                                                (constantly {:model "test-model"
                                                             :choices [{:finish_reason "stop"
                                                                        :message {:content "pong"}}]})
                                                clock)
                            :run-id "run-456"
                            :system "Reply concisely."
                            :prompt "ping"
                            :tools {}})]
    (is (= :completed (:status result)))
    (is (= [0 1 2 3 4]
           (mapv :seq (:log result))))
    (is (= [:run-started
            :user-submitted
            :model-requested
            :model-responded
            :run-finished]
           (mapv :type (:log result))))
    (is (= "run-456" (:run-id (first (:log result)))))
    (is (= :completed (get-in (last (:log result)) [:data :status])))))

(deftest agent-run-keeps-graded-local-evidence
  (let [result (agent/run! {:command-executor! (test-command-executor
                                                (constantly {:model "test-model"
                                                             :choices [{:finish_reason "stop"
                                                                        :message {:content "pong"}}]})
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-789"
                            :system "Reply concisely."
                            :prompt "private prompt"
                            :tools {}})]
    (is (= {:prompt "private prompt"}
           (:evidence (second (:log result)))))
    (is (= {:final-answer "pong"}
           (:evidence (last (:log result)))))))

(deftest agent-run-uses-injected-model-client
  (let [requests (atom [])
        result (agent/run! {:command-executor! (test-command-executor
                                                #(do (swap! requests conj %)
                                                     {:model "test-model"
                                                      :choices [{:finish_reason "stop"
                                                                 :message {:content "pong"}}]})
                                                (constantly "2026-10-31T10:00:00Z"))
                            :system "Reply concisely."
                            :prompt "ping"
                            :tools {}})]
    (is (= "pong" (:text result)))
    (is (= [{:system "Reply concisely."
             :messages [{:role "user" :content "ping"}]
             :tools []}]
           @requests))))

(deftest run-state-machine-decides-model-command-from-events
  (let [state (-> (run/new-run {:run-id "run-state"
                                :system "Reply concisely."
                                :tools {}
                                :max-turns 8})
                  (run/append-event "2026-10-31T10:00:00Z"
                                    :run-started
                                    {:max-turns 8}
                                    nil)
                  (run/append-event "2026-10-31T10:00:01Z"
                                    :user-submitted
                                    {:characters 4}
                                    {:prompt "ping"}))]
    (is (= {:command :model/complete
            :origin :policy
            :system "Reply concisely."
            :messages [{:role "user" :content "ping"}]
            :tools []}
           (run/default-action-policy state)))))
(deftest model-response-event-derives-terminal-run-state
  (let [state (-> (run/new-run {:run-id "run-terminal"
                                :system "Reply concisely."
                                :tools {}
                                :max-turns 8})
                  (run/append-event "2026-10-31T10:00:00Z"
                                    :run-started
                                    {:max-turns 8}
                                    nil)
                  (run/append-event "2026-10-31T10:00:01Z"
                                    :user-submitted
                                    {:characters 4}
                                    {:prompt "ping"})
                  (run/append-event "2026-10-31T10:00:02Z"
                                    :model-requested
                                    {:turn 0}
                                    nil)
                  (run/append-event "2026-10-31T10:00:03Z"
                                    :model-responded
                                    {:turn 0 :finish-reason "stop" :tool-call-count 0}
                                    {:assistant-message {:role "assistant"
                                                         :content "pong"}}))]
    (is (= :ready-to-finish (:status state)))
    (is (= {:command :run/finish}
           (run/default-action-policy state)))
    (is (= [{:role "user" :content "ping"}
            {:role "assistant" :content "pong"}]
           (:messages state)))))
(deftest agent-run-uses-injected-action-policy
  (let [policy-calls (atom [])
        result (agent/run! {:action-policy #(do (swap! policy-calls conj (:status %))
                                               (case (:status %)
                                                 :awaiting-model {:command :run/finish}
                                                 nil))
                            :command-executor! (test-command-executor
                                                (fn [_]
                                                  (throw (ex-info "Model client must not run" {})))
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-policy"
                            :system "ignored"
                            :prompt "ping"
                            :tools {}})]
    (is (= [:awaiting-model] @policy-calls))
    (is (= :completed (:status result)))
    (is (= [:run-started :user-submitted :run-finished]
           (mapv :type (:log result))))))

(deftest agent-run-executes-tool-call-loop-and-finishes
  (let [responses (atom [{:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :tool_calls [{:id "call-1"
                                                             :type "function"
                                                             :function {:name "calc"
                                                                        :arguments "{\"expr\": \"2+2\"}"}}]}}]}
                         {:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :content "4"}}]}])
        complete! (fn [_]
                    (let [r (first @responses)]
                      (swap! responses subvec 1)
                      r))
        calc-tool {:schema {:type "function"
                            :function {:name "calc"}}
                   :run (fn [{:keys [expr]}]
                          {:result 4})}
        result (agent/run! {:command-executor! (test-command-executor
                                                complete!
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-tool-loop"
                            :prompt "Calculate 2+2"
                            :tools {"calc" calc-tool}})]
    (is (= :completed (:status result)))
    (is (= "4" (:text result)))
    (is (= [:run-started
            :user-submitted
            :model-requested
            :model-responded
            :tool-proposed
            :tool-result
            :model-requested
            :model-responded
            :run-finished]
           (mapv :type (:log result))))
    (is (= :ok (get-in (:log result) [5 :data :outcome])))))

(deftest agent-run-handles-invalid-tool-arguments-and-unknown-tool
  (let [responses (atom [{:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :tool_calls [{:id "call-bad-json"
                                                             :type "function"
                                                             :function {:name "unknown_tool"
                                                                        :arguments "not valid json"}}]}}]}
                         {:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :content "Recovered"}}]}])
        complete! (fn [_]
                    (let [r (first @responses)]
                      (swap! responses subvec 1)
                      r))
        result (agent/run! {:command-executor! (test-command-executor
                                                complete!
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-tool-err"
                            :prompt "Trigger error"
                            :tools {}})]
    (is (= :completed (:status result)))
    (is (= "Recovered" (:text result)))
    (is (= :tool-proposed (:type (nth (:log result) 4))))
    (is (= :tool-result (:type (nth (:log result) 5))))
    (is (= :error (get-in (:log result) [5 :data :outcome])))
    (is (= :invalid-arguments (get-in (:log result) [5 :data :error-kind])))))

(deftest agent-run-terminates-with-step-limit
  (let [complete! (fn [_]
                    {:choices [{:finish_reason "stop"
                                :message {:role "assistant"
                                          :tool_calls [{:id "call-loop"
                                                        :type "function"
                                                        :function {:name "noop"
                                                                   :arguments "{}"}}]}}]})
        result (agent/run! {:command-executor! (test-command-executor
                                                complete!
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-limit"
                            :prompt "Loop forever"
                            :max-turns 2
                            :tools {"noop" {:schema {:type "function"}
                                            :run (constantly {:ok true})}}})]
    (is (= :step-limit (:status result)))
    (is (nil? (:text result)))
    (is (= :limit-reached (:type (nth (:log result) (- (count (:log result)) 2)))))
    (is (= :run-finished (:type (last (:log result)))))))

(deftest agent-run-terminates-with-provider-error-on-model-failure
  (let [complete! (fn [_]
                    {:error {:kind :provider-error
                             :status 500
                             :message "Service Unavailable"}})
        result (agent/run! {:command-executor! (test-command-executor
                                                complete!
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-provider-err"
                            :prompt "Fails immediately"
                            :tools {}})]
    (is (= :provider-error (:status result)))
    (is (= [:run-started :user-submitted :model-requested :model-failed :run-finished]
           (mapv :type (:log result))))))

(deftest agent-run-executes-direct-tool-commands-with-scripted-policy
  (let [executed (atom [])
        scripted-policy (fn [run]
                          (case (:turn run)
                            0 {:command :tool/execute
                               :tool "probe"
                               :arguments {:query "target"}}
                            1 {:command :run/finish
                               :result "Task completed successfully"}
                            nil))
        result (agent/run! {:action-policy scripted-policy
                            :command-executor! (command-executor/command-executor
                                                {:complete! (fn [_] (throw (ex-info "No LLM calls allowed" {})))
                                                 :clock (constantly "2026-10-31T10:00:00Z")})
                            :run-id "run-scripted"
                            :prompt "Execute deterministic workflow"
                            :tools {"probe" {:schema {:type "function"}
                                             :run (fn [args]
                                                    (swap! executed conj args)
                                                    {:found true})}}})]
    (is (= :completed (:status result)))
    (is (= "Task completed successfully" (:text result)))
    (is (= [{:query "target"}] @executed))
    (is (= [:run-started :user-submitted :tool-proposed :tool-result :run-finished]
           (mapv :type (:log result))))
    (is (= :host (get-in (:log result) [2 :data :origin])))
    (is (= :host (get-in (:log result) [3 :data :origin])))
    (is (= :ok (get-in (:log result) [3 :data :outcome])))))

(deftest agent-run-enforces-authorization-policy-rejection
  (let [tool-executed? (atom false)
        policy (fn [run]
                 (case (:status run)
                   :awaiting-model (if (some #(= :action-rejected (get-in % [:data :error-kind]))
                                             (:events run))
                                     {:command :run/finish :result "Safely halted"}
                                     {:command :tool/execute
                                      :tool "delete_all"
                                      :arguments {:force true}})
                   :ready-to-finish {:command :run/finish}
                   nil))
        auth-policy (fn [_run command]
                      (if (= (or (:tool command) (get-in command [:tool-call :function :name])) "delete_all")
                        {:decision :deny :reason "Destructive operation forbidden"}
                        :allow))
        result (agent/run! {:action-policy policy
                            :authorization-policy auth-policy
                            :command-executor! (command-executor/command-executor
                                                {:complete! (fn [_] (throw (ex-info "No LLM allowed" {})))
                                                 :clock (constantly "2026-10-31T10:00:00Z")
                                                 :authorize auth-policy})
                            :run-id "run-auth-deny"
                            :prompt "Attempt forbidden operation"
                            :tools {"delete_all" {:schema {:type "function"}
                                                  :run (fn [_]
                                                         (reset! tool-executed? true)
                                                         {:deleted true})}}})]
    (is (= false @tool-executed?))
    (is (= :completed (:status result)))
    (is (= "Safely halted" (:text result)))
    (let [tool-res-evt (nth (:log result) 3)]
      (is (= :tool-result (:type tool-res-evt)))
      (is (= :error (get-in tool-res-evt [:data :outcome])))
      (is (= :action-rejected (get-in tool-res-evt [:data :error-kind]))))))

(deftest action-policy-composition-and-arbitration
  (let [p-guard (fn [run]
                  (when (>= (:turn run) 5)
                    {:command :run/limit}))
        p-fallback (constantly {:command :run/finish :result "Fallback"})
        composite (run/first-applicable-action-policy p-guard p-fallback)
        state-early (run/new-run {:run-id "r1" :max-turns 8})
        state-late (assoc state-early :turn 6)
        proposal-a (run/action-proposal {:command {:command :tool/execute :tool "slow"}
                                         :proposed-by :generative
                                         :confidence 0.6})
        proposal-b (run/action-proposal {:command {:command :tool/execute :tool "fast"}
                                         :proposed-by :judgement
                                         :confidence 0.95})]
    (is (= {:command :run/finish :result "Fallback"} (composite state-early)))
    (is (= {:command :run/limit} (composite state-late)))
    (is (= {:command :tool/execute :tool "fast"}
           (run/arbitrate-proposals [proposal-a proposal-b])))))

(deftest code-tool-registry-discovers-malli-parameters-and-lowers-to-json-schema
  (let [reg (tools/code-tool-registry ".")
        read-file (get reg "read_file")
        list-files (get reg "list_files")
        search-text (get reg "search_text")
        run-command (get reg "run_command")
        write-file (get reg "write_file")]
    (is (some? read-file))
    (is (some? list-files))
    (is (some? search-text))
    (is (some? run-command))
    (is (some? write-file))
    ;; Parameters are defined as Malli schemas (pure EDN vector)
    (is (vector? (:parameters read-file)))
    (is (= :map (first (:parameters read-file))))
    ;; Lowered to standard JSON schema for provider
    (let [json-sch (get-in read-file [:schema :function :parameters])]
      (is (= "object" (get json-sch :type)))
      (is (contains? (get json-sch :properties) :path))
      (is (= "string" (get-in json-sch [:properties :path :type]))))))

(deftest host-validates-tool-arguments-with-malli-before-execution
  (let [reg (tools/code-tool-registry ".")
        read-file (get reg "read_file")
        ;; Invalid type
        err-type ((:run read-file) {:path 123})
        ;; Missing required key
        err-missing ((:run read-file) {})]
    (is (= :invalid-arguments (get-in err-type [:error :kind])))
    (is (some? (get-in err-type [:error :details])))
    (is (= :invalid-arguments (get-in err-missing [:error :kind])))
    (is (some? (get-in err-missing [:error :details])))))

(deftest trace-graph-renders-all-event-types
  (let [sample-result {:status :completed
                       :text "done"
                       :messages []
                       :log [{:seq 0 :type :run-started :data {:max-turns 8}}
                             {:seq 1 :type :user-submitted :evidence {:prompt "hello"}}
                             {:seq 2 :type :model-requested :data {:tool-count 1 :message-count 1}}
                             {:seq 3 :type :model-responded :data {:model "test" :finish-reason "stop"}
                              :evidence {:assistant-message {:content "hi"}}}
                             {:seq 4 :type :run-finished :data {:status :completed}}]}
        lines (trace/trace-lines sample-result)
        output (with-out-str (trace/render-trace-graph sample-result))]
    (is (vector? lines))
    (is (some #(clojure.string/includes? % "┌── [0] :run-started") lines))
    (is (some #(clojure.string/includes? % "└── [4] :run-finished") lines))
    (is (clojure.string/includes? output "Execution Trace Graph"))
    (is (clojure.string/includes? output ":run-started"))
    (is (clojure.string/includes? output ":user-submitted"))
    (is (clojure.string/includes? output ":model-requested"))
    (is (clojure.string/includes? output ":run-finished"))))
(deftest model-command-projection-and-execution-with-origin
  (let [run (-> (run/new-run {:run-id "r-ctx" :system "Sys" :max-turns 4})
                (update :messages conj {:role "user" :content "Hello"}))
        ctx (run/build-model-context run)
        recorded-req (atom nil)
        executor (command-executor/command-executor
                  {:complete! (fn [req]
                                (reset! recorded-req req)
                                {:choices [{:finish_reason "stop"
                                            :message {:role "assistant" :content "Hi"}}]})
                   :clock (constantly "2026-10-31T10:00:00Z")})
        state (executor run (merge {:command :model/complete :origin :custom-policy}
                                   ctx))]
    (is (= {:system "Sys" :messages [{:role "user" :content "Hello"}] :tools []}
           ctx))
    (is (= {:system "Sys" :messages [{:role "user" :content "Hello"}] :tools []}
           @recorded-req))
    (is (= :custom-policy (get-in (:events state) [0 :data :origin])))
    (is (= :custom-policy (get-in (:events state) [1 :data :origin])))))

(deftest host-validates-tool-return-contract-post-execution
  (let [reg (tools/code-tool-registry ".")
        read-file (get reg "read_file")
        list-files (get reg "list_files")
        search-text (get reg "search_text")
        run-command (get reg "run_command")
        write-file (get reg "write_file")]
    (is (some? (:returns read-file)))
    (is (some? (:returns list-files)))
    (is (some? (:returns search-text)))
    (is (some? (:returns run-command)))
    (is (some? (:returns write-file)))
    (let [res ((:run read-file) {:path "README.md"})]
      (is (string? (:content res))))))
(deftest agent-loop-handles-post-check-contract-violation-and-continues
  (let [buggy-tool {:parameters [:map [:x :int]]
                    :returns [:map [:sum :int]]
                    :run (fn [{:keys [x]}]
                           ;; Deliberately breaks return contract by missing :sum
                           {:wrong-key x})}
        guarded-tool {:run (fn [args]
                             (let [res ((:run buggy-tool) args)
                                   ret-schema (:returns buggy-tool)]
                               (if (and ret-schema (not (malli.core/validate ret-schema res)))
                                 {:error {:kind :invalid-tool-output-contract
                                          :tool "buggy"
                                          :details (malli.error/humanize (malli.core/explain ret-schema res))
                                          :raw-output res}}
                                 res)))}
        responses (atom [{:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :tool_calls [{:id "call-buggy"
                                                             :type "function"
                                                             :function {:name "buggy"
                                                                        :arguments "{\"x\": 10}"}}]}}]}
                         {:choices [{:finish_reason "stop"
                                     :message {:role "assistant"
                                               :content "Detected contract failure and recovered"}}]}])
        received-tool-msgs (atom [])
        complete! (fn [req]
                    (let [r (first @responses)]
                      (swap! responses subvec 1)
                      (when-let [tool-msg (first (filter #(= (:role %) "tool") (:messages req)))]
                        (swap! received-tool-msgs conj tool-msg))
                      r))
        result (agent/run! {:command-executor! (command-executor/command-executor
                                                {:complete! complete!
                                                 :clock (constantly "2026-10-31T10:00:00Z")})
                            :run-id "run-post-check"
                            :prompt "Run buggy tool"
                            :tools {"buggy" guarded-tool}})]
    (is (= :completed (:status result)))
    (is (= "Detected contract failure and recovered" (:text result)))
    ;; Tool result message was generated and delivered to model without halting the loop
    (is (= 1 (count @received-tool-msgs)))
    (let [content (:content (first @received-tool-msgs))]
      (is (clojure.string/includes? content "invalid-tool-output-contract"))
      (is (clojure.string/includes? content "missing required key")))
    ;; Tool result was recorded in event log with outcome error
    (let [tool-res (first (filter #(= (:type %) :tool-result) (:log result)))]
      (is (= :error (get-in tool-res [:data :outcome])))
      (is (= :invalid-tool-output-contract (get-in tool-res [:data :error-kind]))))))

(deftest search-text-tool-finds-matches-and-rejects-outside-workspace
  (let [reg (tools/code-tool-registry ".")
        search-text (get reg "search_text")
        res ((:run search-text) {:query "code-tool-registry"})
        err-outside ((:run search-text) {:path "../outside" :query "foo"})]
    (is (pos? (:total res)))
    (is (vector? (:matches res)))
    (is (some #(= "src/botashka/tools.clj" (:path %)) (:matches res)))
    (is (= :path-outside-workspace (get-in err-outside [:error :kind])))))

(deftest run-command-executes-shell-and-captures-output
  (let [reg (tools/code-tool-registry ".")
        run-cmd (get reg "run_command")
        res ((:run run-cmd) {:command "echo dogfood"})
        err ((:run run-cmd) {:command ""})]
    (is (zero? (:exit res)))
    (is (clojure.string/includes? (:out res) "dogfood"))
    (is (= :invalid-arguments (get-in err [:error :kind])))))

(deftest write-file-creates-file-and-rejects-outside-workspace
  (let [reg (tools/code-tool-registry ".")
        write-fn ((get reg "write_file") :run)
        temp-path "target/temp_dogfood_test.txt"
        res (write-fn {:path temp-path :content "dogfood content"})
        err-outside (write-fn {:path "../outside.txt" :content "evil"})]
    (is (= temp-path (:path res)))
    (is (pos? (:bytes_written res)))
    (is (babashka.fs/exists? temp-path))
    (is (= "dogfood content" (slurp temp-path)))
    (babashka.fs/delete temp-path)
    (is (= :path-outside-workspace (get-in err-outside [:error :kind])))))

(deftest workspace-resolve-path-boundaries-and-errors
  (let [valid (ws/resolve-path "." "README.md")
        outside (ws/resolve-path "." "../outside.txt")
        not-found (ws/resolve-path "." "missing_file_xyz.txt" {:must-exist? true})
        empty-required (ws/resolve-path "." "" {:require-path? true})]
    (is (some? (:workspace valid)))
    (is (some? (:target valid)))
    (is (= "README.md" (:rel-path valid)))
    (is (= :path-outside-workspace (get-in outside [:error :kind])))
    (is (= :path-not-found (get-in not-found [:error :kind])))
    (is (= :invalid-arguments (get-in empty-required [:error :kind])))))

(deftest agent-run-publishes-events-to-core-async-channel
  (let [event-ch (async/chan 20)
        fake-complete (fn [_]
                        {:choices [{:message {:role "assistant"
                                              :content "test response"}}]})
        executor (command-executor/command-executor
                  {:complete! fake-complete
                   :clock (constantly "2026-11-16T10:00:00Z")
                   :on-event #(async/>!! event-ch %)})
        res (agent/run! {:command-executor! executor
                         :prompt "hello"
                         :event-chan event-ch
                         :tools {}})]
    (is (= :completed (:status res)))
    (let [events (loop [acc []]
                   (if-let [e (async/<!! event-ch)]
                     (recur (conj acc e))
                     acc))]
      (is (= [:run-started :user-submitted :model-requested :model-responded :run-finished]
             (mapv :type events)))
      ;; Channel is closed at end of run
      (is (nil? (async/<!! event-ch))))))

(deftest agent-run-publishes-streaming-tokens-when-streaming-complete-provided
  (let [event-ch (async/chan 20)
        fake-streaming (fn [_ on-token]
                         (on-token "Hello ")
                         (on-token "streaming ")
                         (on-token "world")
                         {:choices [{:message {:role "assistant"
                                               :content "Hello streaming world"}}]})
        executor (command-executor/command-executor
                  {:complete! (fn [_] (throw (ex-info "Should use complete-streaming!" {})))
                   :complete-streaming! fake-streaming
                   :clock (constantly "2026-11-16T10:00:00Z")
                   :on-event #(async/>!! event-ch %)})
        res (agent/run! {:command-executor! executor
                         :prompt "test streaming"
                         :event-chan event-ch
                         :tools {}})]
    (is (= :completed (:status res)))
    (let [events (loop [acc []]
                   (if-let [e (async/<!! event-ch)]
                     (recur (conj acc e))
                     acc))
          token-events (filter #(= (:type %) :streaming-token) events)]
      (is (= 3 (count token-events)))
      (is (= ["Hello " "streaming " "world"]
             (mapv :delta token-events))))))

(deftest sse-events-seq-frames-multiline-data-and-comments
  (let [lines ["data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"one \"}]}}]}"
               ""
               ": heartbeat comment"
               "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"two\"}]}}]}"
               ""
               "data: {\"candidates\": [{\"finishReason\": \"STOP\"}], \"usageMetadata\": {\"totalTokenCount\": 10}}"
               ""]
        events (into [] (model-client/sse-events-seq lines))]
    (is (= 3 (count events)))
    (is (clojure.string/includes? (first events) "one"))
    (is (clojure.string/includes? (second events) "two"))))

(deftest parse-stream-events-accumulates-tokens-and-response
  (let [lines ["data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hello \"}]}}]}"
               ""
               "data: {\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"world!\"}]}}]}"
               ""
               "data: {\"candidates\": [{\"finishReason\": \"STOP\"}], \"responseId\": \"resp-999\", \"modelVersion\": \"gemini-test\", \"usageMetadata\": {\"totalTokenCount\": 20}}"
               ""]
        tokens (atom [])
        resp (model-client/parse-stream-events "gemini-test" lines (fn [t] (swap! tokens conj t)))]
    (is (= ["Hello " "world!"] @tokens))
    (is (= "Hello world!" (get-in resp [:choices 0 :message :content])))
    (is (= "stop" (get-in resp [:choices 0 :finish_reason])))
    (is (= "resp-999" (:id resp)))
    (is (= "gemini-test" (:model resp)))
    (is (= 20 (get-in resp [:usage :totalTokenCount])))))

(deftest event-to-display-item-projects-canonical-and-streaming-events
  (is (= {:kind :token :delta "abc"}
         (chat/event->display-item {:type :streaming-token :delta "abc"})))
  (is (= {:kind :tool-proposed :tool "read_file" :args "path=\"foo\""}
         (chat/event->display-item {:type :tool-proposed
                                    :data {:tool "read_file"}
                                    :evidence {:arguments {:path "foo"}}})))
  (is (= {:kind :tool-result :outcome :ok}
         (chat/event->display-item {:type :tool-result :data {:outcome :ok}})))
  (is (= {:kind :tool-error :error-kind :not-found :message "missing file"}
         (chat/event->display-item {:type :tool-result
                                    :evidence {:result {:error {:kind :not-found :message "missing file"}}}})))
  (is (= {:kind :model-failed :error {:kind :rate-limit}}
         (chat/event->display-item {:type :model-failed :data {:error {:kind :rate-limit}}})))
  (is (= {:kind :run-finished :status :completed :turn 3}
         (chat/event->display-item {:type :run-finished :data {:status :completed :turn 3}})))
  (is (nil? (chat/event->display-item {:type :run-started}))))

(deftest bus-publishes-and-separates-canonical-and-streaming-topics
  (let [b (bus/new-bus)
        stream-ch (bus/subscribe! b :stream 10)
        canonical-ch (bus/subscribe! b :canonical 10)]
    (bus/publish-stream! b "m-1" :token "hello ")
    (bus/publish-canonical! b {:type :user-submitted :data {:chars 5}})
    (bus/publish-stream! b "m-1" :token "world!")
    (bus/publish-canonical! b {:type :run-finished :data {:status :completed}})
    (let [s1 (async/<!! stream-ch)
          s2 (async/<!! stream-ch)
          c1 (async/<!! canonical-ch)
          c2 (async/<!! canonical-ch)]
      (is (= {:topic :stream :stream-id "m-1" :kind :token :payload "hello "} s1))
      (is (= {:topic :stream :stream-id "m-1" :kind :token :payload "world!"} s2))
      (is (= :user-submitted (get-in c1 [:payload :type])))
      (is (= :run-finished (get-in c2 [:payload :type]))))
    (bus/close-bus! b)))

(deftest agent-run-publishes-to-bus
  (let [b (bus/new-bus)
        stream-ch (bus/subscribe! b :stream 10)
        canonical-ch (bus/subscribe! b :canonical 10)
        mock-complete! (fn [_]
                         {:choices [{:finish_reason "stop"
                                     :message {:role "assistant" :content "Bus pong"}}]})
        mock-complete-streaming! (fn [_ on-token]
                                   (on-token "Bus ")
                                   (on-token "pong")
                                   {:choices [{:finish_reason "stop"
                                               :message {:role "assistant" :content "Bus pong"}}]})
        executor (command-executor/command-executor
                  {:complete! mock-complete!
                   :complete-streaming! mock-complete-streaming!
                   :clock (constantly "2026-10-31T10:00:00Z")
                   :bus b})
        res (agent/run! {:command-executor! executor
                         :prompt "Bus ping"
                         :bus b
                         :run-id "run-bus"
                         :tools {}})]
    (is (= :completed (:status res)))
    ;; Check streaming tokens on bus
    (let [t1 (async/<!! stream-ch)
          t2 (async/<!! stream-ch)]
      (is (= "Bus " (:payload t1)))
      (is (= "pong" (:payload t2))))
    ;; Check canonical events on bus
    (let [canonical-events (atom [])
          timeout-ms 200]
      (loop []
        (let [[v _] (async/alts!! [canonical-ch (async/timeout timeout-ms)])]
          (when v
            (swap! canonical-events conj (get-in v [:payload :type]))
            (recur))))
      (is (some #{:run-started} @canonical-events))
      (is (some #{:user-submitted} @canonical-events))
      (is (some #{:model-responded} @canonical-events))
      (is (some #{:run-finished} @canonical-events)))
    (bus/close-bus! b)))

(deftest clef-client-parses-systemone-response
  (let [sample-raw-json "{\"model\":\"clef-flash\",\"answers\":{\"tool_choice\":{\"type\":\"score\",\"legend\":{\"0\":\"search_text\",\"1\":\"list_files\",\"2\":\"no_tool\"},\"probabilities\":{\"0\":0.88,\"1\":0.08,\"2\":0.04},\"confidence\":0.82}}}"
        parsed (clef-client/parse-clef-response sample-raw-json :tool_choice)]
    (is (= "clef-flash" (:model parsed)))
    (is (= "search_text" (:choice parsed)))
    (is (= 0.88 (:probability parsed)))
    (is (= 0.82 (:confidence parsed)))))

(deftest clef-policy-emits-decision-command-and-then-selects-tool
  (let [mock-decide (fn [_] {:choice "search_text" :probability 0.91 :confidence 0.85 :model "clef-flash"})
        policy (clef-policy/clef-action-policy {:decide-fn mock-decide :min-probability 0.80})
        run-init (-> (run/new-run {:run-id "r-clef" :max-turns 5})
                     (assoc :status :awaiting-model
                            :tools {"search_text" {:schema {}}
                                    "read_file" {:schema {}}}))
        ;; Step 1: In :awaiting-model, policy emits :decision/complete command
        cmd1 (policy run-init)]
    (is (= :decision/complete (:command cmd1)))
    (is (some #{"search_text"} (:criteria cmd1)))
    (is (some #{"no_tool"} (:criteria cmd1)))
    ;; Step 2: After decision model responds, state is updated with :last-decision
    (let [run-after-decision (-> run-init
                                 (update :events conj (run/decision-requested-event run-init "clef-flash" (:criteria cmd1) (:state cmd1)))
                                 (update :events conj (run/decision-responded-event run-init (mock-decide nil)))
                                 (run/apply-event (run/decision-responded-event run-init (mock-decide nil))))
          cmd2 (policy run-after-decision)]
      (is (= :tool/execute (:command cmd2)))
      (is (= "search_text" (:tool cmd2)))
      (is (= :decision (:origin cmd2)))
      (is (= 0.85 (:confidence cmd2))))))

(deftest clef-policy-falls-back-on-no-tool-or-low-confidence
  (let [mock-no-tool (fn [_] {:choice "no_tool" :probability 0.95 :confidence 0.90 :model "clef-flash"})
        policy-no-tool (clef-policy/clef-action-policy {:decide-fn mock-no-tool})
        run-init (-> (run/new-run {:run-id "r-fallback" :max-turns 5})
                     (assoc :status :awaiting-model
                            :tools {"read_file" {:schema {}}}))
        cmd-decide (policy-no-tool run-init)
        run-after-no-tool (-> run-init
                              (update :events conj (run/decision-requested-event run-init "clef-flash" (:criteria cmd-decide) ""))
                              (update :events conj (run/decision-responded-event run-init (mock-no-tool nil)))
                              (run/apply-event (run/decision-responded-event run-init (mock-no-tool nil))))]
    ;; After no_tool decision, policy returns nil to allow fallback to model policy
    (is (nil? (policy-no-tool run-after-no-tool)))))

(deftest clef-policy-integrates-with-first-applicable-action-policy
  (let [mock-decide (fn [_] {:choice "list_files" :probability 0.92 :confidence 0.88 :model "clef-flash"})
        clef-pol (clef-policy/clef-action-policy {:decide-fn mock-decide})
        combined-policy (run/first-applicable-action-policy clef-pol run/default-action-policy)
        run (-> (run/new-run {:run-id "r-comb" :max-turns 5})
                (assoc :status :awaiting-model
                       :tools {"list_files" {:schema {}}}))
        cmd (combined-policy run)]
    (is (= :decision/complete (:command cmd)))
    (is (some #{"list_files"} (:criteria cmd)))))

(deftest model-context-projects-fast-path-handoff-message
  (testing "Yielding on low-confidence decision injects handoff context to System 2"
    (let [run (-> (run/new-run {:run-id "r-handoff" :max-turns 5})
                  (assoc :status :awaiting-model
                         :messages [{:role "user" :content "What is the latest file?"}])
                  (run/append-event "2026-11-17T03:00:00Z"
                                    :decision-requested
                                    {:turn 0 :decider "clef-flash" :criteria ["read_file" "no_tool"]}
                                    {:state "summary"})
                  (run/append-event "2026-11-17T03:00:01Z"
                                    :decision-responded
                                    {:turn 0 :decider "clef-flash" :choice "read_file" :probability 0.55 :confidence 0.52}
                                    {:decision {:choice "read_file"}}))
          ctx (run/build-model-context run)
          last-msg (last (:messages ctx))]
      (is (= 2 (count (:messages ctx))))
      (is (= "user" (:role last-msg)))
      (is (.contains (:content last-msg) "evaluated `read_file` (52% confidence)"))
      (is (.contains (:content last-msg) "deferred to you for strategic reasoning"))))

  (testing "Decision no_tool injects no-tool context to System 2"
    (let [run (-> (run/new-run {:run-id "r-no-tool" :max-turns 5})
                  (assoc :status :awaiting-model
                         :messages [{:role "user" :content "Hello, how are you?"}])
                  (run/append-event "2026-11-17T03:00:00Z"
                                    :decision-requested
                                    {:turn 0 :decider "clef-flash" :criteria ["list_files" "no_tool"]}
                                    {:state "summary"})
                  (run/append-event "2026-11-17T03:00:01Z"
                                    :decision-responded
                                    {:turn 0 :decider "clef-flash" :choice "no_tool" :probability 0.94 :confidence 0.90}
                                    {:decision {:choice "no_tool"}}))
          ctx (run/build-model-context run)
          last-msg (last (:messages ctx))]
      (is (= 2 (count (:messages ctx))))
      (is (.contains (:content last-msg) "determined no immediate tool is needed (90% confidence)")))))

(deftest clef-policy-rejects-read-file-when-target-cannot-be-extracted
  (testing "Even if Clef predicts read_file with high confidence, missing target falls back to LLM"
    (let [mock-decide (fn [_] {:choice "read_file" :probability 0.88 :confidence 0.82 :model "clef-flash"})
          policy (clef-policy/clef-action-policy {:decide-fn mock-decide :min-probability 0.70})
          ;; User prompt does NOT mention any file or readme
          run-init (-> (run/new-run {:run-id "r-nofile" :max-turns 5})
                       (assoc :status :awaiting-model
                              :tools {"read_file" {:schema {}}})
                       (run/append-event "2026-11-17T03:00:00Z"
                                         :user-submitted
                                         {:characters 20}
                                         {:prompt "root文件夹哪个文件最新？"}))
          cmd-decide (policy run-init)
          run-after-decision (-> run-init
                                 (run/append-event "2026-11-17T03:00:01Z"
                                                   :decision-requested
                                                   {:turn 0 :decider "clef-flash" :criteria (:criteria cmd-decide)}
                                                   {:state ""})
                                 (run/append-event "2026-11-17T03:00:02Z"
                                                   :decision-responded
                                                   (mock-decide nil)
                                                   {:decision (mock-decide nil)}))
          cmd-tool (policy run-after-decision)]
      ;; Because path cannot be inferred from "root文件夹哪个文件最新？", policy MUST return nil
      (is (nil? cmd-tool)))))
