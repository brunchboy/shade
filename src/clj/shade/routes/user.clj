(ns shade.routes.user
  "Supports the creation, editing, and deletion of users."
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str]
            [ring.util.response :refer [redirect]]
            [shade.db.core :as db]
            [shade.layout :as layout]
            [shade.util :as util])
  (:import (java.util UUID)))

(defn list-users-page [{:keys [session] :as request}]
  (let [user-id (get-in session [ :identity :id])
        users   (db/list-users)
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "admin-users.html"
                   (merge (select-keys request [:active?])
                          {:user  (db/get-user {:id user-id})
                           :users users
                           :rooms rooms}))))

(defn user-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        other-id (when-let [id (:id path-params)] (UUID/fromString id))
        rooms    (db/list-rooms-for-user {:user user-id})
        other    (when other-id (db/get-user {:id other-id}))]
    (if (and other-id (not other))
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (layout/render request "admin-user.html"
                     (merge (select-keys request [:active?])
                            {:user    (db/get-user {:id user-id})
                             :rooms   rooms
                             :other   other
                             :last (util/format-timestamp-relative (:last_login other))})))))

(defn user-save [{:keys [path-params form-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        user     (db/get-user {:id user-id})
        other-id (when-let [id (:id path-params)] (UUID/fromString id))
        other    (when other-id (db/get-user {:id other-id}))
        rooms    (db/list-rooms-for-user {:user user-id})
        email    (form-params "email")
        name     (form-params "name")
        new-pw   (form-params "new_password")
        active   (some? (form-params "active"))
        admin    (some? (form-params "admin"))
        errors   (cond-> []
                   (str/blank? name)
                   (conj "Name cannot be empty.")

                   (str/blank? email)
                   (conj "Email cannot be empty.")

                   (and (not (str/blank? email))
                        (let [match (db/get-user-by-email {:email email})]
                          (and (some? match)
                               (not= (:id match) other-id))))
                   (conj "That email is in use by another user.")

                   (and (str/blank? new-pw)
                        (not other))
                   (conj "Password cannot be empty.")

                   (and (not (str/blank? new-pw))
                        (not (<= 12 (.length new-pw))))
                   (conj "New password must be at least 12 characters long.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[a-z].*" new-pw)))
                   (conj "New password must contain a lowercase letter.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[A-Z].*" new-pw)))
                   (conj "New password must contain an uppercase letter.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[0-9].*" new-pw)))
                   (conj "New password must contain a number.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[^0-9A-Za-z].*" new-pw)))
                   (conj "New password must contain a special character that is not a letter or number.")

                   (when-let [existing (db/get-user-by-email {:email email})]
                     (not= (:id existing) other-id))
                   (conj "Another user with this email already exists."))]
    (if (seq errors)
      (layout/render request "admin-user.html"
                     (merge (select-keys request [:active?])
                            {:user  user
                             :rooms rooms
                             :other (merge other
                                           {:email email
                                            :name  name
                                            :admin admin
                                            :active active})
                             :error (str/join " " errors)}))
      (do
        (if other-id
          (db/update-user! (merge other
                                  {:email email
                                   :name      name
                                   :admin     admin
                                   :is_active active}
                                  (when-not (str/blank? new-pw)
                                    {:pass (hashers/derive new-pw)})))
          (db/create-user! {:name      name
                            :email     email
                            :pass      (hashers/derive new-pw)
                            :admin     admin
                            :is_active active}))
        (redirect "/admin/users")))))

(defn delete-user-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        rooms    (db/list-rooms-for-user {:user user-id})
        other-id (when-let [id (:id path-params)] (UUID/fromString id))
        other    (when other-id (db/get-user {:id other-id}))]
    (if-not other
      (layout/error-page {:status 404 :title "404 - User not found"})
      (layout/render request "admin-delete-user.html"
                     (merge (select-keys request [:active?])
                            {:user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :other  other})))))

(defn user-delete [{:keys [path-params]}]
  (let [other-id (when-let [id (:id path-params)] (UUID/fromString id))]
    (if-not other-id
      (layout/error-page {:status 404 :title "404 - User not found"})
      (do
        (db/delete-user! {:id other-id} )
        (redirect "/admin/users")))))
