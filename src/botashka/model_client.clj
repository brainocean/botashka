(ns botashka.model-client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.core.match :refer [match]]
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
(defn- tool-call-parts
  [tool-calls thought-sig]
  (mapv (fn [tc]
          (cond-> {:functionCall
                   {:name (get-in tc [:function :name])
                    :id (:id tc)
                    :args (try
                            (json/parse-string (get-in tc [:function :arguments]) true)
                            (catch Exception _ {}))}}
            thought-sig (assoc :thoughtSignature thought-sig)))
        tool-calls))

(defn- gemini-message
  [msg]
  (match msg
    {:role "tool" :content content}
    {:role "user"
     :parts [{:functionResponse
              {:name (or (:tool_call_id msg) "tool")
               :response (try
                           (json/parse-string content true)
                           (catch Exception _
                             {:result content}))}}]}

    {:role "assistant" :content content :tool_calls ([_ & _] :seq)}
    {:role "model"
     :parts (into (if (seq content) [{:text content}] [])
                  (tool-call-parts (:tool_calls msg) (:thought_signature msg)))}

    {:role "assistant" :tool_calls ([_ & _] :seq)}
    {:role "model"
     :parts (tool-call-parts (:tool_calls msg) (:thought_signature msg))}

    {:role "assistant" :content content}
    {:role "model"
     :parts (if (seq content) [{:text content}] [])}

    {:content content}
    {:role "user"
     :parts [{:text (str content)}]}))

(defn- gemini-contents
  [messages]
  (mapv gemini-message messages))

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
        text (apply str (keep :text parts))
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

(defn sse-events-seq
  "Transforms a sequence of SSE lines into a lazy sequence of raw event data strings.
   Handles multiple data: lines per event, skips comments and empty lines."
  [lines]
  (let [step (fn step [ls current-data]
               (lazy-seq
                 (if-let [line (first ls)]
                   (cond
                     (str/blank? line)
                     (if (seq current-data)
                       (cons (str/join "\n" current-data)
                             (step (rest ls) []))
                       (step (rest ls) []))

                     (str/starts-with? line "data: ")
                     (step (rest ls) (conj current-data (subs line 6)))

                     (str/starts-with? line ":")
                     (step (rest ls) current-data)

                     :else
                     (step (rest ls) current-data))
                   (when (seq current-data)
                     [(str/join "\n" current-data)]))))]
    (step lines [])))

(def ^:private sse-decode-xf
  (comp
    (keep (fn [payload]
            (try
              (json/parse-string payload true)
              (catch Exception error
                {:error {:kind :invalid-json
                         :message (.getMessage error)
                         :payload payload}}))))
    (take-while #(not (:error %)))))

(defn- extract-text-deltas [event]
  (keep :text (get-in event [:candidates 0 :content :parts])))

(defn- apply-stream-event
  [state event]
  (if-let [err (:error event)]
    (reduced {:error err})
    (let [candidate (get-in event [:candidates 0])
          parts (get-in candidate [:content :parts])
          finish-reason (:finishReason candidate)
          response-id (:responseId event)
          model-version (:modelVersion event)
          usage (:usageMetadata event)]
      (cond-> (update state :parts into (or parts []))
        finish-reason (assoc :finish-reason finish-reason)
        response-id (assoc :response-id response-id)
        model-version (assoc :model-version model-version)
        usage (assoc :usage usage)))))

(defn- finalize-stream-response
  [model state]
  (if (:error state)
    state
    (let [finish-reason (or (:finish-reason state) "STOP")
          candidate {:content {:parts (:parts state)}
                     :finishReason finish-reason}
          response-map {:candidates [candidate]
                        :responseId (:response-id state)
                        :modelVersion (:model-version state)
                        :usageMetadata (:usage state)}]
      (normalize-gemini-response model response-map))))

(defn parse-stream-events
  "Purely parses SSE line sequences via transducers, invokes on-token for text deltas,
   and accumulates the final normalized model completion response."
  [model lines on-token]
  (let [stream-reducer (fn [state event]
                         (when on-token
                           (doseq [t (extract-text-deltas event)]
                             (when (seq t)
                               (on-token t))))
                         (apply-stream-event state event))
        initial-state {:parts []}]
    (transduce sse-decode-xf
               (completing stream-reducer (partial finalize-stream-response model))
               initial-state
               (sse-events-seq lines))))

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
             (parse-stream-events model (line-seq rdr) on-token))
           (with-open [body-stream (:body response)]
             (let [body-str (slurp body-stream)
                   err-data (try (json/parse-string body-str true) (catch Exception _ nil))]
               {:error {:kind :provider-error
                        :status (:status response)
                        :details (or err-data body-str)}}))))
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
