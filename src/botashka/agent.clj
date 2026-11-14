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

   `action-policy` is a pure function from run state to next command or nil.
   `command-executor!` owns model calls, tools, clocks, and all other imperative dependencies.
   `authorization-policy` optionally authorizes, rejects, or asks approval for selected commands."
  [{:keys [command-executor! action-policy authorization-policy run-id system prompt tools max-turns]
    :or {max-turns 8}}]
  (let [action-policy (or action-policy run/default-action-policy)
        executor (or command-executor!
                     (command-executor/default-command-executor
                      (when authorization-policy
                        {:authorize authorization-policy})))
        run (run/new-run {:run-id (or run-id (new-run-id))
                          :system system
                          :tools tools
                          :max-turns max-turns})
        run (executor run {:command :run/start
                           :prompt prompt})]
    (loop [run run]
      (if (= :finished (:status run))
        (run/terminal-result run)
        (if-let [command (action-policy run)]
          (recur (executor run command))
          (run/terminal-result run))))))
