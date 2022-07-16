(ns shade.db.core-test
  (:require
   [shade.db.core :refer [*db*] :as db]
   [java-time.pre-java8]
   [luminus-migrations.core :as migrations]
   [clojure.test :refer :all]
   [next.jdbc :as jdbc]
   [shade.config :refer [env]]
   [mount.core :as mount]))

(use-fixtures
  :once
  (fn [f]
    (mount/start
     #'shade.config/env
     #'shade.db.core/*db*)
    (migrations/migrate ["migrate"] (select-keys env [:database-url]))
    (f)))

(deftest test-users
  (jdbc/with-transaction [t-conn *db* {:rollback-only true}]
    (is (= 1 (db/create-user!
              t-conn
              {:name  "Sam Smith"
               :email "sam.smith@example.com"
               :admin false
               :pass  "pass"}
              {})))
    (is (= {:name       "Sam Smith"
            :email      "sam.smith@example.com"
            :pass       "pass"
            :admin      false
            :last_login nil
            :is_active  nil}
           (-> (db/get-user-by-email t-conn {:email "sam.smith@example.com"} {})
               (dissoc :id))))))
