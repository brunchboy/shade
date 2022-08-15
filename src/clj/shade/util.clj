(ns shade.util
  "Utility functions."
  (:require [clojure.set :as set]
            [java-time :as jt]
            [shade.config :refer [env]])
  (:import (java.awt Polygon)))

(defn interpolate
  "Given a starting and ending value, and a level from 0 to 100,
  interpolates between the two points."
  [start end percentage]
  (let [range (- end start)]
    (Math/round (+ start (/ (* range (- 100 percentage)) 100.0)))))

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
  "Given the coordinates of a point within a room image which has
  already been found to fall within the supplied a shade boundary
  record, returns the opening level which would cause the bottom of
  the shade to touch that point."
  [x y shade]
  (loop [max-level 100
         min-level 0]
    (let [level (+ min-level (quot (- max-level min-level) 2))]
      (if (or (= level max-level) (= level min-level))
        level  ; We've reached the limit of our resolution.
        (let [line-left-x  (interpolate (:top_left_x shade) (:bottom_left_x shade) level)
              line-left-y  (interpolate (:top_left_y shade) (:bottom_left_y shade) level)
              line-right-x (interpolate (:top_right_x shade) (:bottom_right_x shade) level)
              line-right-y (interpolate (:top_right_y shade) (:bottom_right_y shade) level)
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

(defn clip
  "Given a shade boundary map produced by
  `shade.routes.websocket.shades-visible`, and a top and bottom level
  expressed as percentages, returns the boundaries clipped to include
  the specified section only."
  [{:keys [top_left_x top_left_y top_right_x top_right_y bottom_left_x bottom_left_y bottom_right_x bottom_right_y]}
   top-level bottom-level]
  {:top_left_x (interpolate top_left_x bottom_left_x top-level)
   :bottom_left_x (interpolate top_left_x bottom_left_x bottom-level)
   :top_left_y (interpolate top_left_y bottom_left_y top-level)
   :bottom_left_y (interpolate top_left_y bottom_left_y bottom-level)
   :top_right_x (interpolate top_right_x bottom_right_x top-level)
   :bottom_right_x (interpolate top_right_x bottom_right_x bottom-level)
   :top_right_y (interpolate top_right_y bottom_right_y top-level)
   :bottom_right_y (interpolate top_right_y bottom_right_y bottom-level)})

(defn regions-to-draw
  "Given a shade boundary map produced by
  `shade.routes.websocket.shades-visible`, returns a list of image
  types and clipping regions that need to be drawn to show the
  cooresponding current shade state for that pair of shades.
  `base-name` is the name of the base image being drawn, which lets us
  determine when regions can be omitted."
  [base-name {:keys [shades] :as boundaries}]
  (let [levels (set (map :level (vals shades)))]
    (if (= 1 (count levels))
      ;; Both blinds in this pair are at the same level, we have at most two regions to draw.
      (let [level (first levels)]
        (concat
         (when (and (< level 100)             ; We only have to draw the blinds if they aren't fully open,
                    (not= base-name "both"))  ; and only if the base image doesn't already show them.
           [(merge {:image "both"}
                   (clip boundaries 100 level))])
         (when (and (pos? level)              ; We only have to draw the window if the blinds aren't fully closed,
                    (not= base-name "open"))  ; and only if the base image doesn't already show the window.
           [(merge {:image "open"}
                   (clip boundaries level 0))])))
      ;; Blinds are at different levels, we have up to three regions to draw.
      (let [[bottom-shade top-shade] (sort-by (fn [entry] (get-in entry [1 :level])) shades)
            top-level                (get-in top-shade [1 :level])
            bottom-level             (get-in bottom-shade [1 :level])]
        (concat
         (when (and (< top-level 100)         ; The top shade is visible, so there is a region of both shades,
                    (not= base-name "both"))  ; and the base image doesn't already show them.
           [(merge {:image "both"}
                   (clip boundaries 100 top-level))])
         (let [lower-image (if (= (first bottom-shade) "blackout") "blackout" "privacy")]
           (when (not= base-name lower-image)  ; The lower blind section is different than the base image.
             [(merge {:image lower-image}
                     (clip boundaries top-level bottom-level))]))
         (when (and (pos? bottom-level)       ; We only have to draw the window if lower blind isn't fully closed,
                    (not= base-name "open"))  ; and only if the base image doesn't already show the window.
           [(merge {:image "open"}
                   (clip boundaries bottom-level 0))]))))))

(defn base-image
  "Calculates the best starting image on which to draw the positions of
  the blinds, and emits an instruction to draw it in its entirety."
  [grouped-shades room]
  (let [blackout-levels (set (map #(get-in % [:shades "blackout" :level]) grouped-shades))
        screen-levels (set (map #(get-in % [:shades "screen" :level]) grouped-shades))]
    {:image          (cond
                       (= #{0} (set/union blackout-levels screen-levels))
                       "both"

                       (= #{0} blackout-levels)
                       "blackout"

                       (= #{0} screen-levels)
                       "privacy"

                       :else
                       "open")
     :top_left_y     0
     :bottom_left_y  (:image_height room)
     :top_right_y    0
     :bottom_right_y (:image_height room)
     :bottom_left_x  0
     :top_left_x     0
     :bottom_right_x (:image_width room)
     :top_right_x    (:image_width room)}))

(defn movement-indicators-to-draw
  "Given a shade boundary map produced by
  `shade.routes.websocket.shades-visible`, returns a list of
  movement-indicator regions that need to be drawn to show the
  cooresponding current target positions for that pair of shades, if
  either or both is moving."
  [{:keys [shades] :as boundaries}]
  (->> (map (fn [[kind shade]]
              (when (:moving? shade)
                (merge {:moving kind}
                       (clip boundaries 100 (:target-level shade)))))
            shades)
       (filter identity)))

(defn narrow-macro-level
  "Translates a macro shade level, which ranges from 0 to 100, to the
  potentially more limited range required by the calibration
  correction associated with the shade, if any."
  [{:keys [level close_min open_max]}]
  (let [range (- open_max close_min)]
    (+ close_min (Math/round (double (* range (/ level 100)))))))

(defn in-effect-by-room
  "Given the current shade state and a list of macro entries, builds a
  map whose keys are the room IDs present in the macro entries, and
  whose values true when all blinds in that room for which macro
  entries exist are currently at the level desired."
  [state entries]
  (let [rooms (set (map :room entries))]
    (into {}
          (map (fn [room]
                 [room (every? #(= (narrow-macro-level %)
                                   (get-in state [(:shade %) :level]))
                               (filter #(= (:room %) room) entries))])
               rooms))))

(defn expand-shade-level
  "Translates an actual shade level, which is in a potentially limited
  range required by the calibration correction associated with the
  shade, to the full 0-100 range."
  [{:keys [level close_min open_max]}]
  (let [range (- open_max close_min)]
    (Math/round (* 100.0 (/ (- level close_min) range)))))

(defn same-day?
  "Checks whether the specified event last ran today (in the time zone
  of the shades)."
  [event]
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        event-date     (jt/local-date (jt/with-zone-same-instant (.atZone (:happened event) (jt/zone-id "UTC"))
                                        local-timezone))
        today          (jt/local-date (jt/instant) local-timezone)]
    (= event-date today)))

(defn localize-timestamp
  "Converts a timestamp to a local date and time (if an un-zoned
  Instant, considers it to be in UTC). Accepts either an Instant
  object or a number; if `nil` returns `nil`."
  [timestamp]
  (cond
    (jt/zoned-date-time? timestamp)
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))]
      (jt/local-date-time (jt/with-zone-same-instant timestamp local-timezone)))

    (jt/instant? timestamp)
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))]
      (jt/local-date-time (jt/with-zone-same-instant (.atZone timestamp (jt/zone-id "UTC")) local-timezone)))

    (number? timestamp)
    (localize-timestamp (jt/instant timestamp))))

(defn format-timestamp-relative
  "Formats a timestamp as a string, describing it relative to today if
  it falls within a week."
  [timestamp]
  (if-let [localized (localize-timestamp timestamp)]
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
          date           (jt/local-date localized)
          today          (jt/local-date (jt/instant) local-timezone)
          days           (jt/as (jt/period date today) :days)]
      (str (case days
             0           "Today"
             1           "Yesterday"
             (2 3 4 5 6) (jt/format "EEEE" date)
             (jt/format "YYYY-MM-dd" date))
           (jt/format " HH:mm:ss" localized)))
    "Never"))
