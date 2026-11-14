(ns botashka.agent
  (:refer-clojure :exclude [run!])
  (:require [botashka.command-executor :as command-executor]
            [botashka.run :as run])
  (:import [java.util UUID]))

(defn- new-run-id
  "Creates a unique run identifier for the imperative shell."
  []
  (str (UUID/randomUUID)))

(defn run!
  "Runs an event-driven agent state machine through an injected command executor.

   `next-command` is a pure driver. `command-executor!` owns model calls,
   tools, clocks, and all other imperative command dependencies."
  [{:keys [command-executor! next-command run-id system prompt tools max-turns]
    :or {command-executor! (command-executor/default-command-executor)
         next-command run/model-driver
         max-turns 8}}]
  (let [run (run/new-run {:run-id (or run-id (new-run-id))
                          :system system
                          :tools tools
                          :max-turns max-turns})
        run (command-executor! run {:command :run/start
                                    :prompt prompt})]
    (loop [run run]
      (if-let [command (next-command run)]
        (recur (command-executor! run command))
        (run/terminal-result run)))))
