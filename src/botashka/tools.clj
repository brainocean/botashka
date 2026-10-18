(ns botashka.tools
  (:require [babashka.fs :as fs]))

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

(defn- read-file
  [workspace {:keys [path]}]
  (let [file (workspace-path workspace path)]
    (if (fs/regular-file? file)
      {:content (subs (slurp (str file)) 0 (min max-file-characters (fs/size file)))}
      {:error {:kind :file-not-found
               :path path}})))

(defn read-only-registry
  "Returns workspace-confined list-files and read-file tool definitions."
  [workspace]
  {"list_files"
   {:schema {:type "function"
             :function {:name "list_files"
                        :description "Lists files and directories at a workspace-relative directory path."
                        :parameters {:type "object"
                                     :properties {:path {:type "string"}}
                                     :required ["path"]
                                     :additionalProperties false}}}
    :run #(list-files workspace %)}

   "read_file"
   {:schema {:type "function"
             :function {:name "read_file"
                        :description "Reads text from a workspace-relative file path."
                        :parameters {:type "object"
                                     :properties {:path {:type "string"}}
                                     :required ["path"]
                                     :additionalProperties false}}}
    :run #(read-file workspace %)}})
