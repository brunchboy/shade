(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [shade.config :refer [env]]
            [shade.db.core :as db]
            [shade.sun :as sun]
            [shade.weather :as weather]
            [java-time :as jt]
            [ring.adapter.undertow.websocket :as ws]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [mount.core :refer [defstate]]))

(def channel-open
  "Keeps track of the channel associated with the open web socket."
  (atom nil))

(def shade-state
  "Keeps track of the latest information we have about all the shades."
  (atom {}))

(defn throttled?
  "Make sure a request of a particular kind doesn't get stuttered a
  bunch of extra times because the scheduler loop is tickled while
  there are already requests outstanding. Returns truthy if the
  request was throttled and should be discarded."
  ([kind]
   (throttled? kind 800))
  ([kind min-interval-ms]
   (let [now           (System/currentTimeMillis)
         eligible-time (+ now min-interval-ms)
         new-state     (swap! shade-state update-in [:throttle kind]
                          (fn [old-eligible-time]
                            (if (or (not old-eligible-time)
                                    (>= now old-eligible-time))
                              eligible-time
                              old-eligible-time)))]
     (not= eligible-time (get-in new-state [:throttle kind])))))

(defn tickle-state-updater
  "Causes the state updater to immediately check for shades that need
  updates."
  []
  (when-let [tickle-chan (:tickle @shade-state)]
    (async/>!! tickle-chan true)))

(defn on-open
  "Called when a connection to the web socket is opened."
  [{:keys [channel]}]
  (log/info "Web socket opened!")
  (swap! channel-open
         (fn [old-channel]
           (when old-channel
             (future
               (try
                 (.sendClose old-channel)
                 (catch Exception _))
               (.close old-channel)))
           channel))
  (tickle-state-updater))

(defn shades-adjusted
  "Given a list of shades that have been told to move, marks them as
  moving in the state map and tells the state updater to run
  immediately."
  [shades]
  (doseq [shade shades]
    (swap! shade-state assoc-in [:shades (:id shade) :moving?] true))
  (tickle-state-updater))

(defn- gather-director-vars
  "Given a list of director variable values, transforms them into a map
  keyed by item ID."
  [var-list]
  (reduce (fn [acc v]
            (assoc-in acc [(get v "id") (get v "varName")] (get v "value")))
          {}
          var-list))

(defn on-message
  "Called when a message is received from the web socket."
  [{:keys [data]}]
  #_(println "Received message, data:" data)
  (let [{:keys [action] :as message} (edn/read-string data)]
    (case action
      :positions
      (future
        (log/info "Received updated blind positions.")
        (doseq [[k v] (gather-director-vars (:positions message))]
          (when-let [shade (db/get-shade-by-controller-id {:id k})]
            (swap! shade-state update-in [:shades (:id shade)]
                   merge {:moving?       (zero? (get v "Stopped"))
                          :level         (get v "Level")
                          :target-level  (get v "Target Level")})))
        (swap! shade-state assoc :last-update (System/currentTimeMillis)))

      :batteries
      (future
        (log/info "Received updated battery levels.")
        (let [vars (gather-director-vars (:batteries message))]
          (doseq [shade (db/list-shades)]
                (if-let [level (get-in vars [(:parent_id shade) "Battery Level"])]
                  (swap! shade-state assoc-in [:shades (:id shade) :battery-level] level)
                  (log/error "Could not find battery level for shade with parent ID" (:parent_id shade))))
              (swap! shade-state assoc :last-battery-update (System/currentTimeMillis))))

      :set-levels
      (log/info "Received acknowledgement of set-levels command.")

      (log/error "Received unrecognized action:" action))))

(defn on-close
  "Called when the web socket is closed."
  [{:keys [ws-channel]}]
  (log/warn "Web socket closed!")
  (swap! channel-open
         (fn [old-channel]
           (when (= old-channel ws-channel)
             (try
               (.close old-channel)
               (catch Exception e
                 (log/error {:what :exception-closing
                             :exception e
                             :where "Problem closing web socket after close notification"}))))
           nil)))

(defn on-error
  "Called when there is an error."
  [{:keys [_channel error]}]
  (log/error {:what :socket-error
              :where (str "Received web socket error: " error)})
  (swap! channel-open
         (fn [old-channel]
           (when old-channel
             (try
               (.close old-channel)
               (catch Exception e
                 (log/error {:what :exception-closing
                             :exception e
                             :where "Problem closing web socket after error"}))))
           nil)))

(defn handler
  "The web socket handler."
  [_request]
  {:undertow/websocket
   {:on-open    on-open
    :on-message on-message
    :on-close   on-close
    :on-error   on-error}})

(defn websocket-routes []
  [["/ws" handler]])

(defn narrow-macro-level
  "Translates a macro shade level, which ranges from 0 to 100, to the
  potentially more limited range required by the calibration
  correction associated with the shade, if any."
  [{:keys [level close_min open_max]}]
  (let [range (- open_max close_min)]
    (+ close_min (Math/round (double (* range (/ level 100)))))))

(defn run-macro
  "Loads the entries available to the specified user of the specified
  macro, and sends instructions to configure the blinds accordingly."
  [macro-id user-id]
  (let [entries (db/get-macro-entries {:macro macro-id
                                       :user  user-id})]
    (when-let [ch @channel-open]
      (ws/send (str {:action :set-levels
                     :blinds (mapv (fn [entry]
                                     {:id    (:controller_id entry)
                                      :level (narrow-macro-level entry)})
                                   entries)})
               ch)
      (doseq [entry entries]
        (swap! shade-state update-in [:shades (:shade entry)]
               (fn [shade]
                 (assoc shade :moving? (not= (:level entry) (:level shade))))))
      (tickle-state-updater))))

;; TODO: Also add macro-room entries for individual rooms which are in
;; the right position for the macro.
(defn macros-in-effect
  "Loads the entries available to the specified user for each specified
  macro and checks whether the blinds are currently at the level
  desired. Returns the list of macros with an additional `:in-effect`
  attribute indicating whether that macro would do nothing if run by
  that user right now."
  [macros user-id]
  (let [state (:shades @shade-state)]
    (mapv (fn [macro]
            (let [entries (db/get-macro-entries {:macro (:id macro)
                                                 :user  user-id})]
              (assoc macro :in-effect (every? #(= (narrow-macro-level %)
                                                  (get-in state [(:shade %) :level])) entries))))
          macros)))

(defn expand-shade-level
  "Translates an actual shade level, which is in a potentially limited
  range required by the calibration correction associated with the
  shade, to the full 0-100 range."
  [{:keys [level close_min open_max]}]
  (let [range (- open_max close_min)]
    (Math/round (* 100.0 (/ (- level close_min) range)))))

(defn- include-level
  "Takes a shade bounds entry being reported for a room, and inserts the
  current level of that shade into it, expanding it back to the
  logical range where 0 is fully closed and 100 is fully open."
  [shade-info]
  (let [state   (:shades @shade-state)
        leveled (assoc shade-info :level (get-in state [(:shade_id shade-info) :level] (:close_min shade-info)))]
    (-> shade-info
        (assoc :level (expand-shade-level leveled))
        (dissoc :close_min :open_max))))

(defn- group-shades
  "Transforms the shade photo boundaries rows so that shades which share
  the same boundaries are grouped into a single entry."
  [bounds]
  (reduce (fn [acc v]
            (let [shade-info (select-keys v [:kind :close_min :open_max :controller_id :shade_id])
                  base       (or (get acc (:id v))
                                 (assoc (apply dissoc v :id (keys shade-info))
                                        :shades {}))]
              (assoc acc (:id v) (update base :shades assoc (:kind shade-info)
                                         (dissoc (include-level shade-info) :kind)))))
          {}
          bounds))

(defn interpolate
  "Given a starting and ending point, and a level from 0 to 100,
  interpolates between the two points."
  [start end percentage]
  (let [range (- end start)]
    (Math/round (+ start (/ (* range (- 100 percentage)) 100.0)))))

(defn- clip
  "Given a shade boundary map produced by `shades-visible`, and a top
  and bottom level expressed as percentages, returns the boundaries
  clipped to include the specified section only."
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

(defn- regions-to-draw
  "Given a shade boundary map produced by `shades-visible`, returns a
  list of image types and clipping regions that need to be drawn to
  show the cooresponding current shade state for that pair of shades."
  [{:keys [shades] :as boundaries}]
  (let [levels (set (map :level (vals shades)))]
    (if (= 1 (count levels))
      ;; Both blinds in this pair are at the same level, we have at most one region to draw.
      (let [level (first levels)]
        (when (< level 100) ;; We only have to draw something if the blinds aren't fully open.
          [(merge {:image "both"}
                  (clip boundaries 100 level))]))
      ;; Blinds are at different levels
      (let [[bottom-shade top-shade] (sort-by (fn [entry] (get-in entry [1 :level])) shades)
            top-level                (get-in top-shade [1 :level])
            bottom-level             (get-in bottom-shade [1 :level])]
        (concat
         (when (< top-level 100)  ; The top shade is visible
           [(merge {:image "both"}
                   (clip boundaries 100 top-level))])
         [(merge {:image (if (= (first bottom-shade) "blackout") "blackout" "privacy")}
                 (clip boundaries top-level bottom-level))])))))

(defn base-image
  "Calculates the best starting image on which to draw the positions of
  the blinds."
  [grouped-shades]
  ;; TODO: if all closed use "both", otherwise if all blackout closed
  ;; use "blackout", otherwise if all screens closed use "privacy",
  ;; otherwise use "open".
  ;;
  ;; TODO: Get image dimensions from room row in database (also use in room.html).
  (let [image "open"]
    [{:image          image
      :top_left_y     0
      :bottom_left_y  3024
      :top_right_y    0
      :bottom_right_y 3024
      :bottom_left_x  0
      :top_left_x     0
      :bottom_right_x 4032
      :top_right_x    4032}]))

(defn shades-visible
  "Sends a list of image region updates required to make a room photo
  accurately reflect the current state of the shades, as long as the
  specified user has access to the specified room."
  [room-id user-id]
  (let [valid-rooms    (->> (db/list-rooms-for-user {:user user-id})
                         (map :id)
                         set)
        grouped-shades (->> (db/get-room-photo-boundaries {:room room-id})
                            group-shades
                            vals)]
    (when (valid-rooms room-id)
      (concat (base-image grouped-shades)
              (mapcat regions-to-draw grouped-shades)))))


(def moving-interval
  "How often to check the blind positions if any are believed to be
  moving, in milliseconds."
  (jt/as (jt/duration 1 :seconds) :millis))

(def stopped-interval
  "How often to check the blind positions if none are believed to be
  moving, in milliseconds."
  (jt/as (jt/duration 30 :seconds) :millis))

(def battery-update-interval
  "How often to check the battery levels, in milliseconds."
  (jt/as (jt/duration 1 :days) :millis))

(defn- request-position-update
  "Requests the current blind positions on a separate thread if the web
  socket is open. Also, if it's been long enough since we last checked
  the battery levels, check them again."
  []
  (future
    (try
      (when-let [ch @channel-open]
        (when-not (throttled? :position-update)
          (log/info "Requesting blind position update.")
          (ws/send (str {:action :positions}) ch)
          (let [last-update (:last-battery-update @shade-state)]
            (when (or (not last-update)
                      (> (- (System/currentTimeMillis) last-update) battery-update-interval))
              (log/info "Requesting battery level update.")
              (ws/send (str {:action :batteries}) ch)))))
      (catch Throwable t
        (log/error t "Problem requesting blind information.")))))

(defn same-day?
  "Checks whether the specified event last ran today (in the time zone of the blinds)."
  [event]
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        event-date     (jt/local-date (jt/with-zone-same-instant (.atZone (:happened event) (jt/zone-id "UTC"))
                                        local-timezone))
        today          (jt/local-date (jt/instant) local-timezone)]
    (= event-date today)))

(defn sunrise-protect
  "If we have just reached astronomical dawn, close the blackout
  curtains in all rooms marked for sunrise protection."
  [sun-position]
  (let [last-run (db/find-event {:name "sunrise-protect"})
        ch       @channel-open]
    (when-not (and last-run (same-day? last-run))      ; Has not already run today.
      (when (and (> (:elevation sun-position) sun/astronomical-dawn-elevation)    ; It's past astronomical dawn.
                 ch)  ; And we have a connection to the blind interface.
        (log/info "Running sunrise-protect.")
        (ws/send (str {:action :set-levels
                       :blinds (mapv (fn [shade]
                                       {:id    (:controller_id shade)
                                        :level (:close_min shade)})
                                     (db/list-shades-for-sunrise-protect))})
                 ch)
        (db/save-event {:name "sunrise-protect"})
        (tickle-state-updater)))))

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
                  (< (:temperature weather) sunblock-temperature-threshold)))  ; It was recently enough cold enough.
           (when-let [forecast (weather/forecast-for-today)]
             (< (:high forecast) sunblock-temperature-threshold)))))  ; The forecast high for the day is cold enough.

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

(defn sunblock-groups
  "Check to see if the sun has first entered any sunblock groups today,
  and it is warm enough we want to block the sun for reasons of
  temperature, in which case those blinds should be closed. or first
  exited any which were entered earlier today."
  [sun-position]
  (let [ch    @channel-open
        warm  (warm-enough?)
        clear (not-overcast-enough?)]
    (doseq [group (db/list-sunblock-groups)]
      (let [last-opened (db/find-event {:name "sunblock-group-entered" :related-id (:id group)})
            shining?    (sun/entering-windows? sun-position group)]
        (if-not (and last-opened (same-day? last-opened))
          ;; This group has not yet run today, time to close?
          (when (and shining?  ; The sun is shining through this group,
                     warm      ; the weather merits blocking the sun to keep the home cool,
                     clear     ; some sun may be getting through cloud layers,
                     ch)       ; and we have a connection to the blind interface.
            (log/info "Closing blinds for sunblock group" (:name group))
            (ws/send (str {:action :set-levels
                           :blinds (mapv (fn [shade]
                                           {:id    (:controller_id shade)
                                            :level (:close_min shade)})
                                         (db/get-sunblock-group-entries {:sunblock_group (:id group)}))})
                     ch)
            (db/save-event {:name "sunblock-group-entered" :related-id (:id group)})
            (tickle-state-updater))

          ;; This group has run today, is it time to open back up?
          (let [last-closed (db/find-event {:name "sunblock-group-exited" :related-id (:id group)})]
            (when (and (not shining?)  ; Sun is no longer shining through this group.
                       (not (and last-closed (same-day? last-closed)))  ; We have not yet closed it.
                       ch)             ; And we have a connection to the blind interface.
              (log/info "Reopening blinds for sunblock group" (:name group))
              (ws/send (str {:action :set-levels
                             :blinds (mapv (fn [shade]
                                             {:id    (:controller_id shade)
                                              :level (:open_max shade)})
                                           (db/get-sunblock-group-entries {:sunblock_group (:id group)}))})
                       ch)
              (db/save-event {:name "sunblock-group-exited" :related-id (:id group)}))))))))

(defn run-needed-events
  "Determine which events need running now, and run them."
  []
  (future
    (when-not (throttled? :run-needed-events 20000)
      (let [sun-position (sun/position (jt/zoned-date-time)
                                       (get-in env [:location :latitude]) (get-in env [:location :longitude]))]
        (try
          (sunrise-protect sun-position)
          (sunblock-groups sun-position)
          (catch Throwable t
            (log/error t "Problem in run-needed-events")))))))

(defn- next-wait
  "Calculate how long to wait for our next blind update; it will be much
  shorter if any blinds were last known to be moving."
  []
  (if (some :moving? (vals (:shades @shade-state))) moving-interval stopped-interval))

(defn start-state-updater
  "Starts the async loop which keeps tabs on the current shade
  positions."
  []
  (swap! shade-state
         (fn [old-state]
           (if-not (:shutdown old-state)
             (let [shutdown-chan (async/promise-chan)
                   tickle-chan   (async/chan 1)]
               (async/go
                 (try
                   (async/<! (async/timeout 200))  ; Wait for atom to be initialized.
                   (request-position-update)
                   (weather/update-when-due)
                   (loop [[_v c] (async/alts! [shutdown-chan tickle-chan (async/timeout (next-wait))] {:priority true})]
                     (when (and (not= c shutdown-chan) (:shutdown @shade-state))
                       (request-position-update)
                       (weather/update-when-due)
                       (run-needed-events)
                       (recur (async/alts! [shutdown-chan tickle-chan (async/timeout (next-wait))] {:priority true}))))
                   (catch Throwable t
                     (log/error t "Problem in state-updater go loop")))
                 (reset! shade-state {}))  ; We have been shut down.
               {:shutdown shutdown-chan
                :tickle   tickle-chan
                :shades   {}})
             old-state))))  ; We were already running, do nothing.

(defn stop-state-updater
  "Stops the async loop which keeps tabs on the current shade
  positions."
  [_runner]
  (when-let [shutdown-chan (:shutdown @shade-state)]
    (async/>!! shutdown-chan true)))


(defstate runner
  :start (start-state-updater)
  :stop (stop-state-updater runner))
