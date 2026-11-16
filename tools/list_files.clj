#!/usr/bin/env bb
(ns list-files
  (:require [babashka.fs :as fs]
            [botashka.workspace :as ws]
            [clojure.edn :as edn]))

(def metadata
  {:name "list_files"
   :description "Lists files and directories at a workspace-relative directory path."
   :parameters [:map
                [:path {:description "Workspace-relative directory path to list"}
                 :string]]
   :returns [:or
             [:map [:files [:vector :string]]]
             [:map [:error [:map [:kind :keyword]]]]]})

(defn run
  [{:keys [workspace path]}]
  (let [{directory :target error :error} (ws/resolve-path workspace path)]
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
