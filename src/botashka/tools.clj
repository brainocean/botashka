(ns botashka.tools
  (:require [babashka.fs :as fs]
            [botashka.tool-runtime :as tool-runtime]))

(defn- code-tool-programs
  []
  (->> (fs/list-dir "tools")
       (filter #(= "clj" (fs/extension %)))
       (map str)
       sort))

(defn- code-tool-entry
  [workspace program]
  (let [{:keys [name description parameters] :as metadata}
        (tool-runtime/describe! program)]
    (when-not name
      (throw (ex-info "Code tool did not describe itself"
                      {:program program
                       :result metadata})))
    [name {:schema {:type "function"
                    :function {:name name
                               :description description
                               :parameters parameters}}
           :run #(tool-runtime/run! program
                                    (assoc % :workspace workspace))}]))

(defn code-tool-registry
  "Discovers self-describing Babashka tool artifacts from tools/*.clj."
  [workspace]
  (into {} (map #(code-tool-entry workspace %) (code-tool-programs))))
