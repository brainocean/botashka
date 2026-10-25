(ns botashka.scratch
  (:require [botashka.agent :as agent]
            [botashka.provider :as provider]
            [botashka.tool-runtime :as tool-runtime]
            [botashka.tools :as tools]
            [cheshire.core :as json]))

(comment

  ;; Reload the agent namespace after editing src/botashka/agent.clj.
  (require 'botashka.agent :reload)

  ;; Probe a plain OpenAI-compatible completion and inspect its raw response.
  (provider/complete!
    {:system "You are concise."
     :messages [{:role "user"
                 :content "Reply with exactly: pong."}]})

  ;; Extract only the first assistant text plus completion metadata.
  (provider/complete-text!
    {:system "You are concise."
     :messages [{:role "user"
                 :content "Reply with exactly: pong."}]})

  ;; Force the provider to return a calculator tool call; inspect its raw protocol shape.
  (provider/complete!
    {:messages [{:role "user"
                 :content "What is 2 plus 3? Use the calculator."}]
     :tools [{:type "function"
              :function {:name "calculator"
                         :description "Adds two integers."
                         :parameters {:type "object"
                                      :properties {:left {:type "integer"}
                                                   :right {:type "integer"}}
                                      :required ["left" "right"]
                                      :additionalProperties false}}}]
     :tool-choice {:type "function"
                   :function {:name "calculator"}}})

  ;; Manually complete one tool round trip: parse arguments, calculate, send the result back.
  (let [tools [{:type "function"
                :function {:name "calculator"
                           :description "Adds two integers."
                           :parameters {:type "object"
                                        :properties {:left {:type "integer"}
                                                     :right {:type "integer"}}
                                        :required ["left" "right"]
                                        :additionalProperties false}}}]
        user-message {:role "user"
                      :content "What is 2 plus 3? Use the calculator."}
        first-response (provider/complete!
                        {:messages [user-message]
                         :tools tools
                         :tool-choice {:type "function"
                                       :function {:name "calculator"}}})
        assistant-message (get-in first-response [:choices 0 :message])
        tool-call (first (:tool_calls assistant-message))
        {:keys [left right]} (json/parse-string
                              (get-in tool-call [:function :arguments])
                              true)
        tool-result (+ left right)]
    (provider/complete!
     {:messages [user-message
                 assistant-message
                 {:role "tool"
                  :tool_call_id (:id tool-call)
                  :content (str tool-result)}]
      :tools tools}))

  ;; Run the bounded agent loop with an in-process calculator tool.
  (agent/run!
   {:system "Use the calculator for arithmetic questions."
    :prompt "What is 2 plus 3?"
    :tools {"calculator"
            {:schema {:type "function"
                      :function {:name "calculator"
                                 :description "Adds two integers."
                                 :parameters {:type "object"
                                              :properties {:left {:type "integer"}
                                                           :right {:type "integer"}}
                                              :required ["left" "right"]
                                              :additionalProperties false}}}
             :run (fn [{:keys [left right]}]
                    {:result (+ left right)})}}})

  ;; Answer a real workspace question through list/read file tools.
  (agent/run!
   {:system "Use the file tools to answer questions about the workspace."
    :prompt "Read README.md and state its exact title."
    :tools (tools/read-only-registry ".")})

  ;; Inspect the structured execution trace of the workspace agent run.
  (-> (agent/run!
       {:system "Use the file tools to answer questions about the workspace."
        :prompt "Read README.md and state its exact title."
        :tools (tools/read-only-registry ".")})
      :trace)

  ;; Recover from a missing file by listing the workspace and reading the actual README.
  (agent/run!
   {:system "Use file tools. If a file is missing, inspect the directory and recover."
    :prompt "Read the project title from README.txt."
    :tools (tools/read-only-registry ".")})

  ;; Run the read-file code artifact as a separate Babashka process.
  (tool-runtime/run! "tools/read-file.clj"
                     {:workspace "."
                      :path "README.md"})








  )
