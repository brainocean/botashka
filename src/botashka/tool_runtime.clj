(ns botashka.tool-runtime
  (:require [babashka.process :as process]
            [clojure.edn :as edn]))

(defn run!
  "Runs a Babashka code tool with EDN stdin and decodes its EDN stdout."
  [program input]
  (let [{:keys [exit out err]}
        (process/shell {:continue true
                        :in (pr-str input)
                        :out :string
                        :err :string}
                       "bb" program)]
    (if (zero? exit)
      (try
        (edn/read-string out)
        (catch Exception error
          {:error {:kind :invalid-tool-output
                   :program program
                   :message (.getMessage error)
                   :stdout out}}))
      {:error {:kind :tool-process-failed
               :program program
               :exit exit
               :stderr err}})))
