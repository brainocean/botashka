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
           (run/model-driver state)))))

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
           (run/model-driver state)))
    (is (= [{:role "user" :content "ping"}
            {:role "assistant" :content "pong"}]
           (:messages state)))))
(deftest agent-run-uses-injected-driver
  (let [driver-calls (atom [])
        result (agent/run! {:next-command #(do (swap! driver-calls conj (:status %))
                                               (case (:status %)
                                                 :awaiting-model {:command :run/finish}
                                                 nil))
                            :command-executor! (test-command-executor
                                                (fn [_]
                                                  (throw (ex-info "Model client must not run" {})))
                                                (constantly "2026-10-31T10:00:00Z"))
                            :run-id "run-driver"
                            :system "ignored"
                            :prompt "ping"
                            :tools {}})]
    (is (= [:awaiting-model :finished] @driver-calls))
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
