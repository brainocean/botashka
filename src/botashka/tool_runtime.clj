(ns botashka.tool-runtime
  (:refer-clojure :exclude [run!])
  (:require [babashka.process :as process]
            [clojure.edn :as edn]))

(def default-timeout-ms 30000)
(def default-max-characters 50000)

(defn run!
  "Runs a Babashka code tool with EDN stdin and decodes its EDN stdout.
   Enforces timeout and bounds stdout length."
  ([program input]
   (run! program input {:timeout-ms default-timeout-ms
                        :max-characters default-max-characters}))
  ([program input {:keys [timeout-ms max-characters]
                   :or {timeout-ms default-timeout-ms
                        max-characters default-max-characters}}]
   (try
     (let [proc (process/process ["bb" program]
                                 {:in (pr-str input)
                                  :out :string
                                  :err :string})
           done? (deref proc timeout-ms nil)]
       (if-not done?
         (do
           (process/destroy proc)
           {:error {:kind :tool-timeout
                    :program program
                    :timeout-ms timeout-ms}})
         (let [{:keys [exit out err]} @proc]
           (if (zero? exit)
             (let [bounded-out (if (> (count out) max-characters)
                                 (subs out 0 max-characters)
                                 out)]
               (try
                 (edn/read-string bounded-out)
                 (catch Exception error
                   {:error {:kind :invalid-tool-output
                            :program program
                            :message (.getMessage error)
                            :stdout bounded-out}})))
             {:error {:kind :tool-process-failed
                      :program program
                      :exit exit
                      :stderr err}}))))
     (catch Exception error
       {:error {:kind :tool-execution-failed
                :program program
                :message (.getMessage error)}}))))
(defn describe!
  "Reads a code tool's self-described provider metadata."
  [program]
  (run! program {:command :describe}))
