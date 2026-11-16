(ns botashka.chat
  (:require [botashka.agent :as agent]
            [botashka.tools :as tools]
            [clojure.core.async :as async]
            [clojure.string :as str]))

(def ^:private ansi-reset "\u001b[0m")
(def ^:private ansi-bold "\u001b[1m")
(def ^:private ansi-cyan "\u001b[36m")
(def ^:private ansi-green "\u001b[32m")
(def ^:private ansi-yellow "\u001b[33m")
(def ^:private ansi-red "\u001b[31m")
(def ^:private ansi-dim "\u001b[2m")

(defn- format-tool-args [args]
  (if (map? args)
    (str/join " " (map (fn [[k v]] (str (name k) "=" (pr-str v))) args))
    (pr-str args)))

(defn- consume-events!
  "Consumes the event channel in the current thread and renders streaming outputs."
  [event-chan]
  (let [in-stream? (atom false)]
    (loop []
      (when-let [event (async/<!! event-chan)]
        (case (:type event)
          :streaming-token
          (do
            (reset! in-stream? true)
            (print (:delta event))
            (flush))

          :tool-proposed
          (do
            (when @in-stream?
              (println)
              (reset! in-stream? false))
            (println (str ansi-yellow "● Tool: " ansi-bold (get-in event [:data :tool]) ansi-reset
                          ansi-dim " " (format-tool-args (get-in event [:evidence :arguments])) ansi-reset)))

          :tool-result
          (let [outcome (get-in event [:data :outcome])
                err (get-in event [:evidence :result :error])]
            (if (= outcome :ok)
              (println (str ansi-green "  ✔ Result: :ok" ansi-reset))
              (println (str ansi-red "  ✖ Error: " (or (:kind err) outcome)
                            (when-let [msg (:message err)] (str " - " msg)) ansi-reset))))

          :model-failed
          (println (str ansi-red "✖ Model error: " (get-in event [:data :error]) ansi-reset))

          :run-finished
          (do
            (when @in-stream?
              (println)
              (reset! in-stream? false))
            (println (str ansi-dim "─ (" (get-in event [:data :status]) ", turn: " (get-in event [:data :turn]) ")" ansi-reset "\n")))

          ;; other canonical events pass silently
          nil)
        (recur)))))

(defn- banner []
  (let [model (or (System/getenv "BOTASHKA_MODEL") "gemini-3.1-flash-lite")
        tools (tools/code-tool-registry ".")]
    (println (str ansi-bold ansi-cyan "🤖 Botashka Interactive Coding Agent" ansi-reset))
    (println (str ansi-dim "Model: " model " | Tools: " (str/join ", " (sort (keys tools))) ansi-reset))
    (println (str ansi-dim "Type your prompt to start, or 'exit' / 'quit' to end session." ansi-reset "\n"))))

(defn chat-loop
  "Starts an interactive multi-turn REPL chat loop with real-time token streaming and tool visibility."
  ([] (chat-loop {}))
  ([{:keys [workspace system max-turns]
     :or {workspace "." max-turns 15}}]
   (banner)
   (let [registry (tools/code-tool-registry workspace)]
     (loop [messages []
            turn-count 1]
       (print (str ansi-bold ansi-cyan "botashka [" turn-count "]> " ansi-reset))
       (flush)
       (if-let [input (read-line)]
         (let [trimmed (str/trim input)]
           (cond
             (or (empty? trimmed) (= trimmed "exit") (= trimmed "quit") (= trimmed ":q"))
             (println (str ansi-dim "Goodbye!" ansi-reset))

             :else
             (let [event-chan (async/chan 100)
                   run-future (future
                                (try
                                  (agent/run! {:prompt trimmed
                                               :messages messages
                                               :system system
                                               :tools registry
                                               :max-turns max-turns
                                               :event-chan event-chan})
                                  (catch Exception error
                                    (async/close! event-chan)
                                    {:error error})))]
               (consume-events! event-chan)
               (let [result @run-future]
                 (if (:error result)
                   (do
                     (println (str ansi-red "Execution error: " (.getMessage (:error result)) ansi-reset))
                     (recur messages turn-count))
                   (recur (or (:messages result) messages) (inc turn-count)))))))
         ;; EOF (Ctrl+D)
         (println (str "\n" ansi-dim "Goodbye!" ansi-reset)))))))
