(ns botashka.agent
  (:refer-clojure :exclude [run!])
  (:require [botashka.command-executor :as command-executor]
            [botashka.run :as run]
            [clojure.core.async :as async])
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
  [{:keys [command-executor! action-policy authorization-policy run-id system prompt tools max-turns
           messages on-event event-chan close-chan? bus]
    :or {max-turns 8}}]
  (let [on-event (or on-event
                     (when event-chan
                       (fn [evt] (async/>!! event-chan evt))))
        action-policy (or action-policy run/default-action-policy)
        executor (or command-executor!
                     (command-executor/default-command-executor
                      (cond-> {}
                        authorization-policy (assoc :authorize authorization-policy)
                        on-event (assoc :on-event on-event)
                        bus (assoc :bus bus))))
        run (run/new-run {:run-id (or run-id (new-run-id))
                          :system system
                          :tools tools
                          :max-turns max-turns
                          :messages messages})
        run (executor run {:command :run/start
                           :prompt prompt})]
    (let [result (loop [run run]
                   (if (= :finished (:status run))
                     (run/terminal-result run)
                     (if-let [command (action-policy run)]
                       (recur (executor run command))
                       (run/terminal-result run))))]
      (when (and event-chan (not (false? close-chan?)))
        (async/close! event-chan))
      result)))
