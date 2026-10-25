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

(def ^:private max-file-characters 12000)

(defn- workspace-path
  [workspace path]
  (let [workspace (fs/canonicalize workspace)
        target (fs/canonicalize (fs/path workspace path))]
    (when-not (fs/starts-with? target workspace)
      (throw (ex-info "Path is outside the workspace"
                      {:workspace (str workspace)
                       :path path})))
    target))

(defn- list-files
  [workspace {:keys [path]}]
  (let [directory (workspace-path workspace path)]
    (if (fs/directory? directory)
      {:files (->> (fs/list-dir directory)
                   (map #(str (fs/file-name %)))
                   sort
                   vec)}
      {:error {:kind :not-a-directory
               :path path}})))


(defn read-only-registry
  "Returns read-only tool definitions, including discovered code tool artifacts."
  [workspace]
  (merge
   {"list_files"
    {:schema {:type "function"
              :function {:name "list_files"
                         :description "Lists files and directories at a workspace-relative directory path."
                         :parameters {:type "object"
                                      :properties {:path {:type "string"}}
                                      :required ["path"]
                                      :additionalProperties false}}}
     :run #(list-files workspace %)}}
   (code-tool-registry workspace)))
