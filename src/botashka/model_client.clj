(ns botashka.model-client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:private base-url
  (or (System/getenv "HAI_BASE_URL")
      (System/getenv "GEMINI_BASE_URL")
      "http://localhost:6655/gemini/v1beta"))

(def ^:private default-model
  (or (System/getenv "BOTASHKA_MODEL")
      "gemini-3.1-flash-lite"))

(defn- api-key []
  (or (System/getenv "HAI_PROXY_API_KEY")
      (System/getenv "GEMINI_API_KEY")
      (throw (ex-info "HAI_PROXY_API_KEY or GEMINI_API_KEY is not set"
                      {:kind :missing-api-key}))))

(defn- gemini-contents
  [messages]
  (mapv (fn [{:keys [role content tool_calls tool_call_id thought_signature]}]
          (cond
            (= role "tool")
            {:role "user"
             :parts [{:functionResponse
                      {:name (or tool_call_id "tool")
                       :response (try
                                   (json/parse-string content true)
                                   (catch Exception _
                                     {:result content}))}}]}

            (= role "assistant")
            (let [parts (cond-> []
                          (seq content)
                          (conj {:text content}))
                  parts (if (seq tool_calls)
                          (into parts
                                (map (fn [tc]
                                       (cond-> {:functionCall
                                                {:name (get-in tc [:function :name])
                                                 :id (:id tc)
                                                 :args (try
                                                         (json/parse-string (get-in tc [:function :arguments]) true)
                                                         (catch Exception _ {}))}}
                                         thought_signature (assoc :thoughtSignature thought_signature)))
                                     tool_calls))
                          parts)]
              {:role "model"
               :parts parts})

            :else
            {:role "user"
             :parts [{:text (str content)}]}))
        messages))

(defn- gemini-tools
  [tools]
  (when (seq tools)
    [{:functionDeclarations
      (mapv (fn [tool]
              (let [f (or (:function tool) tool)]
                {:name (:name f)
                 :description (:description f)
                 :parameters (:parameters f)}))
            tools)}]))

(defn- gemini-request-body
  [{:keys [system messages tools]}]
  (cond-> {:contents (gemini-contents messages)}
    (seq system) (assoc :systemInstruction {:parts [{:text system}]})
    (seq tools) (assoc :tools (gemini-tools tools))))

(defn- parse-gemini-candidate
  [candidate]
  (let [parts (get-in candidate [:content :parts])
        text (->> parts (keep :text) (clojure.string/join "\n"))
        function-calls (->> parts (keep :functionCall))
        thought-sig (->> parts (keep :thoughtSignature) first)
        tool-calls (when (seq function-calls)
                     (mapv (fn [fc]
                             {:id (or (:id fc) (:name fc))
                              :type "function"
                              :function {:name (:name fc)
                                         :arguments (json/generate-string (or (:args fc) {}))}})
                           function-calls))]
    (cond-> {:role "assistant"}
      (seq text) (assoc :content text)
      (seq tool-calls) (assoc :tool_calls tool-calls)
      thought-sig (assoc :thought_signature thought-sig))))

(defn- normalize-gemini-response
  [model response-map]
  (let [candidate (get-in response-map [:candidates 0])
        finish-reason (get candidate :finishReason "STOP")
        message (when candidate (parse-gemini-candidate candidate))]
    {:id (:responseId response-map)
     :model (or (:modelVersion response-map) model)
     :choices [{:index 0
                :finish_reason (clojure.string/lower-case (str finish-reason))
                :message message}]
     :usage (:usageMetadata response-map)}))

(defn complete!
  "Sends one model completion request to Gemini v1beta and normalizes it to an OpenAI-compatible shape."
  [request]
  (let [model (or (:model request) default-model)
        endpoint (str base-url "/models/" model ":generateContent")]
    (try
      (let [response (http/post endpoint
                                {:headers {"authorization" (str "Bearer " (api-key))
                                           "content-type" "application/json"}
                                 :body (json/generate-string (gemini-request-body request))
                                 :throw false
                                 :timeout 60000})]
        (if (and (>= (:status response) 200) (< (:status response) 300))
          (try
            (let [data (json/parse-string (:body response) true)]
              (normalize-gemini-response model data))
            (catch Exception error
              {:error {:kind :invalid-json
                       :status (:status response)
                       :message (.getMessage error)}}))
          (try
            (let [err-data (json/parse-string (:body response) true)]
              {:error {:kind :provider-error
                       :status (:status response)
                       :details err-data}})
            (catch Exception _
              {:error {:kind :provider-error
                       :status (:status response)
                       :body (:body response)}}))))
      (catch Exception error
        {:error {:kind :network-error
                 :message (.getMessage error)}}))))

(defn complete-streaming!
  "Sends a streaming completion request to Gemini v1beta, calls `(on-token token)`
   on incoming text chunks, and returns the aggregated OpenAI-compatible completion response."
  ([request]
   (complete-streaming! request nil))
  ([request on-token]
   (let [model (or (:model request) default-model)
         endpoint (str base-url "/models/" model ":streamGenerateContent?alt=sse")]
     (try
       (let [response (http/post endpoint
                                 {:headers {"authorization" (str "Bearer " (api-key))
                                            "content-type" "application/json"}
                                  :body (json/generate-string (gemini-request-body request))
                                  :as :stream
                                  :throw false
                                  :timeout 60000})]
         (if (and (>= (:status response) 200) (< (:status response) 300))
           (with-open [rdr (clojure.java.io/reader (:body response))]
             (loop [lines (line-seq rdr)
                    accumulated-parts []
                    last-data nil]
               (if-let [line (first lines)]
                 (if (str/starts-with? line "data: ")
                   (let [data-str (subs line 6)
                         data (try (json/parse-string data-str true)
                                   (catch Exception _ nil))
                         parts (get-in data [:candidates 0 :content :parts])]
                     (doseq [part parts]
                       (when-let [t (:text part)]
                         (when (and on-token (seq t))
                           (on-token t))))
                     (recur (rest lines)
                            (into accumulated-parts parts)
                            (or data last-data)))
                   (recur (rest lines) accumulated-parts last-data))
                 (let [finish-reason (or (get-in last-data [:candidates 0 :finishReason]) "STOP")
                       candidate {:content {:parts accumulated-parts}
                                  :finishReason finish-reason}
                       final-response (assoc last-data :candidates [candidate])]
                   (normalize-gemini-response model final-response)))))
           (try
             (let [body-str (slurp (:body response))
                   err-data (try (json/parse-string body-str true) (catch Exception _ nil))]
               {:error {:kind :provider-error
                        :status (:status response)
                        :details (or err-data body-str)}})
             (catch Exception _
               {:error {:kind :provider-error
                        :status (:status response)}}))))
       (catch Exception error
         {:error {:kind :network-error
                  :message (.getMessage error)}})))))

(defn complete-text!
  "Returns the first assistant text and response metadata from a completion."
  [request]
  (let [response (complete! request)]
    {:id (:id response)
     :model (:model response)
     :text (get-in response [:choices 0 :message :content])
     :finish-reason (get-in response [:choices 0 :finish_reason])
     :usage (:usage response)}))
