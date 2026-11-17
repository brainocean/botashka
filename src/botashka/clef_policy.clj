(ns botashka.clef-policy
  (:require [botashka.clef-client :as clef-client]
            [clojure.string :as str]))

(defn- initial-user-prompt
  "Finds the original user-submitted prompt, ignoring synthetic observation messages."
  [run]
  (or (->> (:events run)
           (filter #(= (:type %) :user-submitted))
           last
           :evidence
           :prompt)
      (->> (:messages run)
           (filter (fn [m]
                     (and (= (:role m) "user")
                          (not (str/starts-with? (str (:content m)) "[Observation:"))
                          (not (str/starts-with? (str (:content m)) "[System Context:")))))
           first
           :content)
      ""))

(defn- format-run-state-summary
  "Purely builds a descriptive state string from the current run state for Clef."
  [run]
  (let [user-prompt (initial-user-prompt run)
        last-msg (last (:messages run))
        last-event (last (:events run))]
    (str "User request: " (pr-str user-prompt) ". "
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

(defn default-clef-arguments-extractor
  "Infers sensible default arguments for known tools when selected by Clef.
   Returns nil if required arguments cannot be reliably extracted from the user's intent."
  [run choice]
  (let [user-prompt (initial-user-prompt run)]
    (case choice
      "search_text" {:query (if (str/blank? user-prompt) "" user-prompt)}
      "read_file" (when-let [detected (or (re-find #"(?i)readme(\.md)?" user-prompt)
                                          (re-find #"[a-zA-Z0-9_\-\./]+\.[a-zA-Z0-9]+" user-prompt))]
                    (let [path (if (vector? detected) (first detected) (str detected))]
                      (if (re-find #"(?i)readme" path)
                        {:path "README.md"}
                        {:path path})))
      nil)))

(defn clef-action-policy
  "Creates a pure ActionPolicy driven by explicit decision events.
   
   Behavior:
     1. When (:last-decision run) is newly recorded (from a prior :decision-responded event):
        - If decision.choice is a valid tool, probability >= min-probability, AND arguments can be extracted:
          Returns {:command :tool/execute, :origin :decision, :tool choice, :arguments args}.
        - Otherwise returns nil (falling back to default model policy).
     2. When in :awaiting-model, no decision has run for the current turn yet, and last event was not a tool result:
        Returns {:command :decision/complete, :decider ..., :criteria ..., :state ...}
        which instructs the imperative shell to query Clef and record canonical decision events.
     3. Otherwise returns nil."
  ([] (clef-action-policy {}))
  ([{:keys [decide-fn arguments-fn min-probability candidate-tools decider]
     :or {decide-fn clef-client/decide!
          arguments-fn default-clef-arguments-extractor
          min-probability 0.75
          decider "clef-flash"}}]
   (fn [run]
     (when (and (= (:status run) :awaiting-model)
                (< (:turn run) (:max-turns run)))
       (let [last-event (last (:events run))
             last-decision (:last-decision run)]
         (cond
           ;; Step 2: We just got a decision response
           (and (= (:type last-event) :decision-responded)
                last-decision)
           (let [{:keys [choice probability confidence decider]} last-decision
                 effective-prob (or probability confidence 0.0)
                 args (when (and (seq choice) (not= choice "no_tool"))
                        (arguments-fn run choice))]
             (when (and (seq choice)
                        (not= choice "no_tool")
                        (>= effective-prob min-probability)
                        ;; Must have valid extracted arguments, otherwise fall back to LLM!
                        (map? args))
               {:command :tool/execute
                :tool choice
                :origin :decision
                :decider decider
                :confidence (or confidence probability)
                :probability probability
                :arguments args}))

           ;; Step 1: Initial decision query when awaiting model and haven't run decision yet this turn
           (and (not= (:type last-event) :decision-responded)
                (not (and (= (:type last-event) :tool-result)
                          (= (get-in last-event [:data :origin]) :decision))))
           (let [tools (or candidate-tools (vec (keys (:tools run))))
                 criteria (conj (mapv name tools) "no_tool")
                 state-summary (format-run-state-summary run)]
             {:command :decision/complete
              :decider decider
              :criteria criteria
              :state state-summary
              :decide-fn decide-fn})

           :else nil))))))
