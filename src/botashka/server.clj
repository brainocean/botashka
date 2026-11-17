(ns botashka.server
  (:require [org.httpkit.server :as httpkit]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; 1. 会话状态与客户端连接池 (Functional Core State & Imperative Registry)
;; =============================================================================

(defonce clients (atom #{}))

(defonce session-state
  (atom {:timeline []
         :status :idle
         :streaming-text ""
         :pending-approval nil}))

(defn- broadcast! [event]
  (let [payload-str (pr-str event)]
    (doseq [ch @clients]
      (httpkit/send! ch payload-str))))

(defn- sync-client! [ch]
  (httpkit/send! ch (pr-str {:type :session/sync
                             :payload @session-state})))

;; =============================================================================
;; 2. Mock Agent 流式与审批引擎 (Spike Simulation)
;; =============================================================================

(defn- run-mock-agent-turn! [user-prompt]
  (Thread/startVirtualThread
   (fn []
     (try
       ;; 1. 追加用户消息进 timeline
      (swap! session-state
             (fn [s]
               (-> s
                   (assoc :status :running)
                   (update :timeline conj {:id (str (java.util.UUID/randomUUID))
                                           :role :user
                                           :text user-prompt}))))

      ;; 2. 模拟打字机 Token Streaming
      (let [thought-tokens ["思考中" "..." " 收到" "您的" "需求" "：\"" user-prompt "\"。\n"
                            "为了" "完成" "任务" "，我需要" "先执行" " `ls -la` " "检查" "工作区" "目录" "结构" "。"]]
        (doseq [tok thought-tokens]
          (Thread/sleep 120)
          (swap! session-state update :streaming-text str tok)
          (broadcast! {:type :token/delta
                       :payload {:delta tok}})))

      ;; 3. 步骤完成，归档到 timeline
      (let [full-thought (:streaming-text @session-state)]
        (swap! session-state (fn [s]
                               (-> s
                                   (assoc :streaming-text ""
                                          :status :waiting-approval)
                                   (update :timeline conj {:id (str (java.util.UUID/randomUUID))
                                                           :role :agent
                                                           :text full-thought}))))
        (broadcast! {:type :step/finish
                     :payload {:text full-thought}}))

      ;; 4. 触发人在回路：敏感工具调用等待审批
      (let [approval-payload {:id (str (java.util.UUID/randomUUID))
                              :tool "bash"
                              :cmd "ls -la"}]
        (swap! session-state assoc :pending-approval approval-payload)
        (broadcast! {:type :action/approval-required
                     :payload approval-payload}))
      (catch Exception e
        (println "Error in mock agent runner:" e))))))

(defn- handle-approval-decision! [approval-id approved?]
  (Thread/startVirtualThread
   (fn []
     (try
       (let [pending (:pending-approval @session-state)]
        (when (= (:id pending) approval-id)
          (if approved?
            ;; 用户批准：执行并回显结果
            (let [mock-result "total 32\ndrwxr-xr-x  12 user staff  384 Nov 17 18:00 .\ndrwxr-xr-x   6 user staff  192 Nov 17 17:00 ..\n-rw-r--r--   1 user staff 1024 Nov 17 17:30 deps.edn\n-rw-r--r--   1 user staff 2048 Nov 17 17:45 README.md"]
              (swap! session-state (fn [s]
                                     (-> s
                                         (assoc :status :idle
                                                :pending-approval nil)
                                         (update :timeline conj {:id (str (java.util.UUID/randomUUID))
                                                                 :role :tool
                                                                 :tool (:tool pending)
                                                                 :result mock-result}))))
              (broadcast! {:type :action/completed
                           :payload {:tool (:tool pending)
                                     :result mock-result}}))
            ;; 用户拒绝
            (do
              (swap! session-state assoc
                     :status :idle
                     :pending-approval nil)
              (broadcast! {:type :action/completed
                           :payload {:tool (:tool pending)
                                     :result "Command execution denied by user."}})))))
      (catch Exception e
        (println "Error in handling approval:" e))))))

;; =============================================================================
;; 3. WebSocket 消息处理
;; =============================================================================

(defn- handle-client-message! [raw-str]
  (try
    (let [event (edn/read-string raw-str)
          {:keys [type payload]} event]
      (case type
        :prompt/submit
        (run-mock-agent-turn! (:text payload))

        :action/decision
        (handle-approval-decision! (:approval-id payload) (:approved? payload))

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
      ;; WebSocket 端点
      (= uri "/ws")
      (ws-handler req)

      ;; 首页
      (or (= uri "/") (= uri "/index.html"))
      (serve-file "resources/public/index.html")

      ;; 前端 ClojureScript 代码
      (= uri "/app.cljs")
      (serve-file "resources/public/app.cljs")

      ;; 应用自定义 CSS
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
         host (or (:host opts) "127.0.0.1")]
     (println (str "Starting Botashka Web Console at http://" host ":" port " ..."))
     (httpkit/run-server app-handler {:ip host :port port}))))

(defn -main [& args]
  (let [server (start-server!)]
    (println "Server running. Press Ctrl+C to stop.")
    ;; 主线程保持运行
    @(promise)))
