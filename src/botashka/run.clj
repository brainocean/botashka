(ns botashka.run)

(defn build-model-context
  "Purely projects the model invocation parameters (context projection) from current run state."
  [run]
  {:system (:system run)
   :messages (:messages run)
   :tools (mapv :schema (vals (:tools run)))})

(defn new-run
  "Creates an unstarted immutable agent run from explicit options."
  [{:keys [run-id system tools max-turns messages]}]
  {:id run-id
   :system system
   :tools tools
   :max-turns max-turns
   :turn 0
   :status :new
   :messages (or messages [])
   :events []
   :pending-tool-calls []})

(defn- requested-tool-calls
  "Returns the tool calls proposed by an assistant message."
  [assistant-message]
  (:tool_calls assistant-message))

(defn- remove-tool-call
  "Removes the completed tool call from the run's pending calls."
  [tool-calls tool-call-id]
  (vec (remove #(= tool-call-id (:id %)) tool-calls)))

(def ^:private event-transitions
  {:run-started
   (fn [run _data _evidence]
     (assoc run :status :awaiting-user))

   :user-submitted
   (fn [run _data {:keys [prompt]}]
     (-> run
         (update :messages conj {:role "user" :content prompt})
         (assoc :status :awaiting-model)))

   :model-requested
   (fn [run _data _evidence]
     (assoc run :status :awaiting-model-response))

   :model-responded
   (fn [run _data {:keys [assistant-message]}]
     (let [tool-calls (requested-tool-calls assistant-message)]
       (cond-> (-> run
                   (update :messages conj assistant-message)
                   (assoc :pending-tool-calls (vec tool-calls)))
         (seq tool-calls) (assoc :status :awaiting-tools)
         (empty? tool-calls) (assoc :status :ready-to-finish))))

   :model-failed
   (fn [run _data _evidence]
     (assoc run :status :ready-to-finish
                :terminal-status :provider-error))

   :tool-proposed
   (fn [run _data _evidence]
     run)

   :tool-result
   (fn [run data evidence]
     (let [origin (get data :origin :model)
           model-origin? (= origin :model)
           pending-tool-calls (if model-origin?
                                (remove-tool-call (:pending-tool-calls run)
                                                  (:call-id data))
                                (:pending-tool-calls run))
           next-turn (if (or (not model-origin?) (empty? pending-tool-calls))
                       (inc (:turn run))
                       (:turn run))]
       (cond-> (-> run
                   (assoc :pending-tool-calls pending-tool-calls
                          :turn next-turn))
         (:tool-message evidence) (update :messages conj (:tool-message evidence))
         (seq pending-tool-calls) (assoc :status :awaiting-tools)
         (empty? pending-tool-calls) (assoc :status :awaiting-model))))

   :limit-reached
   (fn [run _data _evidence]
     (assoc run :status :ready-to-finish
                :terminal-status :step-limit))

   :run-finished
   (fn [run data _evidence]
     (assoc run :status :finished
                :terminal-status (:status data)))})

(defn apply-event
  "Purely derives the next run state from one canonical run event using data-driven transitions."
  [run {:keys [type data evidence]}]
  (if-let [transition (get event-transitions type)]
    (transition run data evidence)
    run))

(defn append-event
  "Purely envelopes, appends, and applies one canonical run event."
  [run occurred-at type data evidence]
  (let [event (cond-> {:run-id (:id run)
                       :seq (count (:events run))
                       :at occurred-at
                       :type type
                       :data data}
                evidence (assoc :evidence evidence))]
    (-> run
        (update :events conj event)
        (apply-event event))))

(def ^:private status-action-rules
  {:awaiting-model
   (fn [run]
     (if (>= (:turn run) (:max-turns run))
       {:command :run/limit}
       (merge {:command :model/complete
               :origin :policy}
              (build-model-context run))))

   :awaiting-tools
   (fn [run]
     (when-let [tc (first (:pending-tool-calls run))]
       {:command :tool/execute
        :tool-call tc
        :origin :model}))

   :ready-to-finish
   (constantly {:command :run/finish})})

(defn default-action-policy
  "Default action policy: drives model completions and processes pending assistant tool calls
   using data-driven status action rules."
  [run]
  (when-let [rule (get status-action-rules (:status run))]
    (rule run)))

(defn first-applicable-action-policy
  "Combines multiple action policies by evaluating them in order.
   Returns the command from the first policy that returns non-nil."
  [& policies]
  (fn [run]
    (some #(% run) policies)))

(defn action-proposal
  "Builds an action proposal candidate with origin and confidence metadata."
  [{:keys [command proposed-by confidence evidence]}]
  {:command command
   :proposed-by (or proposed-by :host)
   :confidence (or confidence 1.0)
   :evidence evidence})

(defn arbitrate-proposals
  "Arbitrates among candidate action proposals.
   Picks the proposal with highest confidence, breaking ties by earliest proposal."
  [proposals]
  (when (seq proposals)
    (:command (apply max-key :confidence proposals))))

(defn terminal-result
  "Builds the public terminal result from a finished immutable run."
  [run]
  {:status (:terminal-status run)
   :text (or (get-in (last (:events run)) [:evidence :final-answer])
             (get-in (last (:events run)) [:evidence :result])
             (get-in (last (:events run)) [:data :result]))
   :messages (:messages run)
   :log (:events run)})

(defn run-started-event
  "Builds the observed event that starts one run."
  [run]
  {:type :run-started
   :data {:max-turns (:max-turns run)}
   :evidence nil})

(defn user-submitted-event
  "Builds the observed event for the submitted user prompt."
  [prompt]
  {:type :user-submitted
   :data {:characters (count prompt)}
   :evidence {:prompt prompt}})

(defn limit-reached-event
  "Builds the observed event for a run that reached its turn limit."
  [run]
  {:type :limit-reached
   :data {:turn (:turn run)
          :kind :max-turns
          :value (:max-turns run)}
   :evidence nil})

(defn run-finished-event
  "Builds the observed terminal event for a run."
  [run status evidence]
  {:type :run-finished
   :data {:status status
          :turn (:turn run)}
   :evidence evidence})

(defn model-requested-event
  "Builds the observed event for an outgoing model completion request."
  ([run]
   (model-requested-event run nil :policy))
  ([run model-request]
   (model-requested-event run model-request :policy))
  ([run model-request origin]
   (let [msgs (or (:messages model-request) (:messages run))
         tools (or (:tools model-request) (mapv :schema (vals (:tools run))))]
     {:type :model-requested
      :data (cond-> {:turn (:turn run)
                     :origin (or origin :policy)
                     :message-count (count msgs)
                     :tool-count (count tools)}
              (:model model-request) (assoc :model (:model model-request)))
      :evidence nil})))

(defn model-responded-event
  "Builds the observed event for a successful model completion response."
  ([run response assistant-message tool-calls]
   (model-responded-event run response assistant-message tool-calls :policy))
  ([run response assistant-message tool-calls origin]
   {:type :model-responded
    :data {:turn (:turn run)
           :origin (or origin :policy)
           :model (:model response)
           :finish-reason (get-in response [:choices 0 :finish_reason])
           :tool-call-count (count tool-calls)}
    :evidence {:assistant-message assistant-message}}))

(defn model-failed-event
  "Builds the observed event for a failed model completion request."
  ([run error]
   (model-failed-event run error :policy))
  ([run error origin]
   {:type :model-failed
    :data {:turn (:turn run)
           :origin (or origin :policy)
           :error-kind (:kind error)
           :status (:status error)}
    :evidence {:error error}}))

(defn tool-proposed-event
  ([run tool-call arguments-or-raw]
   (tool-proposed-event run tool-call arguments-or-raw (or (:origin tool-call) :model)))
  ([run tool-call arguments-or-raw origin]
   (let [tool (or (get-in tool-call [:function :name]) (:tool tool-call))
         call-id (or (:id tool-call) (:call-id tool-call))
         parsed? (map? arguments-or-raw)]
     {:type :tool-proposed
      :data (cond-> {:turn (:turn run)
                     :call-id call-id
                     :tool tool
                     :origin origin}
              parsed? (assoc :argument-keys (sort (map name (keys arguments-or-raw)))))
      :evidence (if parsed?
                  {:arguments arguments-or-raw}
                  {:raw-arguments arguments-or-raw})})))

(defn tool-result-event
  "Builds the observed event for a tool execution result."
  ([run tool-call result tool-message]
   (tool-result-event run tool-call result tool-message (or (:origin tool-call) :model)))
  ([run tool-call result tool-message origin]
   (let [outcome (if (:error result) :error :ok)
         error-kind (get-in result [:error :kind])
         call-id (or (:id tool-call) (:call-id tool-call))
         tool (or (get-in tool-call [:function :name]) (:tool tool-call))]
     {:type :tool-result
      :data (cond-> {:turn (:turn run)
                     :call-id call-id
                     :tool tool
                     :origin origin
                     :outcome outcome}
              error-kind (assoc :error-kind error-kind))
      :evidence (cond-> {:result result}
                  tool-message (assoc :tool-message tool-message))})))
