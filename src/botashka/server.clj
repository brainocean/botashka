(ns botashka.server
  (:require [babashka.fs :as fs]
            [botashka.agent :as agent]
            [botashka.bus :as bus]
            [botashka.chat :as chat]
            [botashka.command-executor :as command-executor]
            [botashka.tools :as tools]
            [cheshire.core :as json]
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
         :raw-events []           ;; 完整的底层核心事件流
         :status :idle             ;; :idle | :running | :waiting-approval
         :streaming-text ""        ;; 正在推过来的临时 token
         :pending-approval nil     ;; 待审批项 {:id ... :tool ... :args ...}
         :messages []              ;; 维护底层 Agent 的多轮对话历史
         :turn-count 1             ;; 当前轮次
         :workspace "."
         :fast-path? false}))
(defonce pending-approvals (atom {})) ;; approval-id -> promise

(defn- broadcast! [event]
  (let [payload-str (pr-str event)]
    (doseq [ch @clients]
      (httpkit/send! ch payload-str))))

(defn- sync-client! [ch]
  (httpkit/send! ch (pr-str {:type :session/sync
                             :payload (dissoc @session-state :messages)})))

;; =============================================================================
;; 2. 人在回路：安全授权策略 (Human-in-the-loop Authorization Policy)
;; =============================================================================

(def ^:private sensitive-tools #{"run_command" "write_file"})

(defn- extract-tool-details [command]
  (let [explicit-call (:tool-call command)
        tname (or (:tool command)
                  (get-in command [:tool-call :function :name])
                  (get-in command [:function :name]))
        raw-args (or (:arguments command)
                     (get-in command [:tool-call :function :arguments])
                     (get-in command [:function :arguments]))
        parsed-args (cond
                      (map? raw-args) raw-args
                      (string? raw-args) (try (json/parse-string raw-args true) (catch Exception _ raw-args))
                      :else raw-args)]
    {:tool tname
     :arguments parsed-args}))

(defn- web-authorization-policy
  "Asks human approval via WebSocket for sensitive commands (write_file, run_command),
   blocking only the virtual execution thread until client approves or denies."
  [_run command]
  (let [{:keys [tool arguments]} (extract-tool-details command)]
    (if (contains? sensitive-tools tool)
      (let [approval-id (str (java.util.UUID/randomUUID))
            p (promise)
            approval-payload {:id approval-id
                              :tool tool
                              :args arguments}]
        (swap! pending-approvals assoc approval-id p)
        (swap! session-state assoc
               :status :waiting-approval
               :pending-approval approval-payload)
        (broadcast! {:type :action/approval-required
                     :payload approval-payload})
        ;; 阻塞虚拟线程等待前端做出决定
        (let [approved? (deref p 300000 false)] ;; 5 分钟超时保护
          (swap! pending-approvals dissoc approval-id)
          (swap! session-state assoc
                 :status :running
                 :pending-approval nil)
          (broadcast! {:type :action/approval-settled
                       :payload {:approval-id approval-id}})
          (if approved?
            :allow
            {:decision :deny
             :reason (str "Tool `" tool "` was rejected by human operator in Web console.")})))
      :allow)))

;; =============================================================================
;; 3. 真实 Agent 运行循环与 Bus 事件转发 (Real Agent Shell Runner)
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
                ;; 无论何种事件，都存入 raw-events 并实时广播给观察者
                (swap! session-state update :raw-events conj evt)
                (broadcast! {:type :event/appended
                             :payload evt})
                (case (:type evt)
                  :decision-responded
                  (let [dec-item {:id (str (java.util.UUID/randomUUID))
                                  :role :decision
                                  :choice (get-in evt [:data :choice])
                                  :decider (get-in evt [:data :decider])
                                  :confidence (get-in evt [:data :confidence])
                                  :probability (get-in evt [:data :probability])}]
                    (swap! session-state update :timeline conj dec-item)
                    (broadcast! {:type :timeline/append
                                 :payload dec-item}))

                  :tool-proposed
                  (let [origin (get-in evt [:data :origin])
                        tool-item {:id (str (java.util.UUID/randomUUID))
                                   :role :tool-proposed
                                   :tool (get-in evt [:data :tool])
                                   :origin origin
                                   :args (or (get-in evt [:evidence :arguments])
                                             (get-in evt [:evidence :raw-arguments]))}]
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
                        error? (or (:error res) (= (:outcome (:data evt)) :error))
                        result-str (if error?
                                     (str "ERROR: " (pr-str (or (:error res) (get-in evt [:data :error-kind]))))
                                     (if (string? res) res (pr-str res)))
                        result-item {:id (str (java.util.UUID/randomUUID))
                                     :role :tool
                                     :tool tool-name
                                     :error? (boolean error?)
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
               fast-path? (:fast-path? @session-state)
              action-policy (when fast-path?
                              (require 'botashka.clef-policy)
                              (require 'botashka.run)
                              ((resolve 'botashka.run/first-applicable-action-policy)
                               ((resolve 'botashka.clef-policy/clef-action-policy) {:min-probability 0.80})
                               (resolve 'botashka.run/default-action-policy)))
               executor (command-executor/default-command-executor
                         {:authorize web-authorization-policy
                          :bus b})
               run-opts (cond-> {:prompt user-prompt
                                 :messages prev-messages
                                 :system system-prompt
                                 :tools registry
                                 :max-turns 10
                                 :authorization-policy web-authorization-policy
                                 :command-executor! executor
                                 :bus b}
                          action-policy (assoc :action-policy action-policy))
               result (agent/run! run-opts)]
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
;; 4. WebSocket 消息处理
;; =============================================================================

(defn- handle-approval-decision! [approval-id approved?]
  (if-let [p (get @pending-approvals approval-id)]
    (deliver p (boolean approved?))
    (println "Warning: no pending approval found for id:" approval-id)))

(defn- handle-client-message! [raw-str]
  (try
    (let [event (edn/read-string raw-str)
          {:keys [type payload]} event]
      (case type
        :prompt/submit
        (run-agent-turn! (:text payload))

        :config/fast-path
        (let [enabled? (boolean (:enabled? payload))]
          (swap! session-state assoc :fast-path? enabled?)
          (broadcast! {:type :config/fast-path-updated
                       :payload {:fast-path? enabled?}}))
        :session/reset
        (do
          (swap! session-state assoc
                 :timeline []
                 :raw-events []
                 :messages []
                 :turn-count 1
                 :streaming-text ""
                 :pending-approval nil
                 :status :idle)
          (broadcast! {:type :session/sync
                       :payload (dissoc @session-state :messages)}))

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
;; 5. 静态资源路由 (Zero-dependency HTTP Handler)
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
;; 6. 启动入口
;; =============================================================================

(defn start-server!
  ([] (start-server! {}))
  ([opts]
   (let [port (or (:port opts)
                  (when-let [p (System/getenv "BOTASHKA_PORT")]
                    (Integer/parseInt p))
                  7890)
         host (or (:host opts) "127.0.0.1")
         workspace (or (:workspace opts) ".")
         fast-path? (boolean (or (:fast-path opts) (:clef opts)))]
     (swap! session-state assoc :workspace workspace :fast-path? fast-path?)
     (println (str "Starting Botashka Web Console at http://" host ":" port " (workspace: " workspace ", fast-path: " fast-path? ") ..."))
     (httpkit/run-server app-handler {:ip host :port port}))))
(defn -main [& args]
  (let [opts (if (and (seq args) (map? (first args)))
               (first args)
               {})
        server (start-server! opts)]
    (println "Server running. Press Ctrl+C to stop.")
    @(promise)))
