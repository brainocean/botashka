#!/usr/bin/env bb
(ns run-command
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private max-output-characters 20000)

(def metadata
  {:name "run_command"
   :description "Executes a shell command (e.g. 'bb test', 'git status', 'git diff') within the workspace directory and captures exit code, stdout, and stderr."
   :parameters [:map
                [:command {:description "Shell command line to execute within the workspace"} :string]]
   :returns [:or
             [:map
              [:exit :int]
              [:out :string]
              [:err :string]]
             [:map [:error [:map [:kind :keyword]]]]]})

(defn- truncate-output
  [text limit]
  (if (> (count text) limit)
    (str (subs text 0 limit) "\n... (truncated)")
    text))

(defn run
  [{:keys [workspace command]}]
  (if (or (nil? command) (str/blank? command))
    {:error {:kind :invalid-arguments
             :message "Command must be a non-empty string"}}
    (try
      (let [canonical-ws (fs/canonicalize (or workspace "."))]
        (if-not (fs/directory? canonical-ws)
          {:error {:kind :invalid-workspace
                   :workspace workspace}}
          (let [shell-cmd (if (fs/windows?)
                            ["cmd.exe" "/c" command]
                            ["/bin/sh" "-c" command])
                {:keys [exit out err]}
                (apply process/shell {:dir (str canonical-ws)
                                      :out :string
                                      :err :string
                                      :continue true}
                       shell-cmd)]
            {:exit exit
             :out (truncate-output out max-output-characters)
             :err (truncate-output err max-output-characters)})))
      (catch Exception error
        {:error {:kind :command-execution-failed
                 :command command
                 :message (.getMessage error)}}))))

(when-not *compile-files*
  (let [input (try (-> *in* slurp edn/read-string)
                   (catch Exception _ nil))]
    (when input
      (if (= :describe (:command input))
        (prn metadata)
        (-> input run prn)))))
