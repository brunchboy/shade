(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [java-time :as jt]
            [mount.core :refer [defstate]]
            [ring.adapter.undertow.websocket :as ws]
            [shade.config :refer [env]]
            [shade.db.core :as db]
            [shade.sun :as sun]
            [shade.util :as util]
            [shade.weather :as weather]))

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
  [{:keys [channel]}]
  (log/warn "Web socket closed!")
  (swap! channel-open
         (fn [old-channel]
           (when (= old-channel channel)
             (try
               (.close old-channel)
               (catch Exception e
                 (log/error {:what :exception-closing
                             :exception e
                             :where "Problem closing web socket after close notification"}))))
           nil)))

(defn on-error
  "Called when there is an error."
  [{:keys [error]}]
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
   {:on-open          on-open
    :on-message       on-message
    :on-close-message on-close
    :on-error         on-error}})

(defn websocket-routes []
  [["/ws" handler]])

(defn run-macro
  "Loads the entries available to the specified user of the specified
  macro, and sends instructions to configure the blinds accordingly.
  If `room-id` is not `nil`, only entries for blinds in that room
  will be used."
  [macro-id user-id room-id]
  (let [entries (db/get-macro-entries {:macro macro-id
                                       :user  user-id})]
    (when-let [ch @channel-open]
      (ws/send (str {:action :set-levels
                     :blinds (mapv (fn [entry]
                                     {:id    (:controller_id entry)
                                      :level (util/narrow-macro-level entry)})
                                   (cond->> entries
                                     room-id
                                     (filter #(= (:room %) room-id))))})
               ch)
      (doseq [entry entries]
        (swap! shade-state update-in [:shades (:shade entry)]
               (fn [shade]
                 (assoc shade :moving? (not= (util/narrow-macro-level entry) (:level shade))))))
      (tickle-state-updater))))

(defn move-shades
  "Sets the shades mentioned in a preview request to the desired
  levels. Also handles taps to move shades on the room images."
  [preview]
  (when-let [ch @channel-open]
    (when-not (empty? preview)
      (let [ids    (map (fn [k] (java.util.UUID/fromString (name k))) (keys preview))
            shades (db/get-shades {:ids ids})]
        (ws/send (str {:action :set-levels
                       :blinds (mapv (fn [shade]
                                       ;; This painful bit is because JS sometimes sends us the values as
                                       ;; strings, and sometimes as Integers, which `Long/valueOf` does not
                                       ;; support.
                                       (let [level   (Long/valueOf (str (get preview (-> shade :id str keyword))))
                                             leveled (assoc shade :level level)]
                                         {:id    (:controller_id shade)
                                          :level (util/narrow-macro-level leveled)}))
                                     shades)})
                 ch)
        (doseq [shade shades]  ; Then do similar shenanigans to let our state updater know the shades are moving.
          (let [level   (Long/valueOf (str (get preview (-> shade :id str keyword))))
                leveled (assoc shade :level level)]
            (swap! shade-state update-in [:shades (:id shade)]
                   (fn [state]
                     (assoc state :moving? true
                            :target-level (util/narrow-macro-level leveled))))))
        (tickle-state-updater)))))

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
              (assoc macro :in-effect (every? #(= (util/narrow-macro-level %)
                                                  (get-in state [(:shade %) :level])) entries)
                     :rooms (util/in-effect-by-room state entries))))
          macros)))

(defn shades-for-macro-editor
  "Returns the list of shades including their current level and battery
  level. If any are mentioned in the supplied list of macro entries,
  adds the macro level to that entry."
  [entries]
  (let [state       (:shades @shade-state)
        entry-index (reduce (fn [acc entry]
                              (assoc acc (:shade entry) entry))
                            {}
                            entries)]
    (map (fn [shade]
           (let [leveled       (assoc shade :level (get-in state [(:id shade) :level] (:close_min shade)))
                 entry         (get entry-index (:id shade))
                 battery-level (get-in state [(:id shade) :battery-level] -1)]
             (merge shade
                    {:level         (util/expand-shade-level leveled)
                     :macro-level   (get entry :level)
                     :battery-level battery-level})))
         (db/list-shades))))

(defn- include-level
  "Takes a shade bounds entry being reported for a room, and inserts the
  current level of that shade into it, expanding it back to the
  logical range where 0 is fully closed and 100 is fully open. It also
  includes a flag that indicates whether the shade is moving, and the
  target level it is moving to."
  [shade-info]
  (let [state    (get-in @shade-state [:shades (:shade_id shade-info)])
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
  (reduce (fn [acc v]
            (let [shade-info (select-keys v [:kind :close_min :open_max :controller_id :shade_id])
                  base       (or (get acc (:id v))
                                 (assoc (apply dissoc v :id (keys shade-info))
                                        :shades {}))]
              (assoc acc (:id v) (update base :shades assoc (:kind shade-info)
                                         (dissoc (include-level shade-info) :kind)))))
          {}
          bounds))

(defn shades-visible
  "Sends a list of image region updates required to make a room photo
  accurately reflect the current state of the shades, as long as the
  specified user has access to the specified room. After the last
  image drawing instruction is emitted, we add instructions to draw
  translucent indicators of the positions to which any moving shades
  are moving."
  [room-id user-id]
  (let [valid-rooms (->> (db/list-rooms-for-user {:user user-id}))
        room        (first (filter #(= (:id %) room-id) valid-rooms) )]
    (when room
      (let [grouped-shades (->> (db/get-room-photo-boundaries {:room room-id})
                                group-shades-and-add-levels
                                vals)
            base           (util/base-image grouped-shades room)]
        (concat [base]
                (mapcat (partial util/regions-to-draw (:image base)) grouped-shades)
                (mapcat util/movement-indicators-to-draw grouped-shades))))))


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

(defn sunrise-protect
  "If we have just reached astronomical dawn, close the blackout
  curtains in all rooms marked for sunrise protection."
  [sun-position]
  (let [last-run (db/find-event {:name "sunrise-protect"})
        ch       @channel-open]
    (when-not (and last-run (util/same-day? last-run))      ; Has not already run today.
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
                  (< (:temperature weather) sunblock-temperature-threshold)))  ; It was recently enough too cold.
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

(defn- close-unobstructed-shade-set
  "Helper function to close a set of unobstructed shades during the
  processing of a sunblock group. Takes the list of unobstructed shade
  records, a snapshot of the current shade state, and the channel used
  to communicate with the blind controller daemon."
  [unobstructed state ch]
  (when (seq unobstructed)
    ;; Save the starting positions of unobstructed shades so we can restore them when sunblock ends.
    (doseq [shade unobstructed]
      (let [level (get-in state [(:id shade) :level])]
        (log/info "Saving sunblock_restore level of shade" (:name shade) "as" level)
        (db/set-shade-sunblock-restore! {:id               (:id shade)
                                         :sunblock_restore level})))
    ;; Close all the unobstructed shades in the sunblock group.
    (ws/send (str {:action :set-levels
                   :blinds (mapv (fn [shade]
                                   {:id    (:controller_id shade)
                                    :level (:close_min shade)})
                                 unobstructed)})
             ch)
    (tickle-state-updater)))

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
  (let [ch    @channel-open
        warm  (warm-enough?)
        clear (not-overcast-enough?)]
    (doseq [group (db/list-sunblock-groups)]
      (let [last-opened (db/find-event {:name "sunblock-group-entered" :related-id (:id group)})
            shining?    (sun/entering-windows? sun-position group)]
        (if-not (and last-opened (util/same-day? last-opened))
          ;; This group has not yet run today, time to close?
          (when (and shining?  ; The sun is shining through this group,
                     warm      ; the weather merits blocking the sun to keep the home cool,
                     clear     ; some sun may be getting through cloud layers,
                     ch)       ; and we have a connection to the blind interface.
            (log/info "Closing blinds for sunblock group" (:name group))
            (let [shades       (->> (db/get-sunblock-group-shades {:sunblock_group (:id group)})
                                    (map (fn [shade] (assoc shade :obstructions (obstructions sun-position shade)))))
                  state        @shade-state
                  unobstructed (remove :obstructions shades)]
              (close-unobstructed-shade-set unobstructed state ch)
              ;; Record the shades that have been delayed in closing by obstructions, and those that are now closed.
              (doseq [shade shades]
                (db/set-shade-sunblock-state! {:id    (:id shade)
                                               :state (if (:obstructions shade) "delayed" "closed")})))
            (db/save-event {:name "sunblock-group-entered" :related-id (:id group)}))

          ;; This group has run today, is it time to open back up?
          (let [last-closed (db/find-event {:name "sunblock-group-exited" :related-id (:id group)})]
            (if (and (not shining?)  ; Sun is no longer shining through this group.
                       (not (and last-closed (util/same-day? last-closed)))  ; We have not yet closed it.
                       ch)             ; And we have a connection to the blind interface.
              (do
                (log/info "Reopening blinds for sunblock group" (:name group))
                (ws/send (str {:action :set-levels
                               :blinds (mapv (fn [shade]
                                               {:id    (:controller_id shade)
                                                :level (or (:sunblock_restore shade) (:open_max shade))})
                                             (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                                     :state          "closed"}))})
                         ch)
                (tickle-state-updater)
                ;; Clear any state and saved positions, we're done.
                (db/clear-sunblock-group-shade-states! {:sunblock_group (:id group)})
                (db/save-event {:name "sunblock-group-exited" :related-id (:id group)}))

              ;; It is not yet time to end this group, but we need to check whether any delayed blinds
              ;; are now due to open, or if any closed blinds can be opened because they will be obstructed
              ;; for the rest of the day.
              (do
                (when (and shining?  ; The sun is shining through this group,
                           warm      ; the weather merits blocking the sun to keep the home cool,
                           clear     ; some sun may be getting through cloud layers,
                           ch)       ; and we have a connection to the blind interface.
                  (let [delayed      (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                             :state          "delayed"})
                        state        @shade-state
                        unobstructed (remove :obstructions delayed)]
                    (when (seq unobstructed)
                      (log/info "Closing newly unobstructed blinds for sublock group (:name group)"))
                    (close-unobstructed-shade-set unobstructed state ch)
                    (doseq [shade unobstructed]
                      (db/set-shade-sunblock-state! {:id    (:id shade)
                                                     :state "closed"}))))
                ;; Finally, look for closed shades that can reopen early for the rest of the day.
                (let [closed (db/get-sunblock-group-shades-in-state {:sunblock_group (:id group)
                                                                     :state          "closed"})]
                  (when (and (seq closed)
                             ch)
                    (let [to-reopen (filter (partial can-reopen? sun-position now group) closed)]
                      (when (seq to-reopen)
                        (log/info "Reopening early blinds for sunblock group" (:name group))
                        (ws/send (str {:action :set-levels
                                       :blinds (mapv (fn [shade]
                                                       {:id    (:controller_id shade)
                                                        :level (or (:sunblock_restore shade) (:open_max shade))})
                                                     to-reopen)})
                                 ch)
                        (tickle-state-updater)
                        (doseq [shade to-reopen]
                          (db/set-shade-sunblock-state! {:id    (:id shade)
                                                         :state "reopened"}))))))))))))))

(defn send-alarm
  "Raise an alarm through an IFTTT web hook that will send a push
  notification because we have not received a shade update in a
  multiple of our update interval. Records that multiple to suppress
  redundant alarms."
  [multiple]
  (if-let [webhook-key (:ifttt-webhook-key env)]
    (let [url (str "https://maker.ifttt.com/trigger/shade_trouble/with/key/" webhook-key)]
      (-> (client/get url)
        :body))
    (log/error "Unable to raise alarm about delayed blind updates, no IFTTT_WEBHOOK__KEY environment variable!"))
  (swap! shade-state assoc :alarm multiple))

(defn alarm-if-no-updates
  "Checks if too long has passed since we received a blinds update, and
  if so, raises an alarm to check on the system state."
  []
  (try
    (let [state   @shade-state
          delayed (quot (- (System/currentTimeMillis) (or (:last-update state) (:started state))) stopped-interval)]
      (cond (< delayed 3)
            (swap! shade-state dissoc :alarm)

            (and (>= delayed 120) (< (:alarm state 0) 120))
            (send-alarm 120)

            (and (>= delayed 3) (< (:alarm state 0) 3))
            (send-alarm 3)))
    (catch Throwable t
      (log/error t "Problem raising alarm about delayed shade updates."))))

(defn run-needed-events
  "Determine which events need running now, and run them."
  []
  (future
    (alarm-if-no-updates)
    (when-not (throttled? :run-needed-events 20000)
      (let [now          (jt/zoned-date-time)
            sun-position (sun/position now
                                       (get-in env [:location :latitude]) (get-in env [:location :longitude]))]
        (try
          (sunrise-protect sun-position)
          (sunblock-groups sun-position now)
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
                :started  (System/currentTimeMillis)
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
