(ns botashka.chat
  (:require [babashka.fs :as fs]
            [botashka.agent :as agent]
            [botashka.tools :as tools]
            [clojure.core.async :as async]
            [clojure.core.match :refer [match]]
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
        (match event
          {:type :streaming-token :delta delta}
          (do
            (reset! in-stream? true)
            (print delta)
            (flush))

          {:type :tool-proposed :data {:tool tool} :evidence {:arguments args}}
          (do
            (when @in-stream?
              (println)
              (reset! in-stream? false))
            (println (str ansi-yellow "● Tool: " ansi-bold tool ansi-reset
                          ansi-dim " " (format-tool-args args) ansi-reset)))

          {:type :tool-result :data {:outcome :ok}}
          (println (str ansi-green "  ✔ Result: :ok" ansi-reset))

          {:type :tool-result :evidence {:result {:error {:kind kind :message msg}}}}
          (println (str ansi-red "  ✖ Error: " kind " - " msg ansi-reset))

          {:type :tool-result :evidence {:result {:error {:kind kind}}}}
          (println (str ansi-red "  ✖ Error: " kind ansi-reset))

          {:type :model-failed :data {:error err}}
          (println (str ansi-red "✖ Model error: " err ansi-reset))

          {:type :run-finished :data {:status status :turn turn}}
          (do
            (when @in-stream?
              (println)
              (reset! in-stream? false))
            (println (str ansi-dim "─ (" status ", turn: " turn ")" ansi-reset "\n")))

          :else nil)
        (recur)))))

(defn- default-system [workspace]
  (str "You are Botashka, an expert coding agent. "
       "You are operating inside the workspace directory: " workspace ". "
       "All file operations (read_file, write_file, search_text, list_files) must use paths relative to this workspace root. "
       "run_command executes shell commands directly within this workspace root directory."))

(defn- banner [workspace registry]
  (let [model (or (System/getenv "BOTASHKA_MODEL") "gemini-3.1-flash-lite")]
    (println (str ansi-bold ansi-cyan "🤖 Botashka Interactive Coding Agent" ansi-reset))
    (println (str ansi-bold "Workspace: " ansi-reset ansi-cyan workspace ansi-reset))
    (println (str ansi-dim "Model: " model " | Tools: " (str/join ", " (sort (keys registry))) ansi-reset))
    (println (str ansi-dim "Type your prompt to start, or 'exit' / 'quit' to end session." ansi-reset "\n"))))

(defn chat-loop
  "Starts an interactive multi-turn REPL chat loop with real-time token streaming and tool visibility."
  ([] (chat-loop {}))
  ([{:keys [workspace system max-turns]
     :or {workspace "." max-turns 15}}]
   (let [canonical-ws (str (fs/canonicalize (or workspace ".")))]
     (when-not (fs/exists? canonical-ws)
       (fs/create-dirs canonical-ws))
     (let [registry (tools/code-tool-registry canonical-ws)
           system-prompt (or system (default-system canonical-ws))]
       (banner canonical-ws registry)
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
                                                 :system system-prompt
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
           (println (str "\n" ansi-dim "Goodbye!" ansi-reset))))))))
