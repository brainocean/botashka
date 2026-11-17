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
           :timeline []             ;; [{:id ... :role :user/:agent/:tool-proposed/:tool/:system ...}]
           :streaming-text ""       ;; 正在打字输出的临时文本
           :pending-approval nil    ;; {:id ... :tool ... :args ...}
           :workspace "."
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
      :session/sync
      (swap! app-state merge payload {:connected? true})

      :token/delta
      (swap! app-state update :streaming-text str (:delta payload))

      :timeline/append
      (swap! app-state update :timeline conj payload)

      :run/settled
      (swap! app-state assoc
             :status (:status payload)
             :streaming-text "")

      :action/approval-required
      (swap! app-state assoc
             :status :waiting-approval
             :pending-approval payload)

      :action/approval-settled
      (swap! app-state (fn [s]
                         (if (= (get-in s [:pending-approval :id]) (:approval-id payload))
                           (assoc s :pending-approval nil :status :running)
                           s)))

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
        status (:status @app-state)
        ws (:workspace @app-state)]
    [:header {:style {:display "flex" :justify-content "space-between" :align-items "center" :padding "0.6rem 0"}}
     [:hgroup {:style {:margin 0}}
      [:h3 {:style {:margin 0}} "Botashka"]
      [:small {:style {:margin 0 :color "var(--pico-muted-color)"}} (str "Workspace: " ws)]]
     [:div
      [:span {:class (str "badge " (if connected? "badge-online" "badge-offline"))}
       (if connected? "🟢 CONNECTED" "🔴 DISCONNECTED")]
      [:span {:style {:margin-left "0.5rem" :color "var(--pico-muted-color)" :font-size "0.8rem"}}
       (str "State: " (name status))]]]))

(defn format-tool-args [args]
  (cond
    (map? args) (pr-str args)
    (string? args) args
    :else (str args)))

(defn approval-modal-view []
  (when-let [approval (:pending-approval @app-state)]
    [:article {:class "tool-box" :style {:border-color "var(--pico-primary)"}}
     [:header
      [:strong "⚠️ 权限请求：允许执行以下敏感工具调用吗？"]]
     [:div
      [:p [:strong "Tool: "] [:code (str (:tool approval))]]
      [:p [:strong "Arguments: "] [:pre [:code (format-tool-args (:args approval))]]]]
     [:footer {:style {:display "flex" :gap "0.5rem" :justify-content "flex-end"}}
      [:button {:class "secondary outline"
                :on-click (fn []
                            (swap! app-state assoc :pending-approval nil :status :running)
                            (send-event! {:type :action/decision
                                          :payload {:approval-id (:id approval)
                                                    :approved? false}}))}
       "拒绝 (Deny)"]
      [:button {:on-click (fn []
                            (swap! app-state assoc :pending-approval nil :status :running)
                            (send-event! {:type :action/decision
                                          :payload {:approval-id (:id approval)
                                                    :approved? true}}))}
       "批准执行 (Approve)"]]]))

(defn timeline-item-view [item]
  [:article {:key (:id item) :class "timeline-card"}
   (case (:role item)
     :user
     [:div
      [:header {:style {:display "flex" :justify-content "space-between"}}
       [:strong "👤 You"]]
      [:p {:style {:margin 0}} (:text item)]]

     :agent
     [:div
      [:header {:style {:display "flex" :justify-content "space-between"}}
       [:strong "🤖 Botashka"]]
      [:div {:style {:white-space "pre-wrap"}} (:text item)]]

     :tool-proposed
     [:details {:open false}
      [:summary {:style {:cursor "pointer"}}
       [:strong (str "● Tool Proposed: " (:tool item))]]
      [:pre {:style {:margin-top "0.5rem"}}
       [:code (str "Arguments: " (format-tool-args (:args item)))]]]

     :tool
     (let [err? (:error? item)]
       [:details {:open (boolean err?)}
        [:summary {:style {:cursor "pointer" :color (if err? "var(--pico-del-color)" "inherit")}}
         [:strong (if err?
                    (str "✖ Tool Failed: " (:tool item))
                    (str "✔ Tool Result: " (:tool item)))]]
        [:pre {:style {:margin-top "0.5rem"
                       :border (if err? "1px solid var(--pico-del-color)" "none")}}
         [:code (:result item)]]])

     :system
     [:div
      [:header [:strong {:style {:color "var(--pico-del-color)"}} "⚠️ System"]]
      [:p {:style {:margin 0 :color "var(--pico-del-color)"}} (:text item)]])])

(defn timeline-view []
  (let [{:keys [timeline streaming-text]} @app-state]
    [:section
     (for [item timeline]
       ^{:key (:id item)} [timeline-item-view item])
     ;; 流式打字机卡片
     (when (not (empty? streaming-text))
       [:article {:class "timeline-card"}
        [:header [:strong "🤖 Botashka (Thinking / Responding...)"]]
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
                             (swap! app-state assoc :input-text "" :status :running)
                             (send-event! {:type :prompt/submit
                                           :payload {:text text}}))))}
     [:fieldset {:role "group"}
      [:input {:type "text"
               :placeholder (if disabled? "Botashka 正在处理任务..." "输入 Prompt / 需求...")
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
