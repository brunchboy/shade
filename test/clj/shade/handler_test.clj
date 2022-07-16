(ns shade.handler-test
  (:require
    [clojure.test :refer :all]
    [ring.mock.request :refer :all]
    [shade.config]
    [shade.db.core]
    [shade.handler :refer :all]
    [shade.middleware.formats :as formats]
    [muuntaja.core :as m]
    [mount.core :as mount]))

(defn parse-json [body]
  (m/decode formats/instance "application/json" body))

(use-fixtures
  :once
  (fn [f]
    (mount/start #'shade.config/env
                 #'shade.handler/app-routes
                 #'shade.db.core/*db*)
    (f)))

(deftest test-app
  (testing "main route"
    (let [response ((app) (request :get "/"))]
      (is (= 302 (:status response)))))

  (testing "not-found route"
    (let [response ((app) (request :get "/invalid"))]
      (is (= 404 (:status response))))))
