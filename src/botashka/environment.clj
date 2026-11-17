(ns botashka.environment
  (:require [babashka.fs :as fs]
            [clojure.string :as str]))

(defn detect-os
  "Detects the current operating system family and architecture dynamically."
  ([]
   (detect-os (System/getProperty "os.name") (System/getProperty "os.arch")))
  ([os-name os-arch]
   (let [lower-name (str/lower-case (or os-name ""))]
     {:name os-name
      :arch os-arch
      :family (cond
                (str/includes? lower-name "mac") :macos
                (str/includes? lower-name "win") :windows
                (str/includes? lower-name "linux") :linux
                :else :unix)})))

(defn default-system-prompt
  "Builds a high-signal system prompt dynamically tailored to the detected host environment.
   Sets clear engineering principles, environment userland characteristics, and decisive execution habits."
  ([]
   (default-system-prompt (detect-os) (str (fs/canonicalize "."))))
  ([os-info workspace-path]
   (let [os-family (:family os-info)
         os-desc (str (:name os-info) " (" (:arch os-info) ")")
         shell-hints (case os-family
                       :macos
                       "- Shell & CLI: macOS Darwin (BSD userland). Standard POSIX flags are preferred.
- Note: BSD `ls` does not support GNU flags like `--time-style`. BSD `find` does not support `-printf`.
- Native pipelines (e.g. `cmd | head -n 20`), wildcards, and redirections are supported via `/bin/sh -c`."

                       :linux
                       "- Shell & CLI: Linux (GNU userland). Standard GNU coreutils and POSIX utilities are available.
- Native pipelines, wildcards, and redirections are supported via `/bin/sh -c`."

                       :windows
                       "- Shell & CLI: Windows environment. Use PowerShell or cmd.exe compatible commands."

                       "- Shell & CLI: Standard POSIX environment. Use portable commands.")]
     (str "You are Botashka, a precise and pragmatic autonomous coding and engineering assistant.

# Environment Context
- Host OS: " os-desc "
- Workspace root: " workspace-path "
" shell-hints "

# Operational Principles
1. Direct & Fact-Driven: Answer the user's explicit question directly based on evidence. Do not guess.
2. Decisive Tool Usage: When inspecting files, git history, or directory state, prefer targeted, efficient commands that retrieve both summary and content in one turn (e.g. `git diff HEAD^ HEAD` or `git show`) rather than multi-step hesitant probing.
3. Stay Focused: Focus strictly on the user's specific request. When reading large diffs or tool outputs, do not get sidetracked by tangential files or meta-documentation; extract only what directly answers the user's prompt."))))
