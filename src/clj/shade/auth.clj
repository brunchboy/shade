(ns shade.auth
  "Functions associated with users and authorization."
  (:require [shade.db.core :as db]
            [buddy.hashers :as hashers]))

(defn create-user!
  "Creates a user record, after hashing the raw password."
  [user]
  (db/create-user! (update user :pass hashers/derive)))

(defn update-user!
  "Updates a user record, hashing the raw password."
  [user]
  (db/update-user! (update user :pass hashers/derive)))
