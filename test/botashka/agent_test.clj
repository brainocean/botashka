(ns botashka.agent-test
  (:require [botashka.agent :as agent]
            [botashka.command-executor :as command-executor]
            [botashka.run :as run]
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
            :request {:system "Reply concisely."
                      :messages [{:role "user" :content "ping"}]
                      :tools []}}
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
