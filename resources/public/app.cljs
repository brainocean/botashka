(ns botashka.app
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.edn :as edn]))

;; =============================================================================
;; 1. 状态定义 (State)
;; =============================================================================

(defonce app-state
  (r/atom {:connected? false
           :status :idle            ;; :idle | :running | :waiting-approval
           :timeline []             ;; [{:id ... :role :user/:agent :text ... :tool-call ...}]
           :streaming-text ""       ;; 正在打字输出的临时文本
           :pending-approval nil    ;; {:id ... :tool ... :cmd ...}
           :input-text ""}))

;; =============================================================================
;; 2. WebSocket Channel 抽象与自动重连 (Imperative Shell)
;; =============================================================================

(defonce ws-ref (atom nil))

(declare connect-ws!)

(defn send-event! [event-map]
  (if-let [socket @ws-ref]
    (if (= (.-readyState socket) js/WebSocket.OPEN)
      (.send socket (pr-str event-map))
      (js/console.warn "WebSocket is not open:" (.-readyState socket)))
    (js/console.warn "No active WebSocket connection")))

(defn handle-server-event! [event]
  (let [{:keys [type payload]} event]
    (case type
      ;; 1. 会话初次握手同步 (多端/刷新)
      :session/sync
      (swap! app-state merge payload {:connected? true})

      ;; 2. 流式 Token 接收 (打字机效果)
      :token/delta
      (swap! app-state update :streaming-text str (:delta payload))

      ;; 3. Agent 思考/回答小结步骤完成
      :step/finish
      (let [final-text (:text payload)]
        (swap! app-state (fn [s]
                           (-> s
                               (update :timeline conj {:id (str (random-uuid))
                                                       :role :agent
                                                       :text final-text})
                               (assoc :streaming-text ""
                                      :status :idle)))))

      ;; 4. 人在回路：请求工具执行确认
      :action/approval-required
      (swap! app-state assoc
             :status :waiting-approval
             :pending-approval payload)

      ;; 5. 工具执行结果回显
      :action/completed
      (let [result (:result payload)]
        (swap! app-state (fn [s]
                           (-> s
                               (assoc :status :idle
                                      :pending-approval nil)
                               (update :timeline conj {:id (str (random-uuid))
                                                       :role :tool
                                                       :tool (:tool payload)
                                                       :result result})))))

      ;; 默认未知事件
      (js/console.log "Unhandled event:" (pr-str event)))))

(defn connect-ws! []
  (let [protocol (if (= (.-protocol js/location) "https:") "wss:" "ws:")
        host (.-host js/location)
        url (str protocol "//" host "/ws")
        socket (js/WebSocket. url)]
    (set! (.-onopen socket)
          (fn [_]
            (swap! app-state assoc :connected? true)
            (js/console.log "WebSocket connected: " url)))
    (set! (.-onmessage socket)
          (fn [msg]
            (try
              (let [event (edn/read-string (.-data msg))]
                (handle-server-event! event))
              (catch :default e
                (js/console.error "Failed to parse EDN message:" (.-data msg) e)))))
    (set! (.-onclose socket)
          (fn [_]
            (swap! app-state assoc :connected? false)
            (js/console.warn "WebSocket closed. Retrying in 2s...")
            (js/setTimeout connect-ws! 2000)))
    (set! (.-onerror socket)
          (fn [err]
            (js/console.error "WebSocket error:" err)
            (.close socket)))
    (reset! ws-ref socket)))

;; =============================================================================
;; 3. UI 视图组件 (Hiccup + Pico CSS)
;; =============================================================================

(defn header-view []
  (let [connected? (:connected? @app-state)
        status (:status @app-state)]
    [:header {:style {:display "flex" :justify-content "space-between" :align-items "center" :padding "1rem 0"}}
     [:hgroup {:style {:margin 0}}
      [:h2 {:style {:margin 0}} "Botashka"]
      [:p {:style {:margin 0}} "Web Console (Zero-Build Spike)"]]
     [:div
      [:span {:class (str "badge " (if connected? "badge-online" "badge-offline"))}
       (if connected? "🟢 CONNECTED" "🔴 DISCONNECTED")]
      [:span {:style {:margin-left "0.5rem" :color "var(--pico-muted-color)" :font-size "0.85rem"}}
       (str "State: " (name status))]]]))

(defn approval-modal-view []
  (when-let [approval (:pending-approval @app-state)]
    [:article {:class "tool-box"}
     [:header
      [:strong "⚠️ 权限请求：允许执行以下工具调用吗？"]]
     [:div
      [:p [:strong "Tool: "] [:code (str (:tool approval))]]
      [:p [:strong "Command: "] [:pre [:code (:cmd approval)]]]]
     [:footer {:style {:display "flex" :gap "1rem" :justify-content "flex-end"}}
      [:button {:class "secondary outline"
                :on-click (fn []
                            (send-event! {:type :action/decision
                                          :payload {:approval-id (:id approval)
                                                    :approved? false}}))}
       "拒绝 (Deny)"]
      [:button {:on-click (fn []
                            (send-event! {:type :action/decision
                                          :payload {:approval-id (:id approval)
                                                    :approved? true}}))}
       "批准执行 (Approve)"]]]))

(defn timeline-item-view [item]
  [:article {:key (:id item) :class "timeline-card"}
   [:header {:style {:display "flex" :justify-content "space-between"}}
    [:strong (case (:role item)
               :user "👤 You"
               :agent "🤖 Botashka"
               :tool (str "🔧 Tool (" (:tool item) ")"))]]
   (case (:role item)
     :user [:p (:text item)]
     :agent [:div {:style {:white-space "pre-wrap"}} (:text item)]
     :tool [:pre [:code (:result item)]])])

(defn timeline-view []
  (let [{:keys [timeline streaming-text]} @app-state]
    [:section
     (for [item timeline]
       ^{:key (:id item)} [timeline-item-view item])
     ;; 流式打字机卡片
     (when (not (empty? streaming-text))
       [:article {:class "timeline-card"}
        [:header [:strong "🤖 Botashka (Streaming...)"]]
        [:div {:style {:white-space "pre-wrap"}}
         streaming-text
         [:span {:class "streaming-cursor"}]]])]))

(defn input-box-view []
  (let [status (:status @app-state)
        input-text (:input-text @app-state)
        disabled? (or (= status :running) (= status :waiting-approval))]
    [:form {:on-submit (fn [e]
                         (.preventDefault e)
                         (when (and (not (empty? (.trim input-text))) (not disabled?))
                           (let [text (.trim input-text)]
                             ;; 乐观更新本地 UI
                             (swap! app-state (fn [s]
                                                (-> s
                                                    (update :timeline conj {:id (str (random-uuid))
                                                                            :role :user
                                                                            :text text})
                                                    (assoc :input-text ""
                                                           :status :running))))
                             ;; 发送给服务端
                             (send-event! {:type :prompt/submit
                                           :payload {:text text}}))))}
     [:fieldset {:role "group"}
      [:input {:type "text"
               :placeholder (if disabled? "请等待当前任务或处理待办确认..." "输入 Prompt / 指令...")
               :disabled disabled?
               :value input-text
               :on-change (fn [e]
                            (swap! app-state assoc :input-text (.. e -target -value)))}]
      [:button {:type "submit" :disabled disabled?} "发送"]]]))

(defn main-view []
  [:main
   [header-view]
   [:hr]
   [timeline-view]
   [approval-modal-view]
   [input-box-view]])

;; =============================================================================
;; 4. 挂载入口
;; =============================================================================

(defn init! []
  (connect-ws!)
  (rdom/render [main-view] (js/document.getElementById "app")))

(init!)
