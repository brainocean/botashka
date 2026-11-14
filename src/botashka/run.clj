(ns botashka.run)

(defn- model-request
  "Builds the model request from the current derived conversation and tools."
  [run]
  {:system (:system run)
   :messages (:messages run)
   :tools (mapv :schema (vals (:tools run)))})

(defn new-run
  "Creates an unstarted immutable agent run from explicit options."
  [{:keys [run-id system tools max-turns]}]
  {:id run-id
   :system system
   :tools tools
   :max-turns max-turns
   :turn 0
   :status :new
   :messages []
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

(defn apply-event
  "Purely derives the next run state from one canonical run event."
  [run {:keys [type data evidence]}]
  (case type
    :run-started (assoc run :status :awaiting-user)
    :user-submitted (-> run
                        (update :messages conj {:role "user"
                                                :content (:prompt evidence)})
                        (assoc :status :awaiting-model))
    :model-requested (assoc run :status :awaiting-model-response)
    :model-responded
    (let [message (:assistant-message evidence)
          tool-calls (requested-tool-calls message)]
      (cond-> (-> run
                  (update :messages conj message)
                  (assoc :pending-tool-calls (vec tool-calls)))
        (seq tool-calls) (assoc :status :awaiting-tools)
        (empty? tool-calls) (assoc :status :ready-to-finish)))
    :model-failed (assoc run :status :ready-to-finish
                         :terminal-status :provider-error)
    :tool-proposed run
    :tool-result
    (let [pending-tool-calls (remove-tool-call (:pending-tool-calls run)
                                               (:call-id data))
          next-turn (if (empty? pending-tool-calls)
                      (inc (:turn run))
                      (:turn run))]
      (cond-> (-> run
                  (update :messages conj (:tool-message evidence))
                  (assoc :pending-tool-calls pending-tool-calls
                         :turn next-turn))
        (seq pending-tool-calls) (assoc :status :awaiting-tools)
        (empty? pending-tool-calls) (assoc :status :awaiting-model)))
    :limit-reached (assoc run :status :ready-to-finish
                          :terminal-status :step-limit)
    :run-finished (assoc run :status :finished
                         :terminal-status (:status data))
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

(defn model-driver
  "Purely chooses the next command for a run driven by model completions."
  [run]
  (case (:status run)
    :awaiting-model (if (= (:turn run) (:max-turns run))
                      {:command :run/limit}
                      {:command :model/complete
                       :request (model-request run)})
    :awaiting-tools {:command :tool/execute
                     :tool-call (first (:pending-tool-calls run))}
    :ready-to-finish {:command :run/finish}
    nil))

(defn terminal-result
  "Builds the public terminal result from a finished immutable run."
  [run]
  {:status (:terminal-status run)
   :text (get-in (last (:events run)) [:evidence :final-answer])
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
  [run]
  {:type :model-requested
   :data {:turn (:turn run)
          :message-count (count (:messages run))
          :tool-count (count (:tools run))}
   :evidence nil})

(defn model-responded-event
  "Builds the observed event for a successful model completion response."
  [run response assistant-message tool-calls]
  {:type :model-responded
   :data {:turn (:turn run)
          :model (:model response)
          :finish-reason (get-in response [:choices 0 :finish_reason])
          :tool-call-count (count tool-calls)}
   :evidence {:assistant-message assistant-message}})

(defn model-failed-event
  "Builds the observed event for a failed model completion request."
  [run error]
  {:type :model-failed
   :data {:turn (:turn run)
          :error-kind (:kind error)
          :status (:status error)}
   :evidence {:error error}})

(defn tool-proposed-event
  "Builds the observed event for a proposed tool call."
  [run tool-call arguments-or-raw]
  (let [tool (get-in tool-call [:function :name])
        parsed? (map? arguments-or-raw)]
    {:type :tool-proposed
     :data (cond-> {:turn (:turn run)
                    :call-id (:id tool-call)
                    :tool tool}
             parsed? (assoc :argument-keys (sort (map name (keys arguments-or-raw)))))
     :evidence (if parsed?
                 {:arguments arguments-or-raw}
                 {:raw-arguments arguments-or-raw})}))

(defn tool-result-event
  "Builds the observed event for a tool execution result."
  [run tool-call result tool-message]
  (let [outcome (if (:error result) :error :ok)
        error-kind (get-in result [:error :kind])]
    {:type :tool-result
     :data (cond-> {:turn (:turn run)
                    :call-id (:id tool-call)
                    :tool (get-in tool-call [:function :name])
                    :outcome outcome}
             error-kind (assoc :error-kind error-kind))
     :evidence {:tool-message tool-message}}))
