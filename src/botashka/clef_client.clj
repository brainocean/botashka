(ns botashka.clef-client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]))

(def ^:private default-endpoint "http://127.0.0.1:8085/v1/systemone")
(defn- extract-top-choice
  "Purely extracts the highest probability choice and confidence from Clef answers."
  [answer]
  (let [legend (:legend answer)
        probabilities (:probabilities answer)
        confidence (:confidence answer 0.0)]
    (when (and (seq legend) (seq probabilities))
      (let [[top-key top-prob] (apply max-key val probabilities)
            choice-name (or (get legend top-key)
                            (get legend (keyword (str top-key)))
                            (get legend (name top-key))
                            (get legend (str top-key)))]
        {:choice choice-name
         :probability top-prob
         :confidence confidence}))))

(defn parse-clef-response
  "Purely parses and normalizes a Clef /v1/systemone API response body into EDN."
  [response-body question-key]
  (try
    (let [data (if (string? response-body)
                 (json/parse-string response-body true)
                 response-body)
          answer (get-in data [:answers question-key])]
      (if answer
        (merge {:model (:model data)}
               (extract-top-choice answer))
        {:error {:kind :missing-answer-key :key question-key}}))
    (catch Exception error
      {:error {:kind :invalid-clef-json :message (.getMessage error)}})))

(defn- build-payload
  [state criteria instructions]
  {:state state
   :questions {:tool_choice {:type "score"
                             :instructions (or instructions "Which tool should be executed next?")
                             :criteria (vec criteria)}}})

(defn decide!
  "Sends a decision request to a Clef server endpoint (/v1/systemone) and returns normalized decision.
   Options:
     `:endpoint` - server URL (default http://127.0.0.1:8085/v1/systemone)
     `:state` - state string describing current situation
     `:criteria` - vector of allowed candidate tool names, e.g. [\"search_text\" \"list_files\" \"no_tool\"]
     `:instructions` - optional decision prompt
     `:timeout` - HTTP timeout in ms (default 5000)"
  [{:keys [endpoint state criteria instructions timeout]
    :or {endpoint default-endpoint
         timeout 5000}}]
  (try
    (let [payload (build-payload state criteria instructions)
          response (http/post endpoint
                              {:headers {"content-type" "application/json"}
                               :body (json/generate-string payload)
                               :throw false
                               :timeout timeout})]
      (if (and (>= (:status response) 200) (< (:status response) 300))
        (parse-clef-response (:body response) :tool_choice)
        {:error {:kind :clef-http-error
                 :status (:status response)
                 :body (:body response)}}))
    (catch Exception error
      {:error {:kind :clef-connection-failed
               :message (.getMessage error)}})))
