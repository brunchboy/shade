(ns shade.routes.room
  "Supports viewing of rooms and interacting with their shades and
  macros."
  (:require
   [ring.util.json-response :refer [json-response]]
   [shade.config :refer [env]]
   [shade.db.core :as db]
   [shade.home-assistant :as ha]
   [shade.layout :as layout]
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
        in-effect (ha/macros-in-effect macros user-id)]
    (if (and room (some #(= (:id %) room-id) rooms))
      (layout/render request "room.html"
                     (merge (select-keys request [:active? :admin?])
                            {:onload "draw();"
                             :cdn    (env :cdn-url)
                             :user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :room   room
                             :macros (map (partial promote-room-state room-id) in-effect)}))
      (layout/error-page {:status 404 :title "404 - Page not found"}))))


(defn- include-level
  "Takes a shade bounds entry being reported for a room, and inserts the
  current level of that shade into it, expanding it back to the
  logical range where 0 is fully closed and 100 is fully open. It also
  includes a flag that indicates whether the shade is moving, and the
  target level it is moving to."
  [shade-info shade-states]
  (let [state    (get shade-states (:shade_id shade-info))
        leveled  (assoc shade-info :level (:level state (:close_min shade-info)))
        targeted (assoc shade-info :level (:target-level state (:close_min shade-info)))]
    (-> shade-info
        (assoc :level (util/expand-shade-level leveled)
               :target-level (util/expand-shade-level targeted)
               :moving? (:moving? state))
        (dissoc :close_min :open_max))))

(defn- group-shades-and-add-levels
  "Transforms the shade photo boundaries rows so that shades which share
  the same boundaries are grouped into a single entry. In the process
  adds information about the shades' current positions, motion, and
  target positions."
  [bounds]
  (let [shade-states (ha/current-shade-states)]
    (reduce (fn [acc v]
              (let [shade-info (select-keys v [:kind :close_min :open_max :controller_id :shade_id :sunblock_state])
                    base       (or (get acc (:id v))
                                   (assoc (apply dissoc v :id (keys shade-info))
                                          :shades {}))]
                (assoc acc (:id v) (update base :shades assoc (:kind shade-info)
                                           (dissoc (include-level shade-info shade-states) :kind)))))
            {}
            bounds)))

(defn shades-visible-commands
  "Builds a list of image region updates required to make a room photo
  accurately reflect the current state of the shades, as long as the
  specified user has access to the specified room. After the last
  image drawing instruction is emitted, we add instructions to draw
  translucent indicators of the positions to which any moving shades
  are moving. Finally, we add instructions to draw sunblock icons in
  the center of any shades which are participating in sun blocking."
  [room-id user-id]
  (let [valid-rooms (->> (db/list-rooms-for-user {:user user-id}))
        room        (first (filter #(= (:id %) room-id) valid-rooms) )]
    (when room
      (let [boundaries     (db/get-room-photo-boundaries {:room room-id})
            grouped-shades (->> boundaries
                                group-shades-and-add-levels
                                vals)
            base           (util/base-image grouped-shades room)]
        (concat [base]
                (mapcat (partial util/regions-to-draw (:image base)) grouped-shades)
                (mapcat util/movement-indicators-to-draw grouped-shades)
                (mapcat util/sunblock-indicators-to-draw grouped-shades))))))

(defn shades-visible [{:keys [path-params session]}]
  (let [user-id (get-in session [:identity :id])
        room-id (UUID/fromString (:room path-params))]
    (json-response (shades-visible-commands room-id user-id))))

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
          (ha/move-shades {(keyword (str (:shade_id hit))) (:level hit)}))
        (json-response hit)))))
