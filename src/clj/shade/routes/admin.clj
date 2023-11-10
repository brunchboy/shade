(ns shade.routes.admin
  "Supports the admin landing page."
  (:require [shade.db.core :as db]
            [shade.layout :as layout]))

(defn admin-page [request]
  (let [user-id (get-in request [:session :identity :id])]
    (layout/render request "admin.html" (merge (select-keys request [:active? :admin?])
                                               {:user            (db/get-user {:id user-id})
                                                :macros          (db/list-macros)
                                                :users           (db/list-users)
                                                :sunblock-groups (db/list-sunblock-groups)
                                                :shades          (db/list-shades)}))))
