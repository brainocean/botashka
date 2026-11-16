#!/usr/bin/env bb
(ns write-file
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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

(defn- resolve-workspace-path
  [workspace path]
  (if (or (nil? path) (str/blank? path))
    {:error {:kind :invalid-arguments
             :message "Path must be a non-empty string"}}
    (try
      (let [canonical-ws (fs/canonicalize (or workspace "."))
            target (fs/canonicalize (fs/path canonical-ws path))]
        (if (fs/starts-with? target canonical-ws)
          {:workspace canonical-ws
           :target target}
          {:error {:kind :path-outside-workspace
                   :path path}}))
      (catch Exception error
        {:error {:kind :invalid-path
                 :path path
                 :message (.getMessage error)}}))))

(defn run
  [{:keys [workspace path content]}]
  (let [{canonical-ws :workspace target :target error :error}
        (resolve-workspace-path workspace path)]
    (if error
      {:error error}
      (try
        (let [parent (fs/parent target)]
          (when (and parent (not (fs/exists? parent)))
            (fs/create-dirs parent))
          (spit (str target) (or content ""))
          (let [rel-path (str (fs/relativize canonical-ws target))]
            {:path rel-path
             :bytes_written (count (.getBytes (or content "") "UTF-8"))}))
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
