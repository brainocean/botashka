(ns botashka.clef-policy
  (:require [botashka.clef-client :as clef-client]
            [clojure.string :as str]))

(defn- format-run-state-summary
  "Purely builds a descriptive state string from the current run state for Clef."
  [run]
  (let [msgs (:messages run)
        last-msg (last msgs)
        events (:events run)
        last-event (last events)
        user-prompt (->> msgs
                         (filter #(= (:role %) "user"))
                         last
                         :content)]
    (str "User request: " (pr-str (or user-prompt "")) ". "
         (when last-event
           (str "Latest event was " (:type last-event)
                (when-let [err (get-in last-event [:data :error])]
                  (str " with error: " (pr-str err)))
                (when-let [outcome (get-in last-event [:data :outcome])]
                  (str " with outcome: " outcome))
                (when-let [tool (get-in last-event [:data :tool])]
                  (str " for tool: " tool))
                ". "))
         (when (and last-msg (not= (:role last-msg) "user"))
           (str "Latest message from " (:role last-msg) ": "
                (pr-str (subs (or (:content last-msg) "") 0 (min 150 (count (or (:content last-msg) ""))))))))))

(defn clef-action-policy
  "Creates an ActionPolicy powered by a Clef decision model.
   When the run status is :awaiting-model and not at max-turns:
     1. Queries Clef with the summarized run state and available tool candidates + 'no_tool'.
     2. If Clef selects a tool with probability >= min-probability and choice != 'no_tool':
        Returns an action proposal or direct command {:command :tool/execute, :origin :clef, :tool choice}.
     3. If Clef chooses 'no_tool' or confidence is below threshold, returns nil
        (allowing fallback to default-action-policy or subsequent policies)."
  ([] (clef-action-policy {}))
  ([{:keys [decide-fn arguments-fn min-probability candidate-tools]
     :or {decide-fn clef-client/decide!
          arguments-fn (constantly {})
          min-probability 0.75}}]
   (fn [run]
     (let [last-event (last (:events run))]
       (when (and (= (:status run) :awaiting-model)
                  (< (:turn run) (:max-turns run))
                  (not (and (= (:type last-event) :tool-result)
                            (= (get-in last-event [:data :origin]) :clef))))
         (let [tools (or candidate-tools (vec (keys (:tools run))))
               criteria (conj (mapv name tools) "no_tool")
               state-summary (format-run-state-summary run)
               decision (decide-fn {:state state-summary
                                    :criteria criteria})]
           (when-not (:error decision)
             (let [{:keys [choice probability confidence]} decision]
               (when (and (seq choice)
                          (not= choice "no_tool")
                          (>= (or probability 0.0) min-probability))
                 {:command :tool/execute
                  :tool choice
                  :origin :clef
                  :confidence (or confidence probability)
                  :arguments (arguments-fn run choice)})))))))))
