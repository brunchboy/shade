(ns shade.routes.room
  "Supports viewing of rooms and interacting with their shades and
  macros."
  (:require
   [ring.util.json-response :refer [json-response]]
   [shade.config :refer [env]]
   [shade.db.core :as db]
   [shade.layout :as layout]
   [shade.routes.websocket :as ws]
   [shade.util :as util])
  (:import
   (java.util UUID)))

(defn- promote-room-state
  "Modifies a macro entry from the in-effect list so the specified
  room's in-effect state is reflected at the top level, since the
  macro buttons on the room page affect only that room."
  [room-id macro]
  (let [rooms (:rooms macro)]
    (assoc macro :in-effect (get rooms room-id))))

(defn room-page [{:keys [path-params session] :as request}]
  (let [user-id   (get-in session [:identity :id])
        rooms     (db/list-rooms-for-user {:user user-id})
        room-id   (UUID/fromString (:id path-params))
        room      (db/get-room {:id room-id})
        macros    (db/list-macros-enabled-for-user-in-room {:user user-id
                                                            :room room-id})
        in-effect (ws/macros-in-effect macros user-id)]
    (if (and room (some #(= (:id %) room-id) rooms))
      (layout/render request "room.html"
                     (merge (select-keys request [:active?])
                            {:onload "draw();"
                             :cdn    (env :cdn-url)
                             :user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :room   room
                             :macros (map (partial promote-room-state room-id) in-effect)}))
      (layout/error-page {:status 404 :title "404 - Page not found"}))))

(defn shades-visible [{:keys [path-params session]}]
  (let [user-id (get-in session [:identity :id])
        room-id (UUID/fromString (:room path-params))]
    (json-response (ws/shades-visible room-id user-id))))

(defn shade-tapped [{:keys [path-params params session]}]
  (let [user-id     (get-in session [:identity :id])
        room-id     (UUID/fromString (:room path-params))
        x           (Long/valueOf (:x params))
        y           (Long/valueOf (:y params))
        kind        (:kind params "blackout")
        valid-rooms (->> (db/list-rooms-for-user {:user user-id}))
        room        (first (filter #(= (:id %) room-id) valid-rooms))]
    (when room
      (let [shades (db/get-room-photo-boundaries {:room room-id})
            hit    (->> shades
                        (filter #(= (:kind %) kind))
                        (map (partial util/level-from-point x y))
                        (filter identity)
                        first)]
        (when hit
          (ws/move-shades {(keyword (str (:shade_id hit))) (:level hit)}))
        (json-response hit)))))
