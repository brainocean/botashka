#!/usr/bin/env bb
(ns write-file
  (:require [babashka.fs :as fs]
            [botashka.workspace :as ws]
            [clojure.edn :as edn]))

(def metadata
  {:name "write_file"
   :description "Writes text content to a workspace-relative file path, creating parent directories if needed."
   :parameters [:map
                [:path {:description "Workspace-relative file path to create or overwrite"} :string]
                [:content {:description "Full text content to write into the file"} :string]]
   :returns [:or
             [:map
              [:path :string]
              [:bytes_written :int]]
             [:map [:error [:map [:kind :keyword]]]]]})

(defn run
  [{:keys [workspace path content]}]
  (let [{canonical-ws :workspace target :target rel-path :rel-path error :error}
        (ws/resolve-path workspace path {:require-path? true})]
    (if error
      {:error error}
      (try
        (let [parent (fs/parent target)]
          (when (and parent (not (fs/exists? parent)))
            (fs/create-dirs parent))
          (spit (str target) (or content ""))
          {:path rel-path
           :bytes_written (count (.getBytes (or content "") "UTF-8"))})
        (catch Exception error
          {:error {:kind :write-failed
                   :path path
                   :message (.getMessage error)}})))))

(when-not *compile-files*
  (let [input (try (-> *in* slurp edn/read-string)
                   (catch Exception _ nil))]
    (when input
      (if (= :describe (:command input))
        (prn metadata)
        (-> input run prn)))))
