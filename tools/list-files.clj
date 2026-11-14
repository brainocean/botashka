#!/usr/bin/env bb
(ns list-files
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def metadata
  {:name "list_files"
   :description "Lists files and directories at a workspace-relative directory path."
   :parameters {:type "object"
                :properties {:path {:type "string"}}
                :required ["path"]
                :additionalProperties false}})

(defn- resolve-workspace-path
  [workspace path]
  (try
    (let [workspace (fs/canonicalize workspace)
          target (fs/canonicalize (fs/path workspace (or path "")))]
      (if (fs/starts-with? target workspace)
        {:path target}
        {:error {:kind :path-outside-workspace
                 :path path}}))
    (catch Exception error
      {:error {:kind :invalid-path
               :path path
               :message (.getMessage error)}})))

(defn run
  [{:keys [workspace path]}]
  (let [{directory :path error :error} (resolve-workspace-path workspace path)]
    (if error
      {:error error}
      (if (fs/directory? directory)
        {:files (->> (fs/list-dir directory)
                     (map #(str (fs/file-name %)))
                     sort
                     vec)}
        {:error {:kind :not-a-directory
                 :path path}}))))

(let [input (-> *in* slurp edn/read-string)]
  (if (= :describe (:command input))
    (prn metadata)
    (-> input run prn)))
