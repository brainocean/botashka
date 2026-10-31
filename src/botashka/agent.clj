(ns botashka.agent
  (:require [botashka.provider :as provider]
            [botashka.log :as log]
            [cheshire.core :as json]))

(defn- start-conversation
  [prompt]
  [{:role "user" :content prompt}])

(defn- tool-schemas
  [tools]
  (mapv :schema (vals tools)))

(defn- ask-provider
  [system messages tools]
  (provider/complete! {:system system
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

(defn- tool-name
  [tool-call]
  (get-in tool-call [:function :name]))

(defn- find-tool
  [tool-call tools]
  (get tools (tool-name tool-call)))

(defn- tool-error
  [kind tool-call error]
  {:error {:kind kind
           :tool (tool-name tool-call)
           :message (.getMessage error)}})

(defn- run-tool
  [tool-call arguments tools]
  (if-let [tool (find-tool tool-call tools)]
    (try
      ((:run tool) arguments)
      (catch Exception error
        (tool-error :tool-failed tool-call error)))
    {:error {:kind :unknown-tool
             :tool (tool-name tool-call)}}))

(defn- tool-result-message
  [tool-call result]
  {:role "tool"
   :tool_call_id (:id tool-call)
   :content (json/generate-string result)})

(defn- result-outcome
  [result]
  (if (:error result) :error :ok))

(defn- execute-tool-call
  [recorder turn tool-call tools]
  (try
    (let [arguments (parse-tool-arguments tool-call)
          tool (tool-name tool-call)
          _ (log/emit! recorder :tool-proposed {:turn turn
                                                   :call-id (:id tool-call)
                                                   :tool tool
                                                   :argument-keys (sort (map name (keys arguments)))})
          result (run-tool tool-call arguments tools)]
      (log/emit! recorder :tool-result {:turn turn
                                           :call-id (:id tool-call)
                                           :tool tool
                                           :outcome (result-outcome result)})
      (tool-result-message tool-call result))
    (catch Exception error
      (let [result (tool-error :invalid-arguments tool-call error)]
        (log/emit! recorder :tool-result {:turn turn
                                             :call-id (:id tool-call)
                                             :tool (tool-name tool-call)
                                             :outcome :error
                                             :error-kind :invalid-arguments})
        (tool-result-message tool-call result)))))

(defn- add-tool-results
  [recorder turn messages assistant-message tool-calls tools]
  (into (conj messages assistant-message)
        (map #(execute-tool-call recorder turn % tools) tool-calls)))

(defn- provider-response-data
  [turn response tool-calls]
  {:turn turn
   :model (:model response)
   :finish-reason (get-in response [:choices 0 :finish_reason])
   :tool-call-count (count tool-calls)})

(defn- terminal-result
  [recorder status turn messages response text]
  (log/emit! recorder :run-finished {:status status
                                        :turn turn}
               (when text {:final-answer text}))
  (cond-> {:status status
           :messages messages
           :log (log/events recorder)}
    response (assoc :response response)
    text (assoc :text text)))

(defn run!
  "Runs a bounded OpenAI Chat Completions tool loop.

   `tools` maps provider tool names to {:schema <OpenAI tool schema> :run fn}.
   Each :run function accepts parsed JSON arguments and returns EDN data."
  [{:keys [system prompt tools max-turns log]
    :or {max-turns 8}}]
  (let [recorder (or log (log/recorder {}))
        messages (start-conversation prompt)]
    (log/emit! recorder :run-started {:max-turns max-turns})
    (log/emit! recorder :user-prompt {:turn 0
                                         :characters (count prompt)}
                 {:prompt prompt})
    (loop [turn 0
           messages messages]
      (if (= turn max-turns)
        (do
          (log/emit! recorder :limit-reached {:turn turn
                                                 :kind :max-turns
                                                 :value max-turns})
          (terminal-result recorder :step-limit turn messages nil nil))
        (do
          (log/emit! recorder :provider-requested {:turn turn
                                                      :message-count (count messages)
                                                      :tool-count (count tools)})
          (let [response (ask-provider system messages tools)
                assistant-message (assistant-message response)
                tool-calls (requested-tool-calls assistant-message)]
            (log/emit! recorder :provider-responded
                         (provider-response-data turn response tool-calls))
            (if (seq tool-calls)
              (recur (inc turn)
                     (add-tool-results recorder turn messages assistant-message tool-calls tools))
              (terminal-result recorder :completed turn (conj messages assistant-message)
                               response (:content assistant-message))))))))
)
