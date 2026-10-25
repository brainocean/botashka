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

(defn- workspace-path
  [workspace path]
  (let [workspace (fs/canonicalize workspace)
        target (fs/canonicalize (fs/path workspace path))]
    (when-not (fs/starts-with? target workspace)
      (throw (ex-info "Path is outside the workspace"
                      {:workspace (str workspace)
                       :path path})))
    target))

(defn run
  [{:keys [workspace path]}]
  (let [directory (workspace-path workspace path)]
    (if (fs/directory? directory)
      {:files (->> (fs/list-dir directory)
                   (map #(str (fs/file-name %)))
                   sort
                   vec)}
      {:error {:kind :not-a-directory
               :path path}})))

(let [input (-> *in* slurp edn/read-string)]
  (if (= :describe (:command input))
    (prn metadata)
    (-> input run prn)))
