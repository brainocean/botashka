#!/usr/bin/env bb
(ns read-file
  (:require [babashka.fs :as fs]
            [botashka.workspace :as ws]
            [clojure.edn :as edn]))

(def ^:private max-file-characters 12000)

(def metadata
  {:name "read_file"
   :description "Reads text from a workspace-relative file path."
   :parameters [:map
                [:path {:description "Workspace-relative file path to read"}
                 :string]]
   :returns [:or
             [:map [:content :string]]
             [:map [:error [:map [:kind :keyword]]]]]})

(defn run
  [{:keys [workspace path]}]
  (let [{target-file :target error :error} (ws/resolve-path workspace path)]
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
