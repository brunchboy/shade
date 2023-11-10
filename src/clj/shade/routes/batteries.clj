(ns shade.routes.batteries
  "Supports the viewing of the battery state of the shades."
  (:require [shade.db.core :as db]
            [shade.layout :as layout]
            [shade.routes.websocket :as ws]
            [ring.util.json-response :refer [json-response]]))

(defn refresh-battery-state [{:keys [session]}]
  (let [user (:identity session)]
    (if (:admin user)
      (do (ws/force-battery-update)
          (json-response {:action "Battery update requested"}))
      (layout/error-page {:status 401 :title "401 - Unauthorized"}))))

(defn battery-page [{:keys [session] :as request}]
  (let [user-id (get-in session [:identity :id])
        rooms   (db/list-rooms-for-user {:user user-id})
        shades  (ws/shades-for-macro-editor nil)]
    (layout/render request "admin-batteries.html"
                   (merge (select-keys request [:active? :admin?])
                            {:user    (db/get-user {:id user-id})
                             :rooms   rooms
                             :shades  (sort-by :battery-level shades)}))))
