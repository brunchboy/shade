(ns shade.routes.room
  "Supports viewing of rooms and interacting with their shades and
  macros."
  (:require
   [shade.db.core :as db]
   [shade.layout :as layout]
   [shade.routes.websocket :as ws]
   [ring.util.json-response :refer [json-response]])
  (:import
   (java.awt Polygon)
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
                             :user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :room   room
                             :macros (map (partial promote-room-state room-id) in-effect)}))
      (layout/error-page {:status 404 :title "404 - Page not found"}))))

(defn shades-visible [{:keys [path-params session]}]
  (let [user-id (get-in session [:identity :id])
        room-id (UUID/fromString (:room path-params))]
    (json-response (ws/shades-visible room-id user-id))))

(defn point-on-line?
  "Calculates a cross product to determine if a point falls on, below,
  or above a line. Returns zero if the point is on the line, positive
  if it is above it, and zero if below it."
  [line-left-x line-left-y line-right-x line-right-y x y]
  (let [dx (- line-right-x line-left-x)
        dy (- line-right-y line-left-y)
        mx (- x line-left-x)
        my (- y line-left-y)]
    (- (* dx my) (* dy mx))))

(defn find-level
  [x y shade]
  (loop [max-level 100
         min-level 0]
    (let [level (+ min-level (quot (- max-level min-level) 2))]
      (if (or (= level max-level) (= level min-level))
        level  ; We've reached the limit of our resolution.
        (let [line-left-x  (ws/interpolate (:top_left_x shade) (:bottom_left_x shade) level)
              line-left-y  (ws/interpolate (:top_left_y shade) (:bottom_left_y shade) level)
              line-right-x (ws/interpolate (:top_right_x shade) (:bottom_right_x shade) level)
              line-right-y (ws/interpolate (:top_right_y shade) (:bottom_right_y shade) level)
              direction    (point-on-line? line-left-x line-left-y line-right-x line-right-y x y)]
          (if (zero? direction)
            level  ; We hit the point exactly.
            (if (pos? direction)
              (recur level min-level)  ; The current level has the blind too low.
              (recur max-level level))))))))  ; The current level has the blind too high.

(defn level-from-point
  "Given the coordinates of a point within a room image, and a shade
  boundary record, returns the record of the shade which was tapped,
  if any, augmented with the level at which it was tapped."
  [x y shade]
  (let [poly (Polygon.)]
    (.addPoint poly (:top_left_x shade) (:top_left_y shade))
    (.addPoint poly (:top_right_x shade) (:top_right_y shade))
    (.addPoint poly (:bottom_right_x shade) (:bottom_right_y shade))
    (.addPoint poly (:bottom_left_x shade) (:bottom_left_y shade))
    (cond (.contains poly x y)
          (assoc shade :level (find-level x y shade))

          (and (>= x (:top_left_x shade))
               (<= x (:top_right_x shade))
               (<= y (max (:top_left_y shade) (:top_right_y shade))))
          (assoc shade :level 100)  ; Click above shade means open all the way.

          (and (>= x (:bottom_left_x shade))
               (<= x (:bottom_right_x shade))
               (>= y (min (:bottom_left_y shade) (:bottom_right_y shade))))
          (assoc shade :level 0))))  ; Click below shade means close all the way.

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
                        (map (partial level-from-point x y))
                        (filter identity)
                        first)]
        (when hit
          (ws/move-shades {(keyword (str (:shade_id hit))) (:level hit)}))
        (json-response hit)))))
