(ns botashka.server
  (:require [babashka.fs :as fs]
            [botashka.agent :as agent]
            [botashka.bus :as bus]
            [botashka.chat :as chat]
            [botashka.tools :as tools]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as httpkit]))

;; =============================================================================
;; 1. 会话状态与客户端连接池 (Functional Core State & Imperative Registry)
;; =============================================================================

(defonce clients (atom #{}))

(defonce session-state
  (atom {:timeline []             ;; 会话呈现的时间线
         :status :idle             ;; :idle | :running | :waiting-approval
         :streaming-text ""        ;; 正在推过来的临时 token
         :pending-approval nil     ;; 待审批项 {:id ... :tool ... :args ...}
         :messages []              ;; 维护底层 Agent 的多轮对话历史
         :turn-count 1             ;; 当前轮次
         :workspace "."}))

(defn- broadcast! [event]
  (let [payload-str (pr-str event)]
    (doseq [ch @clients]
      (httpkit/send! ch payload-str))))

(defn- sync-client! [ch]
  (httpkit/send! ch (pr-str {:type :session/sync
                             :payload (dissoc @session-state :messages)})))

;; =============================================================================
;; 2. 真实 Agent 运行循环与 Bus 事件转发 (Real Agent Shell Runner)
;; =============================================================================

(defn- default-system [workspace]
  (str "You are Botashka, an expert coding agent. "
       "You are operating inside the workspace directory: " workspace ". "
       "All file operations (read_file, write_file, search_text, list_files) must use paths relative to this workspace root. "
       "run_command executes shell commands directly within this workspace root directory."))

(defn- run-agent-turn! [user-prompt]
  (Thread/startVirtualThread
   (fn []
     (let [workspace (:workspace @session-state)
           canonical-ws (str (fs/canonicalize workspace))
           registry (tools/code-tool-registry canonical-ws)
           system-prompt (default-system canonical-ws)
           user-item {:id (str (java.util.UUID/randomUUID))
                      :role :user
                      :text user-prompt}
           b (bus/new-bus)
           stream-ch (bus/subscribe! b :stream (async/sliding-buffer 256))
           canonical-ch (bus/subscribe! b :canonical 100)
           done-promise (promise)]

       ;; 1. 更新本地与前端状态
       (swap! session-state (fn [s]
                              (-> s
                                  (assoc :status :running
                                         :streaming-text "")
                                  (update :timeline conj user-item))))
       (broadcast! {:type :timeline/append
                    :payload user-item})

       ;; 2. 异步监听 stream topic (高频 Token)
       (Thread/startVirtualThread
        (fn []
          (loop []
            (when-let [msg (async/<!! stream-ch)]
              (when (= (:kind msg) :token)
                (let [delta (:payload msg)]
                  (swap! session-state update :streaming-text str delta)
                  (broadcast! {:type :token/delta
                               :payload {:delta delta}})))
              (recur)))))

       ;; 3. 异步监听 canonical topic (结构化事件)
       (Thread/startVirtualThread
        (fn []
          (loop []
            (when-let [msg (async/<!! canonical-ch)]
              (let [evt (:payload msg)]
                (case (:type evt)
                  :tool-proposed
                  (let [tool-item {:id (str (java.util.UUID/randomUUID))
                                   :role :tool-proposed
                                   :tool (get-in evt [:data :tool])
                                   :args (get-in evt [:evidence :arguments])}]
                    ;; 将之前的思考打字内容落地，并追加工具提议
                    (let [streaming (:streaming-text @session-state)]
                      (when-not (empty? streaming)
                        (let [agent-item {:id (str (java.util.UUID/randomUUID))
                                          :role :agent
                                          :text streaming}]
                          (swap! session-state (fn [s]
                                                 (-> s
                                                     (assoc :streaming-text "")
                                                     (update :timeline conj agent-item))))
                          (broadcast! {:type :timeline/append
                                       :payload agent-item}))))
                    (swap! session-state update :timeline conj tool-item)
                    (broadcast! {:type :timeline/append
                                 :payload tool-item}))

                  :tool-result
                  (let [res (get-in evt [:evidence :result])
                        tool-name (get-in evt [:data :tool])
                        result-str (if (:error res)
                                     (str "ERROR: " (pr-str (:error res)))
                                     (if (string? res) res (pr-str res)))
                        result-item {:id (str (java.util.UUID/randomUUID))
                                     :role :tool
                                     :tool tool-name
                                     :result result-str}]
                    (swap! session-state update :timeline conj result-item)
                    (broadcast! {:type :timeline/append
                                 :payload result-item}))

                  :run-finished
                  (deliver done-promise true)

                  nil))
              (recur)))))

       ;; 4. 真正执行 Agent Run!
       (try
         (let [prev-messages (:messages @session-state)
               result (agent/run! {:prompt user-prompt
                                   :messages prev-messages
                                   :system system-prompt
                                   :tools registry
                                   :max-turns 10
                                   :bus b})]
           ;; 等待事件消费完毕
           (deref done-promise 5000 false)
           ;; 收尾最后的思考文字
           (let [streaming (:streaming-text @session-state)]
             (when-not (empty? streaming)
               (let [agent-item {:id (str (java.util.UUID/randomUUID))
                                 :role :agent
                                 :text streaming}]
                 (swap! session-state (fn [s]
                                        (-> s
                                            (assoc :streaming-text "")
                                            (update :timeline conj agent-item))))
                 (broadcast! {:type :timeline/append
                              :payload agent-item}))))
           ;; 更新多轮 messages 和 idle 状态
           (swap! session-state (fn [s]
                                  (-> s
                                      (assoc :status :idle
                                             :streaming-text "")
                                      (assoc :messages (or (:messages result) prev-messages))
                                      (update :turn-count inc))))
           (broadcast! {:type :run/settled
                        :payload {:status :idle}}))
         (catch Exception e
           (let [err-msg (.getMessage e)
                 err-item {:id (str (java.util.UUID/randomUUID))
                           :role :system
                           :text (str "执行错误: " err-msg)}]
             (swap! session-state (fn [s]
                                    (-> s
                                        (assoc :status :idle
                                               :streaming-text "")
                                        (update :timeline conj err-item))))
             (broadcast! {:type :timeline/append
                          :payload err-item})
             (broadcast! {:type :run/settled
                          :payload {:status :idle}})))
         (finally
           (bus/close-bus! b)))))))

;; =============================================================================
;; 3. WebSocket 消息处理
;; =============================================================================

(defn- handle-client-message! [raw-str]
  (try
    (let [event (edn/read-string raw-str)
          {:keys [type payload]} event]
      (case type
        :prompt/submit
        (run-agent-turn! (:text payload))

        (println "Unknown event from client:" event)))
    (catch Exception e
      (println "Failed to parse client message:" raw-str e))))

(defn- ws-handler [req]
  (httpkit/as-channel req
   {:on-open (fn [ch]
               (swap! clients conj ch)
               (sync-client! ch))
    :on-close (fn [ch _status]
                (swap! clients disj ch))
    :on-receive (fn [_ch msg]
                  (handle-client-message! msg))}))

;; =============================================================================
;; 4. 静态资源路由 (Zero-dependency HTTP Handler)
;; =============================================================================

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "application/javascript; charset=utf-8"
   "cljs" "text/plain; charset=utf-8"
   "edn"  "application/edn; charset=utf-8"})

(defn- get-mime-type [path]
  (let [ext (last (str/split path #"\."))]
    (get mime-types ext "application/octet-stream")))

(defn- serve-file [file-path]
  (let [f (io/file file-path)]
    (if (.exists f)
      {:status 200
       :headers {"Content-Type" (get-mime-type file-path)
                 "Cache-Control" "no-cache"}
       :body (slurp f)}
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body (str "File not found: " file-path)})))

(defn app-handler [req]
  (let [uri (:uri req)]
    (cond
      (= uri "/ws")
      (ws-handler req)

      (or (= uri "/") (= uri "/index.html"))
      (serve-file "resources/public/index.html")

      (= uri "/app.cljs")
      (serve-file "resources/public/app.cljs")

      (= uri "/botashka.css")
      (serve-file "resources/public/botashka.css")

      (str/starts-with? uri "/vendor/")
      (serve-file (str "resources/public" uri))

      :else
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; =============================================================================
;; 5. 启动入口
;; =============================================================================

(defn start-server!
  ([] (start-server! {}))
  ([opts]
   (let [port (or (:port opts)
                  (when-let [p (System/getenv "BOTASHKA_PORT")]
                    (Integer/parseInt p))
                  7890)
         host (or (:host opts) "127.0.0.1")
         workspace (or (:workspace opts) ".")]
     (swap! session-state assoc :workspace workspace)
     (println (str "Starting Botashka Web Console at http://" host ":" port " (workspace: " workspace ") ..."))
     (httpkit/run-server app-handler {:ip host :port port}))))

(defn -main [& args]
  (let [server (start-server!)]
    (println "Server running. Press Ctrl+C to stop.")
    @(promise)))
