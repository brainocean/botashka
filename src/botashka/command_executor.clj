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

(defn- append-observed-event
  "Appends one observed event using the executor-owned clock."
  ([run clock event]
   (run/append-event run (clock) (:type event) (:data event) (:evidence event)))
  ([run clock type data evidence]
   (run/append-event run (clock) type data evidence)))
(defn- append-observed-events
  "Appends observed event descriptions in order with executor-owned timestamps."
  [run clock events]
  (reduce (fn [run {:keys [type data evidence]}]
            (append-observed-event run clock type data evidence))
          run
          events))

(defn- normalize-tool-command
  "Normalizes direct tool commands and model-proposed tool calls into a unified shape."
  [command]
  (let [explicit-call (:tool-call command)
        origin (or (:origin command) (:origin explicit-call)
                   (if explicit-call :model :host))
        tool-call (or explicit-call
                      (let [tool-name (or (:tool command) (get-in command [:function :name]))
                            args (:arguments command)
                            args-str (if (string? args)
                                       args
                                       (json/generate-string (or args {})))]
                        {:id (str "call-" (java.util.UUID/randomUUID))
                         :function {:name tool-name
                                    :arguments args-str}}))]
    (assoc tool-call :origin origin)))

(defn- execute-tool-command
  "Executes one tool command and appends its observed proposal and result events."
  [run clock command]
  (let [tool-call (normalize-tool-command command)
        origin (:origin tool-call)
        raw-arguments (get-in tool-call [:function :arguments])
        parsed (try
                 {:arguments (json/parse-string raw-arguments true)}
                 (catch Exception error
                   {:error error}))
        run (if-let [args (:arguments parsed)]
              (append-observed-event run clock
                                     (run/tool-proposed-event run tool-call args origin))
              (append-observed-event run clock
                                     (run/tool-proposed-event run tool-call raw-arguments origin)))
        result (if-let [args (:arguments parsed)]
                 (run-tool tool-call args (:tools run))
                 (tool-error :invalid-arguments tool-call (:error parsed)))
        tool-msg (when (= origin :model)
                   (tool-result-message tool-call result))]
    (append-observed-event run clock
                           (run/tool-result-event run tool-call result tool-msg origin))))

(defn command-executor
  "Builds an imperative executor for model, tool, and run commands."
  [{:keys [complete! clock authorize]
    :or {authorize (constantly :allow)}}]
  (fn [run command]
    (let [auth (authorize run command)]
      (if (and (not= auth :allow)
               (not= (:decision auth) :allow)
               (not (true? auth)))
        (let [reason (or (when (map? auth) (:reason auth))
                         "Command rejected by authorization policy")]
          (if (= (:command command) :tool/execute)
            (let [tool-call (normalize-tool-command command)
                  origin (:origin tool-call)
                  arguments (try
                              (json/parse-string (get-in tool-call [:function :arguments]) true)
                              (catch Exception _ nil))
                  run (append-observed-event run clock
                                             (run/tool-proposed-event run tool-call (or arguments "") origin))
                  err-result {:error {:kind :action-rejected :reason reason}}
                  tool-msg (when (= origin :model)
                             (tool-result-message tool-call err-result))]
              (append-observed-event run clock
                                     (run/tool-result-event run tool-call err-result tool-msg origin)))
            (append-observed-events run clock
                                    [(run/run-finished-event run :rejected {:reason reason})])))
        (case (:command command)
          :run/start
          (append-observed-events run clock
                                  [(run/run-started-event run)
                                   (run/user-submitted-event (:prompt command))])
          :model/complete
          (let [model-req (or (:request command) (dissoc command :command :origin))
                origin (or (:origin command) :policy)
                run (append-observed-event run clock (run/model-requested-event run model-req origin))
                response (complete! model-req)]
            (if-let [err (:error response)]
              (append-observed-event run clock (run/model-failed-event run err origin))
              (let [message (assistant-message response)
                    tool-calls (:tool_calls message)]
                (append-observed-event run clock
                                       (run/model-responded-event run response message tool-calls origin)))))
          :tool/execute
          (execute-tool-command run clock command)
          :run/limit
          (append-observed-events run clock
                                  [(run/limit-reached-event run)
                                   (run/run-finished-event run :step-limit nil)])
          :run/finish
          (let [result (or (:result command) (get-in (last (:messages run)) [:content]))
                terminal-status (or (:terminal-status run) :completed)]
            (append-observed-events run clock
                                    [(run/run-finished-event
                                      run
                                      terminal-status
                                      {:final-answer result})])))))))

(defn default-command-executor
  "Builds the command executor used by a normal local agent run."
  ([]
   (default-command-executor {}))
  ([options]
   (command-executor (merge {:complete! model-client/complete!
                             :clock #(str (Instant/now))}
                            options))))
