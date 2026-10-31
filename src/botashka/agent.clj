(ns botashka.agent
  (:refer-clojure :exclude [run!])
  (:require [botashka.provider :as provider]
            [cheshire.core :as json])
  (:import [java.time Instant]
           [java.util UUID]))

(defn- provider-request
  "Builds the provider request from the current derived conversation and tools."
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
  "Extracts the first assistant message from a provider completion."
  [response]
  (get-in response [:choices 0 :message]))

(defn- requested-tool-calls
  "Returns the tool calls proposed by an assistant message."
  [assistant-message]
  (:tool_calls assistant-message))

(defn- tool-name
  "Returns the provider name requested by a tool call."
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
                        (assoc :status :awaiting-provider))
    :provider-requested (assoc run :status :awaiting-provider-response)
    :provider-responded
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
        (empty? pending-tool-calls) (assoc :status :awaiting-provider)))
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

(defn next-command
  "Purely decides the one permitted next command for the current run state."
  [run]
  (case (:status run)
    :awaiting-provider (if (= (:turn run) (:max-turns run))
                         {:command :run/limit}
                         {:command :provider/complete
                          :request (provider-request run)})
    :awaiting-tools {:command :tool/execute
                     :tool-call (first (:pending-tool-calls run))}
    :ready-to-finish {:command :run/finish}
    nil))

(defn- parse-tool-arguments
  "Decodes a provider tool call's JSON arguments into EDN."
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
  "Builds the provider-compatible message for one tool result."
  [tool-call result]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (json/generate-string result)})

(defn- result-outcome
  "Classifies a structured tool result for the run event log."
  [result]
  (if (:error result) :error :ok))

(defn- append-observed-event!
  "Reads the shell clock and appends one observed event to an immutable run."
  [run clock type data evidence]
  (append-event run (clock) type data evidence))

(defn- execute-tool-command!
  "Executes one tool command and appends its observed proposal and result events."
  [run clock tool-call]
  (try
    (let [arguments (parse-tool-arguments tool-call)
          tool (tool-name tool-call)
          run (append-observed-event! run clock :tool-proposed
                                      {:turn (:turn run)
                                       :call-id (:id tool-call)
                                       :tool tool
                                       :argument-keys (sort (map name (keys arguments)))}
                                      {:arguments arguments})
          result (run-tool tool-call arguments (:tools run))]
      (append-observed-event! run clock :tool-result
                              {:turn (:turn run)
                               :call-id (:id tool-call)
                               :tool tool
                               :outcome (result-outcome result)}
                              {:tool-message (tool-result-message tool-call result)}))
    (catch Exception error
      (let [result (tool-error :invalid-arguments tool-call error)]
        (append-observed-event! run clock :tool-result
                                {:turn (:turn run)
                                 :call-id (:id tool-call)
                                 :tool (tool-name tool-call)
                                 :outcome :error
                                 :error-kind :invalid-arguments}
                                {:tool-message (tool-result-message tool-call result)})))))

(defn- provider-response-data
  "Builds the run event data summarizing one provider response."
  [run response tool-calls]
  {:turn (:turn run)
   :model (:model response)
   :finish-reason (get-in response [:choices 0 :finish_reason])
   :tool-call-count (count tool-calls)})

(defn- execute-command!
  "Interprets one effect command and appends its observed facts to the run."
  [run clock complete! command]
  (case (:command command)
    :provider/complete
    (let [run (append-observed-event! run clock :provider-requested
                                      {:turn (:turn run)
                                       :message-count (count (:messages run))
                                       :tool-count (count (:tools run))}
                                      nil)
          response (complete! (:request command))
          message (assistant-message response)
          tool-calls (requested-tool-calls message)]
      (append-observed-event! run clock :provider-responded
                              (provider-response-data run response tool-calls)
                              {:assistant-message message}))
    :tool/execute (execute-tool-command! run clock (:tool-call command))
    :run/limit
    (let [run (append-observed-event! run clock :limit-reached
                                      {:turn (:turn run)
                                       :kind :max-turns
                                       :value (:max-turns run)}
                                      nil)]
      (append-observed-event! run clock :run-finished
                              {:status :step-limit
                               :turn (:turn run)}
                              nil))
    :run/finish
    (append-observed-event! run clock :run-finished
                            {:status (or (:terminal-status run) :completed)
                             :turn (:turn run)}
                            {:final-answer (get-in (last (:messages run)) [:content])})))

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

(defn run!
  "Runs an event-driven OpenAI Chat Completions agent state machine.

   `complete!` and `clock` are shell dependencies. The run state, events, and
   next-command decisions remain immutable data."
  [{:keys [complete! clock run-id system prompt tools max-turns]
    :or {complete! provider/complete!
         clock default-clock
         max-turns 8}}]
  (let [run (new-run {:run-id (or run-id (new-run-id))
                      :system system
                      :tools tools
                      :max-turns max-turns})
        run (append-observed-event! run clock :run-started {:max-turns max-turns} nil)
        run (append-observed-event! run clock :user-submitted {:characters (count prompt)}
                                    {:prompt prompt})]
    (loop [run run]
      (if-let [command (next-command run)]
        (recur (execute-command! run clock complete! command))
        (terminal-result run)))))
