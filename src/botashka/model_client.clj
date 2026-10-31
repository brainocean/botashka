(ns botashka.model-client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def ^:private base-url "http://localhost:6655/openai/v1")
(def ^:private default-model "gpt-5.6-terra")

(defn- api-key []
  (or (System/getenv "HAI_PROXY_API_KEY")
      (throw (ex-info "HAI_PROXY_API_KEY is not set"
                      {:kind :missing-api-key}))))

(defn- request-body [{:keys [system messages model tools tool-choice]}]
  (let [messages (cond-> (vec messages)
                   system (into [{:role "system" :content system}]))]
    (cond-> {:model (or model default-model)
             :messages messages}
      tools (assoc :tools tools)
      tool-choice (assoc :tool_choice tool-choice))))

(defn complete!
  "Sends one OpenAI-compatible model request and returns the decoded response.

   Input messages use OpenAI roles and string content. The caller owns
   model-response normalization and agent decision logic."
  [request]
  (let [response (http/post (str base-url "/chat/completions")
                            {:headers {"authorization" (str "Bearer " (api-key))
                                       "content-type" "application/json"}
                             :body (json/generate-string (request-body request))
                             :throw false
                             :timeout 60000})]
    (try
      (json/parse-string (:body response) true)
      (catch Exception error
        {:error {:kind :invalid-json
                 :status (:status response)
                 :message (.getMessage error)}}))))

(defn complete-text!
  "Returns the first assistant text and response metadata from a completion."
  [request]
  (let [response (complete! request)]
    {:id (:id response)
     :model (:model response)
     :text (get-in response [:choices 0 :message :content])
     :finish-reason (get-in response [:choices 0 :finish_reason])
     :usage (:usage response)}))
