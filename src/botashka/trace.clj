(ns botashka.trace
  (:require [botashka.run :as run]
            [clojure.string :as str]))

(defn render-trace-graph
  "Renders an ASCII visualization of event log, state transitions, and actions/commands."
  [result]
  (let [events (:log result)
        init (run/new-run {:run-id "trace" :max-turns 8})
        states (reductions run/apply-event init events)
        steps (map vector events states (rest states))
        total (count steps)]
    (println "\n======================== Execution Trace Graph ========================")
    (doseq [[idx [evt s-before s-after]] (map-indexed vector steps)]
      (let [last? (= idx (dec total))
            first? (zero? idx)
            prefix (cond first? "┌──" last? "└──" :else "├──")
            line-prefix (if last? "   " "│  ")
            status-before (:status s-before)
            status-after (:status s-after)
            type (:type evt)
            data (:data evt)
            evid (:evidence evt)]
        (println (str prefix " [" idx "] " type " (turn: " (:turn s-before) ")"))
        (if (not= status-before status-after)
          (println (str line-prefix " State:   " status-before " ──► " status-after))
          (println (str line-prefix " State:   " status-before)))
        (case type
          :user-submitted
          (println (str line-prefix " Input:   " (pr-str (subs (:prompt evid) 0 (min 60 (count (:prompt evid)))))))
          :model-requested
          (println (str line-prefix " Command: :model/complete (tools: " (:tool-count data) ", msgs: " (:message-count data) ")"))
          :model-responded
          (let [tcs (get-in evid [:assistant-message :tool_calls])
                content (get-in evid [:assistant-message :content])]
            (println (str line-prefix " Model:   " (:model data) " (" (:finish-reason data) ")"))
            (if (seq tcs)
              (println (str line-prefix " Propose: " (str/join ", " (map #(get-in % [:function :name]) tcs))))
              (when (seq content)
                (println (str line-prefix " Answer:  " (pr-str (subs content 0 (min 60 (count content)))))))))
          :tool-proposed
          (println (str line-prefix " Action:  :tool/execute [origin: " (or (:origin data) :model) "] -> "
                        (:tool data) " " (pr-str (:arguments evid))))
          :tool-result
          (println (str line-prefix " Result:  outcome: " (:outcome data)
                        (when (:error-kind data) (str ", error: " (:error-kind data)))
                        " [origin: " (or (:origin data) :model) "]"))
          :model-failed
          (println (str line-prefix " Error:   " (:error-kind data) " (status: " (:status data) ")"))
          :limit-reached
          (println (str line-prefix " Limit:   " (:kind data) " (turn " (:turn data) " of " (:value data) ")"))
          :run-finished
          (println (str line-prefix " Command: :run/finish [status: " (:status data) "]"))
          nil)
        (when-not last?
          (println "│"))))
    (println "======================================================================\n")))
