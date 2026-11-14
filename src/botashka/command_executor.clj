(ns botashka.command-executor
  (:require [botashka.model-client :as model-client]
            [botashka.run :as run]
            [cheshire.core :as json])
  (:import [java.time Instant]))

(defn- assistant-message
  "Extracts the first assistant message from a model completion."
  [response]
  (get-in response [:choices 0 :message]))

(defn- tool-name
  "Returns the tool name requested by a tool call."
  [tool-call]
  (get-in tool-call [:function :name]))

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
  (run/append-event run (clock) type data evidence))

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

(defn command-executor
  "Builds an imperative executor for model, tool, and run commands."
  [{:keys [complete! clock]}]
  (fn [run command]
    (case (:command command)
      :run/start
      (append-observed-events run clock
                              [(run/run-started-event run)
                               (run/user-submitted-event (:prompt command))])
      :model/complete
      (let [run (append-observed-event run clock :model-requested
                                       {:turn (:turn run)
                                        :message-count (count (:messages run))
                                        :tool-count (count (:tools run))}
                                       nil)
            response (complete! (:request command))]
        (if-let [err (:error response)]
          (append-observed-event run clock :model-failed
                                 {:turn (:turn run)
                                  :error-kind (:kind err)
                                  :status (:status err)}
                                 {:error err})
          (let [message (assistant-message response)
                tool-calls (:tool_calls message)]
            (append-observed-event run clock :model-responded
                                   (model-response-data run response tool-calls)
                                   {:assistant-message message}))))
      :tool/execute
      (execute-tool-command run clock (:tool-call command))
      :run/limit
      (append-observed-events run clock
                              [(run/limit-reached-event run)
                               (run/run-finished-event run :step-limit nil)])
      :run/finish
      (append-observed-events run clock
                              [(run/run-finished-event
                                run
                                (or (:terminal-status run) :completed)
                                {:final-answer (get-in (last (:messages run)) [:content])})]))))

(defn default-command-executor
  "Builds the command executor used by a normal local agent run."
  []
  (command-executor {:complete! model-client/complete!
                     :clock #(str (Instant/now))}))
