(ns botashka.agent
  (:refer-clojure :exclude [run!])
  (:require [botashka.model-client :as model-client]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

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

(defn- assistant-message
  "Extracts the first assistant message from a model completion."
  [response]
  (get-in response [:choices 0 :message]))

(defn- requested-tool-calls
  "Returns the tool calls proposed by an assistant message."
  [assistant-message]
  (:tool_calls assistant-message))

(defn- tool-name
  "Returns the tool name requested by a tool call."
  [tool-call]
  (get-in tool-call [:function :name]))

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

(defn- parse-tool-arguments
  "Decodes a model tool call's JSON arguments into EDN."
  [tool-call]
  (json/parse-string (get-in tool-call [:function :arguments]) true))

(defn- tool-error
  "Builds a structured tool failure from an exception."
  [kind tool-call error]
  {:error {:kind kind
           :tool (tool-name tool-call)
           :message (.getMessage error)}})

(defn- run-tool
  "Runs a registered tool or returns its structured failure."
  [tool-call arguments tools]
  (if-let [tool (get tools (tool-name tool-call))]
    (try
      ((:run tool) arguments)
      (catch Exception error
        (tool-error :tool-failed tool-call error)))
    {:error {:kind :unknown-tool
             :tool (tool-name tool-call)}}))

(defn- tool-result-message
  "Builds the model-compatible message for one tool result."
  [tool-call result]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (json/generate-string result)})

(defn- result-outcome
  "Classifies a structured tool result for the run event log."
  [result]
  (if (:error result) :error :ok))

(defn- append-observed-event
  "Appends one observed event using the executor-owned clock."
  [run clock type data evidence]
  (append-event run (clock) type data evidence))

(defn- append-observed-events
  "Appends observed event descriptions in order with executor-owned timestamps."
  [run clock events]
  (reduce (fn [run {:keys [type data evidence]}]
            (append-observed-event run clock type data evidence))
          run
          events))

(defn- execute-tool-command
  "Executes one tool command and appends its observed proposal and result events."
  [run clock tool-call]
  (try
    (let [arguments (parse-tool-arguments tool-call)
          tool (tool-name tool-call)
          run (append-observed-event run clock :tool-proposed
                                     {:turn (:turn run)
                                      :call-id (:id tool-call)
                                      :tool tool
                                      :argument-keys (sort (map name (keys arguments)))}
                                     {:arguments arguments})
          result (run-tool tool-call arguments (:tools run))]
      (append-observed-events run clock
                              [{:type :tool-result
                                :data {:turn (:turn run)
                                       :call-id (:id tool-call)
                                       :tool tool
                                       :outcome (result-outcome result)}
                                :evidence {:tool-message (tool-result-message tool-call result)}}]))
    (catch Exception error
      (let [result (tool-error :invalid-arguments tool-call error)]
        (append-observed-events run clock
                                [{:type :tool-result
                                  :data {:turn (:turn run)
                                         :call-id (:id tool-call)
                                         :tool (tool-name tool-call)
                                         :outcome :error
                                         :error-kind :invalid-arguments}
                                  :evidence {:tool-message (tool-result-message tool-call result)}}])))))

(defn- model-response-data
  "Builds the run event data summarizing one model response."
  [run response tool-calls]
  {:turn (:turn run)
   :model (:model response)
   :finish-reason (get-in response [:choices 0 :finish_reason])
   :tool-call-count (count tool-calls)})

(defn- run-started-event
  "Builds the observed event that starts one run."
  [run]
  {:type :run-started
   :data {:max-turns (:max-turns run)}
   :evidence nil})

(defn- user-submitted-event
  "Builds the observed event for the submitted user prompt."
  [prompt]
  {:type :user-submitted
   :data {:characters (count prompt)}
   :evidence {:prompt prompt}})

(defn- limit-reached-event
  "Builds the observed event for a run that reached its turn limit."
  [run]
  {:type :limit-reached
   :data {:turn (:turn run)
          :kind :max-turns
          :value (:max-turns run)}
   :evidence nil})

(defn- run-finished-event
  "Builds the observed terminal event for a run."
  [run status evidence]
  {:type :run-finished
   :data {:status status
          :turn (:turn run)}
   :evidence evidence})

(defn command-executor
  "Builds an imperative executor for model, tool, and run commands."
  [{:keys [complete! clock]}]
  (fn [run command]
    (case (:command command)
      :run/start
      (append-observed-events run clock
                              [(run-started-event run)
                               (user-submitted-event (:prompt command))])
      :model/complete
      (let [run (append-observed-event run clock :model-requested
                                       {:turn (:turn run)
                                        :message-count (count (:messages run))
                                        :tool-count (count (:tools run))}
                                       nil)
            response (complete! (:request command))
            message (assistant-message response)
            tool-calls (requested-tool-calls message)]
        (append-observed-event run clock :model-responded
                               (model-response-data run response tool-calls)
                               {:assistant-message message}))
      :tool/execute
      (execute-tool-command run clock (:tool-call command))
      :run/limit
      (append-observed-events run clock
                              [(limit-reached-event run)
                               (run-finished-event run :step-limit nil)])
      :run/finish
      (append-observed-events run clock
                              [(run-finished-event
                                run
                                (or (:terminal-status run) :completed)
                                {:final-answer (get-in (last (:messages run)) [:content])})]))))

(defn- terminal-result
  "Builds the public terminal result from a finished immutable run."
  [run]
  {:status (:terminal-status run)
   :text (get-in (last (:events run)) [:evidence :final-answer])
   :messages (:messages run)
   :log (:events run)})

(defn- default-clock
  "Returns the current instant as a run event timestamp."
  []
  (str (Instant/now)))

(defn- new-run-id
  "Creates a unique run identifier for the imperative shell."
  []
  (str (UUID/randomUUID)))

(defn- default-command-executor
  "Builds the command executor used by a normal local agent run."
  []
  (command-executor {:complete! model-client/complete!
                     :clock default-clock}))

(defn run!
  "Runs an event-driven agent state machine through an injected command executor.

   `next-command` is a pure driver. `command-executor!` owns model calls,
   tools, clocks, and all other imperative command dependencies."
  [{:keys [command-executor! next-command run-id system prompt tools max-turns]
    :or {command-executor! (default-command-executor)
         next-command model-driver
         max-turns 8}}]
  (let [run (new-run {:run-id (or run-id (new-run-id))
                      :system system
                      :tools tools
                      :max-turns max-turns})
        run (command-executor! run {:command :run/start
                                    :prompt prompt})]
    (loop [run run]
      (if-let [command (next-command run)]
        (recur (command-executor! run command))
        (terminal-result run)))))
