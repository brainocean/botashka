(ns botashka.trace
  (:require [botashka.run :as run]
            [clojure.string :as str]))

(defn- step-details
  "Purely extracts descriptive detail lines for one trace step based on event type."
  [evt line-prefix]
  (let [type (:type evt)
        data (:data evt)
        evid (:evidence evt)]
    (case type
      :user-submitted
      [(str line-prefix " Input:   " (pr-str (subs (:prompt evid) 0 (min 60 (count (:prompt evid))))))]

      :model-requested
      [(str line-prefix " Command: :model/complete (tools: " (:tool-count data) ", msgs: " (:message-count data) ")")]

      :model-responded
      (let [tcs (get-in evid [:assistant-message :tool_calls])
            content (get-in evid [:assistant-message :content])]
        (cond-> [(str line-prefix " Model:   " (:model data) " (" (:finish-reason data) ")")]
          (seq tcs)
          (conj (str line-prefix " Propose: " (str/join ", " (map #(get-in % [:function :name]) tcs))))
          (and (empty? tcs) (seq content))
          (conj (str line-prefix " Answer:  " (pr-str (subs content 0 (min 60 (count content))))))))

      :tool-proposed
      (let [origin (or (:origin data) :model)
            decider (:decider data)
            conf (:confidence data)
            origin-str (cond-> (name origin)
                         decider (str " (" decider (when conf (str " " (int (* 100 conf)) "%")) ")"))]
        [(str line-prefix " Action:  :tool/execute [origin: " origin-str "] -> "
              (:tool data) " " (pr-str (:arguments evid)))])
      :tool-result
      [(str line-prefix " Result:  outcome: " (:outcome data)
            (when (:error-kind data) (str ", error: " (:error-kind data)))
            " [origin: " (or (:origin data) :model) "]")]

      :model-failed
      [(str line-prefix " Error:   " (:error-kind data) " (status: " (:status data) ")")]

      :limit-reached
      [(str line-prefix " Limit:   " (:kind data) " (turn " (:turn data) " of " (:value data) ")")]

      :run-finished
      [(str line-prefix " Command: :run/finish [status: " (:status data) "]")]

      [])))

(defn step->lines
  "Purely formats an indexed trace step [idx [event state-before state-after]]
   into a sequence of ASCII diagram lines."
  [total [idx [evt s-before s-after]]]
  (let [last? (= idx (dec total))
        first? (zero? idx)
        prefix (cond first? "┌──" last? "└──" :else "├──")
        line-prefix (if last? "   " "│  ")
        header (str prefix " [" idx "] " (:type evt) " (turn: " (:turn s-before) ")")
        state-line (if (not= (:status s-before) (:status s-after))
                     (str line-prefix " State:   " (:status s-before) " ──► " (:status s-after))
                     (str line-prefix " State:   " (:status s-before)))
        details (step-details evt line-prefix)
        separator (when-not last? ["│"])]
    (into [header state-line] (concat details separator))))

(defn trace-steps
  "Purely derives the list of [event state-before state-after] transitions from an event log."
  [events]
  (let [init (run/new-run {:run-id "trace" :max-turns 8})
        states (reductions run/apply-event init events)]
    (map vector events states (rest states))))

(defn trace-lines
  "Purely projects an agent run result into a vector of printable ASCII trace graph lines
   using a mapcat transducer."
  [result]
  (let [steps (trace-steps (:log result))
        total (count steps)
        lines-xf (comp (map-indexed vector)
                       (mapcat (partial step->lines total)))]
    (into [] lines-xf steps)))

(defn render-trace-graph
  "Renders an ASCII visualization of event log, state transitions, and actions/commands."
  [result]
  (let [lines (trace-lines result)]
    (println "\n======================== Execution Trace Graph ========================")
    (doseq [line lines]
      (println line))
    (println "======================================================================\n")))
