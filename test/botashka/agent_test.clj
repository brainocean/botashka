(ns botashka.agent-test
  (:require [botashka.agent :as agent]
            [clojure.test :refer [deftest is testing]]))

(defn- test-command-executor
  [complete! clock]
  (agent/command-executor {:complete! complete!
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
  (let [run (-> (agent/new-run {:run-id "run-state"
                                :system "Reply concisely."
                                :tools {}
                                :max-turns 8})
                (agent/append-event "2026-10-31T10:00:00Z"
                                    :run-started
                                    {:max-turns 8}
                                    nil)
                (agent/append-event "2026-10-31T10:00:01Z"
                                    :user-submitted
                                    {:characters 4}
                                    {:prompt "ping"}))]
    (is (= {:command :model/complete
            :request {:system "Reply concisely."
                      :messages [{:role "user" :content "ping"}]
                      :tools []}}
           (agent/model-driver run)))))

(deftest model-response-event-derives-terminal-run-state
  (let [run (-> (agent/new-run {:run-id "run-terminal"
                                :system "Reply concisely."
                                :tools {}
                                :max-turns 8})
                (agent/append-event "2026-10-31T10:00:00Z"
                                    :run-started
                                    {:max-turns 8}
                                    nil)
                (agent/append-event "2026-10-31T10:00:01Z"
                                    :user-submitted
                                    {:characters 4}
                                    {:prompt "ping"})
                (agent/append-event "2026-10-31T10:00:02Z"
                                    :model-requested
                                    {:turn 0}
                                    nil)
                (agent/append-event "2026-10-31T10:00:03Z"
                                    :model-responded
                                    {:turn 0 :finish-reason "stop" :tool-call-count 0}
                                    {:assistant-message {:role "assistant"
                                                         :content "pong"}}))]
    (is (= :ready-to-finish (:status run)))
    (is (= {:command :run/finish}
           (agent/model-driver run)))
    (is (= [{:role "user" :content "ping"}
            {:role "assistant" :content "pong"}]
           (:messages run)))))
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
