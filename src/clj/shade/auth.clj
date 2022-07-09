(ns shade.auth
  "Functions associated with users and authorization."
  (:require [shade.db.core :as db]
            [buddy.hashers :as hashers]
            [jdbc-ring-session.core :as jdbc-store]
            [ring.middleware.session.store :as session-store]))

(defn create-user!
  "Creates a user record, after hashing the raw password."
  [user]
  (db/create-user! (update user :pass hashers/derive)))

(defn update-user!
  "Updates a user record, hashing the raw password."
  [user]
  (db/update-user! (update user :pass hashers/derive)))

(defn clear-user-sessions
  "Removes any sessions associated with the supplied user, called when
  they have changed their password to log out any devices that were
  logged in using the old password."
  [user]
  (let [store (jdbc-store/jdbc-store db/*db*)]
    (doseq [session-id (map :session_id (db/list-session-ids))]
      (when (= (:id user) (get-in (session-store/read-session store session-id) [:identity :id]))
        (session-store/delete-session store session-id)))))
