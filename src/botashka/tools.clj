(ns botashka.tools
  (:require [babashka.fs :as fs]
            [botashka.tool-runtime :as tool-runtime]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]))

(defn- code-tool-programs
  [tools-dir]
  (if (fs/directory? tools-dir)
    (->> (fs/list-dir tools-dir)
         (filter #(= "clj" (fs/extension %)))
         (map str)
         sort)
    []))

(defn- valid-tool-metadata?
  [{:keys [name description parameters]}]
  (and (string? name)
       (not (clojure.string/blank? name))
       (string? description)
       (try
         (boolean (m/schema parameters))
         (catch Exception _ false))))

(defn- code-tool-entry
  [workspace program]
  (let [metadata (tool-runtime/describe! program)]
    (if (:error metadata)
      {:error {:kind :tool-describe-failed
               :program program
               :details metadata}}
      (if (valid-tool-metadata? metadata)
        (let [{:keys [name description parameters]} metadata
              provider-schema (json-schema/transform parameters)]
          {:entry [name {:name name
                         :description description
                         :parameters parameters
                         :schema {:type "function"
                                  :function {:name name
                                             :description description
                                             :parameters provider-schema}}
                         :run (fn [arguments]
                                (if-not (m/validate parameters arguments)
                                  {:error {:kind :invalid-arguments
                                           :tool name
                                           :details (me/humanize (m/explain parameters arguments))
                                           :explain (m/explain parameters arguments)}}
                                  (tool-runtime/run! program
                                                     (assoc arguments :workspace workspace))))}]})
        {:error {:kind :invalid-tool-metadata
                 :program program
                 :metadata metadata}}))))

(defn code-tool-registry
  "Discovers self-describing Babashka tool artifacts from tools/*.clj or specified tools-dir.
   Ignores malformed tools and records duplicate name conflicts."
  ([workspace]
   (code-tool-registry workspace "tools"))
  ([workspace tools-dir]
   (let [programs (code-tool-programs tools-dir)]
     (loop [progs programs
            registry {}]
       (if-let [prog (first progs)]
         (let [{:keys [entry error]} (code-tool-entry workspace prog)]
           (if error
             (recur (rest progs) registry)
             (let [[tool-name tool-val] entry]
               (if (contains? registry tool-name)
                 ;; Duplicate detected; preserve first, ignore duplicate
                 (recur (rest progs) registry)
                 (recur (rest progs) (assoc registry tool-name tool-val))))))
         registry)))))
