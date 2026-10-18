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

(defn- add-tool-results
  [messages assistant-message tool-calls tools]
  (into (conj messages assistant-message)
        (map #(tool-result-message % (run-tool % tools)) tool-calls)))

(defn- completed-result
  [messages assistant-message response]
  {:status :completed
   :text (:content assistant-message)
   :messages (conj messages assistant-message)
   :response response})

(defn run!
  "Runs a bounded OpenAI Chat Completions tool loop.

   `tools` maps provider tool names to {:schema <OpenAI tool schema> :run fn}.
   Each :run function accepts parsed JSON arguments and returns EDN data."
  [{:keys [system prompt tools max-turns]
    :or {max-turns 8}}]
  (loop [turn 0
         messages (start-conversation prompt)]
    (if (= turn max-turns)
      {:status :step-limit
       :messages messages}
      (let [response (ask-provider system messages tools)
            assistant-message (assistant-message response)
            tool-calls (requested-tool-calls assistant-message)]
        (if (seq tool-calls)
          (recur (inc turn)
                 (add-tool-results messages assistant-message tool-calls tools))
          (completed-result messages assistant-message response))))))
