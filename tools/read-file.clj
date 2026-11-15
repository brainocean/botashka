#!/usr/bin/env bb
(ns read-file
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]))

(def ^:private max-file-characters 12000)

(def metadata
  {:name "read_file"
   :description "Reads text from a workspace-relative file path."
   :parameters [:map
                [:path {:description "Workspace-relative file path to read"}
                 :string]]})

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
  (let [{target-file :path error :error} (resolve-workspace-path workspace path)]
    (if error
      {:error error}
      (if (fs/regular-file? target-file)
        (let [content (slurp (str target-file))]
          {:content (subs content 0 (min max-file-characters (count content)))})
        {:error {:kind :file-not-found
                 :path path}}))))
(let [input (-> *in* slurp edn/read-string)]
  (if (= :describe (:command input))
    (prn metadata)
    (-> input run prn)))
