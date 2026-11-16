#!/usr/bin/env bb
(ns search-text
  (:require [babashka.fs :as fs]
            [botashka.workspace :as ws]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private max-matches 50)
(def ^:private ignore-dirs #{".git" ".omp" ".cpcache" ".clj-kondo" "target" "node_modules"})

(def metadata
  {:name "search_text"
   :description "Searches for a literal string query across workspace text files, returning matching paths, line numbers, and line excerpts."
   :parameters [:map
                [:query {:description "Literal text string to search for"} :string]
                [:path {:description "Optional workspace-relative directory or file path to scope the search"
                        :optional true} :string]]
   :returns [:or
             [:map
              [:matches [:vector [:map
                                  [:path :string]
                                  [:line :int]
                                  [:match :string]]]]
              [:total :int]
              [:truncated :boolean]]
             [:map [:error [:map [:kind :keyword]]]]]})

(defn- search-file
  [canonical-ws file query]
  (try
    (let [rel-path (str (fs/relativize canonical-ws file))]
      (with-open [rdr (io/reader (str file))]
        (loop [lines (line-seq rdr)
               line-no 1
               results []]
          (if-let [line (first lines)]
            (if (str/includes? line query)
              (recur (rest lines)
                     (inc line-no)
                     (conj results {:path rel-path
                                    :line line-no
                                    :match (str/trim line)}))
              (recur (rest lines) (inc line-no) results))
            results))))
    (catch Exception _
      [])))

(defn- collect-files
  [target-root]
  (if (fs/regular-file? target-root)
    [target-root]
    (->> (fs/glob target-root "**")
         (filter (fn [f]
                   (and (fs/regular-file? f)
                        (not (some (fn [part] (contains? ignore-dirs (str (fs/file-name part))))
                                   (fs/components f)))))))))

(defn run
  [{:keys [workspace path query]}]
  (if (or (nil? query) (str/blank? query))
    {:error {:kind :invalid-arguments
             :message "Query must be a non-empty string"}}
    (let [{canonical-ws :workspace target-root :target error :error}
          (ws/resolve-path workspace path {:must-exist? true})]
      (if error
        {:error error}
        (let [files (collect-files target-root)
              all-matches (loop [f-list files
                                 acc []]
                            (if-let [f (first f-list)]
                              (let [matches (search-file canonical-ws f query)
                                    next-acc (into acc matches)]
                                (if (>= (count next-acc) max-matches)
                                  next-acc
                                  (recur (rest f-list) next-acc)))
                              acc))
              truncated? (>= (count all-matches) max-matches)
              bounded-matches (vec (take max-matches all-matches))]
          {:matches bounded-matches
           :total (count bounded-matches)
           :truncated truncated?})))))

(when-not *compile-files*
  (let [input (try (-> *in* slurp edn/read-string)
                   (catch Exception _ nil))]
    (when input
      (if (= :describe (:command input))
        (prn metadata)
        (-> input run prn)))))
