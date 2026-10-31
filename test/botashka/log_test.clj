(ns botashka.log-test
  (:require [botashka.agent :as agent]
            [botashka.provider :as provider]
            [botashka.log :as log]
            [clojure.test :refer [deftest is testing]]))

(deftest recorder-normalizes-and-orders-domain-facts
  (let [times (atom ["2026-10-31T10:00:00Z"
                     "2026-10-31T10:00:01Z"])
        recorder (log/recorder {:run-id "run-123"
                                  :clock #(let [time (first @times)]
                                            (swap! times rest)
                                            time)})]
    (log/emit! recorder :run-started {:max-turns 8})
    (log/emit! recorder :run-finished {:status :completed})
    (is (= [{:run-id "run-123"
             :seq 0
             :at "2026-10-31T10:00:00Z"
             :type :run-started
             :data {:max-turns 8}}
            {:run-id "run-123"
             :seq 1
             :at "2026-10-31T10:00:01Z"
             :type :run-finished
             :data {:status :completed}}]
           (log/events recorder)))))

(deftest agent-run-emits-normalized-terminal-log
  (let [times (atom ["2026-10-31T10:00:00Z"
                     "2026-10-31T10:00:01Z"
                     "2026-10-31T10:00:02Z"
                     "2026-10-31T10:00:03Z"
                     "2026-10-31T10:00:04Z"])
        recorder (log/recorder {:run-id "run-456"
                                  :clock #(let [time (first @times)]
                                            (swap! times rest)
                                            time)})]
    (with-redefs [provider/complete! (constantly {:model "test-model"
                                                  :choices [{:finish_reason "stop"
                                                             :message {:content "pong"}}]})]
      (let [result (agent/run! {:system "Reply concisely."
                                :prompt "ping"
                                :tools {}
                                :log recorder})]
        (is (= :completed (:status result)))
        (is (= [0 1 2 3 4]
               (mapv :seq (:log result))))
        (is (= [:run-started
                :user-prompt
                :provider-requested
                :provider-responded
                :run-finished]
               (mapv :type (:log result))))
        (is (= "run-456" (:run-id (first (:log result)))))
        (is (= :completed (get-in (last (:log result)) [:data :status])))))))

(deftest agent-run-keeps-graded-local-evidence
  (let [times (atom (repeat "2026-10-31T10:00:00Z"))
        recorder (log/recorder {:run-id "run-789"
                                  :clock #(let [time (first @times)]
                                            (swap! times rest)
                                            time)})]
    (with-redefs [provider/complete! (constantly {:model "test-model"
                                                  :choices [{:finish_reason "stop"
                                                             :message {:content "pong"}}]})]
      (let [result (agent/run! {:system "Reply concisely."
                                :prompt "private prompt"
                                :tools {}
                                :log recorder})]
        (is (= {:prompt "private prompt"}
               (:evidence (second (:log result))))
        (is (= {:final-answer "pong"}
               (:evidence (last (:log result)))))))))
)
