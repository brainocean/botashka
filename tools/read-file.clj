#!/usr/bin/env bb
(ns read-file
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def ^:private max-file-characters 12000)

(def metadata
  {:name "read_file"
   :description "Reads text from a workspace-relative file path."
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
  (let [file (workspace-path workspace path)]
    (if (fs/regular-file? file)
      {:content (subs (slurp (str file)) 0 (min max-file-characters (fs/size file)))}
      {:error {:kind :file-not-found
               :path path}})))

(let [input (-> *in* slurp edn/read-string)]
  (if (= :describe (:command input))
    (prn metadata)
    (-> input run prn)))
