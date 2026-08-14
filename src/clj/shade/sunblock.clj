(ns shade.sunblock
  "Functions related to keeping the sun from shining in windows on warm days."
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :as async]
            [java-time :as jt]
            [mount.core :refer [defstate]]
            [shade.config :refer [env]]
            [shade.db.core :as db]
            [shade.home-assistant :as ha]
            [shade.sun :as sun]
            [shade.util :as util]
            [shade.weather :as weather]))

(def sunblock-state
  "Keeps track of local state information."
  (atom {}))

(defn tickle-state-updater
  "Causes the state updater to immediately check for things to do."
  []
  (when-let [tickle-chan (:tickle @sunblock-state)]
    (async/>!! tickle-chan true)))

(defn sunrise-protect
  "If we have just reached astronomical dawn, close the blackout
  curtains in all rooms marked for sunrise protection."
  [sun-position]
  (let [last-run (db/find-event {:name "sunrise-protect"})]
    (when-not (and last-run (util/same-day? last-run)) ; Has not already run today.
      (when (> (:elevation sun-position) sun/astronomical-dawn-elevation) ; It's past astronomical dawn.
        (log/info "Running sunrise-protect.")
        (ha/run-sunrise-protect)
        (db/save-event {:name "sunrise-protect"})))))


(def sunblock-max-weather-age
  "The interval beyond which a weather condition report becomes too old
  for considering in deciding whether we need sun-blocking."
  (jt/duration 15 :minutes))

(defn recent-enough?
  "Makes sure a weather observation is new enough for us to still
  consider it when deciding whether we need sun-blocking."
  [weather]
  (pos? (.compareTo sunblock-max-weather-age (jt/duration (:time weather) (jt/zoned-date-time)))))

(def sunblock-temperature-threshold
  "The temperature below which we suppress closing of shades for
  thermal sun blocking."
  60.0)

(defn warm-enough?
  "Checks whether our temperature information indicates we should
  implement sun-blocking for temperature control."
  []

  (not (or (when-let [weather (:weather @weather/state)]
             (and (recent-enough? weather)
                  (< (:temperature weather) sunblock-temperature-threshold))) ; It was recently enough too cold.
           (when-let [forecast (weather/forecast-for-today)]
             (< (:high forecast) sunblock-temperature-threshold)))))  ; The forecast high for the day is too cold.

(def sunblock-cloud-cover-threshold
  "The cloud cover percentage above which we suppress closing of shades
  for thermal sun blocking."
  95)

(defn not-overcast-enough?
  "Checks whether our current cloud cover indicates we should not skip
  sun-blocking if the temperature was high enough."
  []
  (when-let [weather (:weather @weather/state)]
    (or (not (recent-enough? weather))
        (when-let [cloud-percentage (:cloud-percentage weather)]
          (<= cloud-percentage sunblock-cloud-cover-threshold)))))

(defn sunblock-obstacles
  "Returns the list of obstacles which can prevent sun shining in through
  a shade that is part of a sunblock group. If any obstacle has an
  `min_azimuth` value that is greater than its `max_azimuth`, it is
  split into two separate obstacles, one from `min_azimuth` to
  `360.0`, and a second from `0.0` to `max_azimuth`."
  [shade]
  (mapcat (fn [obstacle]
            (if (> (:min_azimuth obstacle) (:max_azimuth obstacle))
              [(assoc obstacle :min_azimuth 0)
               (assoc obstacle :max_azimuth 360)]
              [obstacle]))
          (db/get-sunblock-obstacles-for-shade {:shade (:id shade)})))

(defn obstructing?
  "Checks whether an obstacle is currently preventing sunlight from
  entering its shade."
  [sun-position obstacle]
  (and (< (:min_azimuth obstacle) (:azimuth sun-position) (:max_azimuth obstacle))
       (< (:min_elevation obstacle) (:elevation sun-position) (:max_elevation obstacle))))

(defn obstructions
  "Checks whether there are currently any obstacles preventing sunlight
  from entering a shade. Returns either `nil` or the list of such
  obstacles."
  [sun-position shade]
  (seq (filter (partial obstructing? sun-position) (sunblock-obstacles shade))))

(def max-sun-minutes
  "The number of minutes we will allow the sun to shine through a window
  if we reopen it thanks to an obstruction before the sun block group ends."
  5)

(defn can-reopen?
  "Checks whether a shade can be reopened early for the rest of the
  night."
  [sun-position now group shade]
  (when (obstructions sun-position shade)  ; We can consider it because it is now obstructed.
    (loop [now          (jt/adjust now jt/plus (jt/minutes 1))
           sun-position (sun/position now (get-in env [:location :latitude]) (get-in env [:location :longitude]))
           sun-minutes  0]
      (if (sun/entering-windows? sun-position group)  ; Is this sun block group still needed?
        (if (obstructions sun-position shade)  ; Is this shade still obstructed?
          (recur (jt/adjust now jt/plus (jt/minutes 1))
                 (sun/position now (get-in env [:location :latitude]) (get-in env [:location :longitude]))
                 sun-minutes)  ; Keep scanning forward without counting any more sunlight.
          (when (< sun-minutes max-sun-minutes)  ; Not obstructed, fail if we have reached our sun limit.
            (recur (jt/adjust now jt/plus (jt/minutes 1))
                   (sun/position now (get-in env [:location :latitude]) (get-in env [:location :longitude]))
                   (inc sun-minutes))))  ; Keep scanning forward, counting another minute of sunlight.
        true))))  ; We reached the end of the sun block group's timespan without letting in too much sunlight.

#_(defn test-obstacles
  "A test function for working out the obstacle logic."
  []
  (let [now          (java-time/zoned-date-time 2023 4 28 15 30 26 0 "America/Chicago")
        end          (java-time/zoned-date-time 2023 4 28 19 46 55 0 "America/Chicago")
        sun-position (shade.sun/position now (get-in shade.config/env [:location :latitude])
                                         (get-in shade.config/env [:location :longitude]))
        ;; Dayton Street Shades
        shades       (db/get-sunblock-group-shades {:sunblock_group #uuid  "17eb4b54-c974-403c-8cd9-e0700479bc51"})]
    (println "sun:" sun-position)
    (doseq [shade shades]
      (println "Shade:" (:name shade))
      (doseq [obstacle (sunblock-obstacles shade)]
        (when (obstructing? sun-position obstacle)
          (println "  Obstacle:" (:name obstacle))))
      (println))))

(defn sunblock-groups
  "Check to see if the sun has first entered any sunblock groups today,
  and it is warm enough we want to block the sun for reasons of
  temperature, in which case those blinds should be closed. or first
  exited any which were entered earlier today. `now` tracks the zoned
  date time at which the sun's position was calculated, for use in
  looking forward to decide whether do delay closing or advance
  opening individual shades because of obstacles blocking the sun from
  entering their windows."
  [sun-position now]
  (let [warm  (warm-enough?)
        clear (not-overcast-enough?)]
    (doseq [group (db/list-sunblock-groups)]
      (let [last-opened (db/find-event {:name "sunblock-group-entered" :related-id (:id group)})
            shining?    (sun/entering-windows? sun-position group)]
        (if-not (and last-opened (util/same-day? last-opened))
          ;; This group has not yet run today, time to close?
          (when (and shining?  ; The sun is shining through this group,
                     warm      ; the weather merits blocking the sun to keep the home cool,
                     clear)    ; some sun may be getting through cloud layers,
            (log/info "Closing blinds for sunblock group" (:name group))
            (let [shades       (->> (db/get-sunblock-group-shades {:sunblock_group (:id group)})
                                    (map (fn [shade] (assoc shade :obstructions (obstructions sun-position shade)))))
                  unobstructed (remove :obstructions shades)]
              (ha/close-unobstructed-shade-set unobstructed)

              ;; Record the shades that have been delayed in closing by obstructions, and those that are now closed.
              (ha/record-obstruction-results shades))
            (db/save-event {:name "sunblock-group-entered" :related-id (:id group)}))

          ;; This group has run today, is it time to open back up?
          (let [last-closed (db/find-event {:name "sunblock-group-exited" :related-id (:id group)})]
            (if (and (not shining?)  ; Sun is no longer shining through this group.
                       (not (and last-closed (util/same-day? last-closed))))  ; We have not yet closed it.
              (let [shades (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                   :state          "closed"})]
                (log/info "Reopening blinds for sunblock group" (:name group))
                (ha/reopen-shades-in-sunblock-set shades)

                ;; Clear any state and saved positions, we're done.
                (db/clear-sunblock-group-shade-states! {:sunblock_group (:id group)})
                (db/save-event {:name "sunblock-group-exited" :related-id (:id group)}))

              ;; It is not yet time to end this group, but we need to check whether any delayed blinds
              ;; are now due to open, or if any closed blinds can be opened because they will be obstructed
              ;; for the rest of the day.
              (do
                (when (and shining?  ; The sun is shining through this group,
                           warm      ; the weather merits blocking the sun to keep the home cool,
                           clear)    ; some sun may be getting through cloud layers,
                  (let [delayed      (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                             :state          "delayed"})
                        unobstructed (remove :obstructions delayed)]
                    (when (seq unobstructed)
                      (log/info "Closing newly unobstructed blinds for sublock group (:name group)"))
                    (ha/close-unobstructed-shade-set unobstructed)
                    (doseq [shade unobstructed]
                      (db/set-shade-sunblock-state! {:id    (:id shade)
                                                     :state "closed"}))))
                ;; Finally, look for closed shades that can reopen early for the rest of the day.
                (let [closed (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                     :state          "closed"})]
                  (when (seq closed)
                    (let [to-reopen (filter (partial can-reopen? sun-position now group) closed)]
                      (when (seq to-reopen)
                        (log/info "Reopening early blinds for sunblock group" (:name group))
                        (ha/reopen-shades-in-sunblock-set to-reopen)
                        (doseq [shade to-reopen]
                          (db/set-shade-sunblock-state! {:id    (:id shade)
                                                         :state "reopened"}))))))))))))))

(defn run-needed-events
  "Determine which events need running now, and run them."
  []
  (future
    (when-not (util/throttled? sunblock-state :run-needed-events 20000)
      (let [now          (jt/zoned-date-time)
            sun-position (sun/position now
                                       (get-in env [:location :latitude]) (get-in env [:location :longitude]))]
        (try
          (sunrise-protect sun-position)
          (sunblock-groups sun-position now)
          (catch Throwable t
            (log/error t "Problem in run-needed-events")))))))

(def interval
  "How often we should check on weather and sun position."
  (jt/as (jt/duration 1 :minutes) :millis))

(defn start-state-updater
  "Starts the async loop which keeps tabs on the current sun position
  and weather."
  []
  (swap! sunblock-state
         (fn [old-state]
           (if-not (:shutdown old-state)
             (let [shutdown-chan (async/promise-chan)
                   tickle-chan   (async/chan 1)]
               (async/go
                 (try
                   (weather/update-when-due)
                   (loop [[_v c] (async/alts! [shutdown-chan tickle-chan (async/timeout interval)] {:priority true})]
                     (when (and (not= c shutdown-chan) (:shutdown @sunblock-state))
                       (weather/update-when-due)
                       (run-needed-events)
                       (recur (async/alts! [shutdown-chan tickle-chan (async/timeout interval)] {:priority true}))))
                   (catch Throwable t
                     (log/error t "Problem in sunblock state-updater go loop")))
                 (reset! sunblock-state {})) ; We have been shut down.
               {:shutdown shutdown-chan
                :tickle   tickle-chan
                :started  (System/currentTimeMillis)})
             old-state)))  ; We were already running, leave unchanged.
  (tickle-state-updater))

(defn stop-state-updater
  "Stops the async loop which keeps tabs on the current shade
  positions."
  [_runner]
  (when-let [shutdown-chan (:shutdown @sunblock-state)]
    (async/>!! shutdown-chan true)))


(defstate runner
  :start (start-state-updater)
  :stop (stop-state-updater runner))
