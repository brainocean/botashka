(ns botashka.agent
  (:require [botashka.provider :as provider]
            [cheshire.core :as json]))

(defn- start-conversation
  [prompt]
  [{:role "user" :content prompt}])

(defn- tool-schemas
  [tools]
  (mapv :schema (vals tools)))

(defn- ask-provider
  [system messages tools]
  (provider/complete!
   {:system system
    :messages messages
    :tools (tool-schemas tools)}))

(defn- assistant-message
  [response]
  (get-in response [:choices 0 :message]))

(defn- requested-tool-calls
  [assistant-message]
  (:tool_calls assistant-message))

(defn- parse-tool-arguments
  [tool-call]
  (json/parse-string (get-in tool-call [:function :arguments]) true))

(defn- find-tool
  [tool-call tools]
  (get tools (get-in tool-call [:function :name])))

(defn- run-tool
  [tool-call tools]
  (if-let [tool (find-tool tool-call tools)]
    ((:run tool) (parse-tool-arguments tool-call))
    {:error (str "Unknown tool: " (get-in tool-call [:function :name]))}))

(defn- tool-result-message
  [tool-call result]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (json/generate-string result)})

(defn- tool-call-event
  [turn tool-call arguments]
  {:event :tool-call
   :turn turn
   :call-id (:id tool-call)
   :tool (get-in tool-call [:function :name])
   :arguments arguments})

(defn- tool-result-event
  [turn tool-call result]
  {:event :tool-result
   :turn turn
   :call-id (:id tool-call)
   :tool (get-in tool-call [:function :name])
   :result result})

(defn- execute-tool-call
  [turn tool-call tools]
  (let [arguments (parse-tool-arguments tool-call)
        result (run-tool tool-call tools)]
    {:message (tool-result-message tool-call result)
     :events [(tool-call-event turn tool-call arguments)
              (tool-result-event turn tool-call result)]}))

(defn- add-tool-results
  [turn messages assistant-message tool-calls tools]
  (let [executions (mapv #(execute-tool-call turn % tools) tool-calls)]
    {:messages (into (conj messages assistant-message)
                     (map :message executions))
     :events (vec (mapcat :events executions))}))

(defn- provider-response-event
  [turn response]
  {:event :provider-response
   :turn turn
   :model (:model response)
   :finish-reason (get-in response [:choices 0 :finish_reason])})

(defn- completed-result
  [turn messages assistant-message response trace]
  {:status :completed
   :text (:content assistant-message)
   :messages (conj messages assistant-message)
   :response response
   :trace (conj trace
                {:event :final-answer
                 :turn turn
                 :text (:content assistant-message)})})

(defn run!
  "Runs a bounded OpenAI Chat Completions tool loop.

   `tools` maps provider tool names to {:schema <OpenAI tool schema> :run fn}.
   Each :run function accepts parsed JSON arguments and returns EDN data."
  [{:keys [system prompt tools max-turns]
    :or {max-turns 8}}]
  (loop [turn 0
         messages (start-conversation prompt)
         trace [{:event :user-prompt
                 :turn 0
                 :content prompt}]]
    (if (= turn max-turns)
      {:status :step-limit
       :messages messages
       :trace (conj trace {:event :step-limit
                           :turn turn
                           :max-turns max-turns})}
      (let [response (ask-provider system messages tools)
            assistant-message (assistant-message response)
            tool-calls (requested-tool-calls assistant-message)
            trace (conj trace (provider-response-event turn response))]
        (if (seq tool-calls)
          (let [{next-messages :messages
                 tool-events :events}
                (add-tool-results turn messages assistant-message tool-calls tools)]
            (recur (inc turn)
                   next-messages
                   (into trace tool-events)))
          (completed-result turn messages assistant-message response trace))))))
