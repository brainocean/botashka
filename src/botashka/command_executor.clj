(ns botashka.command-executor
  (:require [botashka.bus :as bus]
            [botashka.model-client :as model-client]
            [botashka.run :as run]
            [cheshire.core :as json]
            [clojure.core.match :refer [match]])
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

(defn- host-observation-message
  "Builds a host observation message when a tool was executed by a decision/host policy,
   allowing the model to immediately consume the result without duplicate tool execution."
  [tool-call result]
  {:role "user"
   :content (str "[System Observation: Tool `" (tool-name tool-call) "` was executed with result:\n"
                 (json/generate-string result)
                 "\nPlease use this information to answer the user request directly without repeating this tool call.]")})

(defn- append-observed-event
  "Appends one observed event using the executor-owned clock and notifies bus / on-event if present."
  ([run clock on-event bus event]
   (let [new-run (run/append-event run (clock) (:type event) (:data event) (:evidence event))
         ev (last (:events new-run))]
     (when bus
       (bus/publish-canonical! bus ev))
     (when on-event
       (on-event ev))
     new-run))
  ([run clock on-event bus type data evidence]
   (append-observed-event run clock on-event bus {:type type :data data :evidence evidence})))

(defn- append-observed-events
  "Appends observed event descriptions in order with executor-owned timestamps."
  [run clock on-event bus events]
  (reduce (fn [r event]
            (append-observed-event r clock on-event bus event))
          run
          events))
(defn- normalize-tool-command
  "Normalizes direct tool commands and model-proposed tool calls into a unified shape."
  [command]
  (let [explicit-call (:tool-call command)
        origin (or (:origin command) (:origin explicit-call)
                   (if explicit-call :model :host))
        decider (or (:decider command) (:decider explicit-call))
        confidence (or (:confidence command) (:confidence explicit-call))
        probability (or (:probability command) (:probability explicit-call))
        tool-call (or explicit-call
                      (let [tool-name (or (:tool command) (get-in command [:function :name]))
                            args (:arguments command)
                            args-str (if (string? args)
                                       args
                                       (json/generate-string (or args {})))]
                        {:id (str "call-" (java.util.UUID/randomUUID))
                         :function {:name tool-name
                                    :arguments args-str}}))]
    (cond-> (assoc tool-call :origin origin)
      decider (assoc :decider decider)
      confidence (assoc :confidence confidence)
      probability (assoc :probability probability))))
(defn- execute-tool-command
  "Executes one tool command and appends its observed proposal and result events."
  [run clock on-event bus command]
  (let [tool-call (normalize-tool-command command)
        origin (:origin tool-call)
        raw-arguments (get-in tool-call [:function :arguments])
        parsed (try
                 {:arguments (json/parse-string raw-arguments true)}
                 (catch Exception error
                   {:error error}))
        run (if-let [args (:arguments parsed)]
              (append-observed-event run clock on-event bus
                                     (run/tool-proposed-event run tool-call args origin))
              (append-observed-event run clock on-event bus
                                     (run/tool-proposed-event run tool-call raw-arguments origin)))
        result (if-let [args (:arguments parsed)]
                 (run-tool tool-call args (:tools run))
                 (tool-error :invalid-arguments tool-call (:error parsed)))
        tool-msg (if (= origin :model)
                   (tool-result-message tool-call result)
                   (host-observation-message tool-call result))]
    (append-observed-event run clock on-event bus
                           (run/tool-result-event run tool-call result tool-msg origin))))

(defn- authorized? [auth]
  (or (= auth :allow)
      (true? auth)
      (= (:decision auth) :allow)))

(defn- rejection-reason [auth]
  (or (when (map? auth) (:reason auth))
      "Command rejected by authorization policy"))

(defn- handle-rejected-tool
  [run clock on-event bus command reason]
  (let [tool-call (normalize-tool-command command)
        origin (:origin tool-call)
        arguments (try
                    (json/parse-string (get-in tool-call [:function :arguments]) true)
                    (catch Exception _ nil))
        run (append-observed-event run clock on-event bus
                                   (run/tool-proposed-event run tool-call (or arguments "") origin))
        err-result {:error {:kind :action-rejected :reason reason}}
        tool-msg (when (= origin :model)
                   (tool-result-message tool-call err-result))]
    (append-observed-event run clock on-event bus
                           (run/tool-result-event run tool-call err-result tool-msg origin))))

(defn command-executor
  "Builds an imperative executor for model, tool, and run commands using structural pattern matching."
  [{:keys [complete! complete-streaming! clock authorize on-event bus]
    :or {authorize (constantly :allow)}}]
  (fn [run command]
    (let [auth (authorize run command)]
      (match [(authorized? auth) (:command command)]
        ;; 1. Rejected tool execution
        [false :tool/execute]
        (handle-rejected-tool run clock on-event bus command (rejection-reason auth))

        ;; 2. Other rejected commands -> terminal rejection
        [false _]
        (append-observed-events run clock on-event bus
                                [(run/run-finished-event run :rejected {:reason (rejection-reason auth)})])

        ;; 3. Run start
        [true :run/start]
        (append-observed-events run clock on-event bus
                                [(run/run-started-event run)
                                 (run/user-submitted-event (:prompt command))])

        ;; 4. Model completion
        [true :model/complete]
        (let [model-req (or (:request command) (dissoc command :command :origin))
              origin (or (:origin command) :policy)
              run (append-observed-event run clock on-event bus (run/model-requested-event run model-req origin))
              stream-id (or (:id run) "model-call")
              on-token (fn [token]
                         (when bus
                           (bus/publish-stream! bus stream-id :token token))
                         (when on-event
                           (on-event {:type :streaming-token :delta token})))
              response (if (and complete-streaming! (or bus on-event))
                         (complete-streaming! model-req on-token)
                         (complete! model-req))]
          (if-let [err (:error response)]
            (append-observed-event run clock on-event bus (run/model-failed-event run err origin))
            (let [message (assistant-message response)
                  tool-calls (:tool_calls message)]
              (append-observed-event run clock on-event bus
                                     (run/model-responded-event run response message tool-calls origin)))))

        ;; 5. Tool execution
        [true :tool/execute]
        (execute-tool-command run clock on-event bus command)

        ;; 6. Run turn limit reached
        [true :run/limit]
        (append-observed-events run clock on-event bus
                                [(run/limit-reached-event run)
                                 (run/run-finished-event run :step-limit nil)])

        ;; 7. Run finish
        [true :run/finish]
        (let [result (or (:result command) (get-in (last (:messages run)) [:content]))
              terminal-status (or (:terminal-status run) :completed)]
          (append-observed-events run clock on-event bus
                                  [(run/run-finished-event
                                    run
                                    terminal-status
                                    {:final-answer result})]))))))

(defn default-command-executor
  "Builds the command executor used by a normal local agent run."
  ([]
   (default-command-executor {}))
  ([options]
   (command-executor (merge {:complete! model-client/complete!
                             :complete-streaming! model-client/complete-streaming!
                             :clock #(str (Instant/now))}
                            options))))
