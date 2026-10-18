(ns botashka.scratch
  (:require [botashka.provider :as provider]))

(comment
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


  )
