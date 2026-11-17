(ns botashka.server-test
  (:require [botashka.server :as server]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(deftest server-app-handler-static-assets
  (testing "Serves index.html on root"
    (let [resp (server/app-handler {:uri "/"})]
      (is (= 200 (:status resp)))
      (is (re-find #"Botashka Web Console" (:body resp)))))

  (testing "Serves app.cljs ClojureScript client source"
    (let [resp (server/app-handler {:uri "/app.cljs"})]
      (is (= 200 (:status resp)))
      (is (re-find #"botashka.app" (:body resp)))))

  (testing "Serves botashka.css stylesheet"
    (let [resp (server/app-handler {:uri "/botashka.css"})]
      (is (= 200 (:status resp)))
      (is (re-find #"--pico-font-size: 14px" (:body resp)))))

  (testing "Serves vendor offline assets"
    (let [resp (server/app-handler {:uri "/vendor/pico.min.css"})]
      (is (= 200 (:status resp))))
    (let [resp (server/app-handler {:uri "/vendor/scittle.js"})]
      (is (= 200 (:status resp))))
    (let [resp (server/app-handler {:uri "/vendor/react.production.min.js"})]
      (is (= 200 (:status resp))))
    (let [resp (server/app-handler {:uri "/vendor/react-dom.production.min.js"})]
      (is (= 200 (:status resp))))
    (let [resp (server/app-handler {:uri "/vendor/scittle.reagent.js"})]
      (is (= 200 (:status resp)))))

  (testing "Returns 404 for nonexistent routes"
    (let [resp (server/app-handler {:uri "/nonexistent.file"})]
      (is (= 404 (:status resp))))))

(deftest server-authorization-policy-logic
  (testing "Authorization policy function existence and non-sensitive tool bypass"
    (let [auth-fn @#'server/web-authorization-policy]
      (is (fn? auth-fn))
      ;; Non-sensitive tool like read_file returns :allow immediately
      (is (= :allow (auth-fn {} {:tool "read_file" :arguments {:path "README.md"}}))))))
