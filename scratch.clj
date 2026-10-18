(ns botashka.scratch
  (:require [botashka.agent :as agent]
            [botashka.provider :as provider]
            [botashka.tools :as tools]
            [cheshire.core :as json]))

(comment

  (require 'botashka.provider :reload)

  (provider/complete!
    {:system "You are concise."
     :messages [{:role "user"
                 :content "Reply with exactly: pong."}]})

  (provider/complete-text!
    {:system "You are concise."
     :messages [{:role "user"
                 :content "Reply with exactly: pong."}]})

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

  (agent/run!
   {:system "Use the file tools to answer questions about the workspace."
    :prompt "Read README.md and state its exact title."
    :tools (tools/read-only-registry ".")})





  )
