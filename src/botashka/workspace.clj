(ns botashka.workspace
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn resolve-path
  "Safely resolves a path relative to the canonical workspace root.
   Guarantees the target stays within the workspace boundary.
   Returns {:workspace canonical-ws, :target canonical-target, :rel-path rel-target}
   or a structured error map on failure."
  ([workspace path]
   (resolve-path workspace path {}))
  ([workspace path {:keys [must-exist? require-path?]}]
   (if (and require-path? (or (nil? path) (str/blank? path)))
     {:error {:kind :invalid-arguments
              :message "Path must be a non-empty string"}}
     (try
       (let [canonical-ws (fs/canonicalize (or workspace "."))
             target (if (and path (not (str/blank? path)))
                      (fs/canonicalize (fs/path canonical-ws path))
                      canonical-ws)]
         (cond
           (not (fs/starts-with? target canonical-ws))
           {:error {:kind :path-outside-workspace
                    :path path}}

           (and must-exist? (not (fs/exists? target)))
           {:error {:kind :path-not-found
                    :path path}}

           :else
           {:workspace canonical-ws
            :target    target
            :rel-path  (str (fs/relativize canonical-ws target))}))
       (catch Exception error
         {:error {:kind :invalid-path
                  :path path
                  :message (.getMessage error)}})))))
