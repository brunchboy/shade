(ns shade.routes.websocket-control4
  "Handles communication with the web socket that relayes queries and
  commands to the Control4 blind controller running on our home
  network."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [java-time :as jt]
            [mount.core :refer [defstate]]
            [ring.adapter.undertow.websocket :as ws]
            [shade.db.core :as db]
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
        (try
          (log/info "Received updated blind positions.")
          (doseq [[k v] (gather-director-vars (:positions message))]
            (try
              (when-let [shade (db/get-shade-by-controller-id {:id k})]
                (if (number? (get v "Level"))
                  (swap! shade-state update-in [:shades (:id shade)]
                         merge {:moving?      (zero? (get v "Stopped"))
                                :level        (get v "Level")
                                :target-level (get v "Target Level")})
                  (log/error "Received malformed variables for blind" (:name shade) "so ignoring positon update:" v)))
              (catch Throwable t
                (log/error t "Problem processing blind position update; controller ID:" k "vars:" v))))
          (swap! shade-state assoc :last-update (System/currentTimeMillis))
          (catch Throwable t
            (log/error t "Problem processing blind position update."))))

      :batteries
      (future
        (try
          (log/info "Received updated battery levels.")
          (let [vars (gather-director-vars (:batteries message))]
            (doseq [shade (db/list-shades)]
              (when (nil? (:home_assistant_entity shade))  ; Ignore ones controlled by Home Assistant
                (if-let [level (get-in vars [(:parent_id shade) "Battery Level"])]
                  (swap! shade-state assoc-in [:shades (:id shade) :battery-level] level)
                  (log/error "Could not find battery level for shade with parent ID" (:parent_id shade)))))
            (swap! shade-state assoc :last-battery-update (System/currentTimeMillis)))
          (let [levels    (->> (:shades @shade-state) vals (map :battery-level) (filter identity) (remove neg?))
                min-level (apply min (conj levels 100.0))]  ; Don't crash if no levels yet known.
            (when (< min-level 5.0)
              (util/send-ifttt-notification (format "Lowest battery level: %.1f%%" (double min-level)))))
          (catch Throwable t
            (log/error t "Problem processing battery level update."))))

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
  (let [entries (->> (db/get-macro-entries {:macro macro-id
                                            :user  user-id})
                     (remove :home_assistant_entity))
        in-room (cond->> entries
                  room-id
                  (filter #(= (:room %) room-id)))]
    (when-let [ch @channel-open]
      (when (seq entries)
        (db/remove-from-active-sunblock {:ids (mapv :shade in-room)})
        (ws/send (str {:action :set-levels
                       :blinds (mapv (fn [entry]
                                       {:id    (:controller_id entry)
                                        :level (util/narrow-macro-level entry)})
                                     in-room)})
                 ch)
        (doseq [entry entries]
          (swap! shade-state update-in [:shades (:shade entry)]
                 (fn [shade]
                   (assoc shade :moving? (not= (util/narrow-macro-level entry) (:level shade))))))
        (tickle-state-updater)))))

(defn move-shades
  "Sets the shades mentioned in a preview request to the desired
  levels. Also handles taps to move shades on the room images."
  [preview]
  (when-let [ch @channel-open]
    (when-not (empty? preview)
      (let [ids    (map (fn [k] (java.util.UUID/fromString (name k))) (keys preview))
            shades (remove :home_assistant_entity (db/get-shades {:ids ids}))]
        (db/remove-from-active-sunblock {:ids ids})
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
            (let [entries (->> (db/get-macro-entries {:macro (:id macro)
                                                      :user  user-id})
                               (remove :home_assistant_entity))]
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
         (remove :home_assistant_entity (db/list-shades)))))

(defn current-shade-states
  "Return the current information we have about shades we manage."
  []
  (:shades @shade-state))


(defn run-sunrise-protect
  "We have just reached astronomical dawn, close the blackout
  curtains in all rooms marked for sunrise protection."
  []
  (when-let [ch @channel-open]  ; We have a connection to the blind interface.
    (log/info "Running sunrise-protect.")
    (let [shades (remove :home_assistant_entity (db/list-shades-for-sunrise-protect))]
      (when (seq shades)
        (ws/send (str {:action :set-levels
                       :blinds (mapv (fn [shade]
                                       {:id    (:controller_id shade)
                                        :level (:close_min shade)})
                                     shades)})
                 ch)
        (tickle-state-updater)))))

(defn close-unobstructed-shade-set
  "Helper function to close a set of unobstructed shades during the
  processing of a sunblock group. Takes the list of unobstructed shade
  records, a snapshot of the current shade state, and the channel used
  to communicate with the blind controller daemon."
  [all-unobstructed]
  (let [unobstructed (remove :home_assistant_entity all-unobstructed)
        state        @shade-state
        ch           @channel-open]
    (when (and ch (seq unobstructed))
      ;; Save the starting positions of unobstructed shades so we can restore them when sunblock ends.
      (doseq [shade unobstructed]
        (let [level (get-in state [:shades (:id shade) :level])]
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
      (tickle-state-updater))))

(defn record-obstruction-results
  "Helper function to record all shades that have been delayed in
  closing by obstructions, and those that are now closed."
  [all-shades]
  (let [shades (remove :home_assistant_entity all-shades)
        state  @shade-state]
    (when @channel-open
      (doseq [shade shades]
        (let [current-level   (or (get-in state [:shades (:id shade) :level]) 0)
              already-closed? (= current-level (:close_min shade))]
          (db/set-shade-sunblock-state! {:id    (:id shade)
                                         :state (cond
                                                  already-closed?       "independent"
                                                  (:obstructions shade) "delayed"
                                                  :else                 "closed")}))))))

(defn reopen-shades-in-sunblock-set
  "Helper function to reopen shades when a sunblock event ends."
  [all-shades]
  (let [shades (remove :home-assistant-entity all-shades)
        state  @shade-state
        ch     @channel-open]
    (when ch
      (ws/send (str {:action :set-levels
                     :blinds (mapv (fn [shade]
                                     {:id    (:controller_id shade)
                                      :level (max (or (:sunblock_restore shade) (:open_max shade))
                                                  (or (get-in state [:shades (:id shade) :level]) 0))})
                                   shades)})
               ch)
      (tickle-state-updater))))


;;;; The state watcher daemon.

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

(defn force-battery-update
  "Cause a battery update to occur immediately, useful when replacing
  batteries to verify the results."
  []
  (future
    (try
      (when-let [ch @channel-open]
        (log/info "Requesting extra battery level update by instruction.")
        (ws/send (str {:action :batteries}) ch))
      (catch Throwable t
        (log/error t "Problem requesting battery level update.")))))


(defn send-alarm
  "Raise an alarm through an IFTTT web hook that will send a push
  notification because we have not received a shade update in a
  multiple of our update interval. Records that multiple to suppress
  redundant alarms."
  [multiple]
  (util/send-ifttt-notification  (str "No response from daemon in " multiple " attempts!"))
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
    (alarm-if-no-updates)))

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
                     (log/error t "Problem in Control4 state-updater go loop")))
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
