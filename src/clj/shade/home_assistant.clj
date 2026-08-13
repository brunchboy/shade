(ns shade.home-assistant
  "Communicates with the Home Assistant REST API to learn about and
  manipulate shade states."
  (:require [clj-http.client :as client]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [java-time :as jt]
            [mount.core :refer [defstate]]
            [shade.config :refer [env]]
            [shade.db.core :as db]
            [shade.util :as util]
            [shade.weather :as weather]))

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

(defn ^:deprecated fetch-shade-cover-state
  "Requests current information about a particular shade from Home
  Assistant. No longer needed because we can fetch them all in a
  single request using fetch-shade-cover-states."
  [shade-id]
  (let [url (str (:home-assistant-url env) "states/cover." shade-id "_cover")]
    (-> (client/get url {:oauth-token (:home-assistant-auth env)})
        :body
        (json/read-str))))

(defn ^:deprecated format-shade-cover-attributes
  "Converts the shade cover state returned by Home Assistant to the
  format we use."
  [shade]
  {:moving? (boolean (#{"opening" "closing"} (get shade "state")))
   :level   (get-in shade ["attributes" "current_position"])})

(def fetch-template
  "The JSON template that retrieves all the information we want about shade positions."
  (str/join
   ["{\"template\": "
    "\"{% set data = namespace(states={}) %} "
    "{% for state in states.cover %} "
    "{% set eid = state.entity_id %} "
    "{% set s = {eid: {'state':  states(eid), 'level': state_attr(eid,'current_position')}} %} "
    "{% set data.states = dict(data.states, **s) %} "
    "{% endfor %} "
    "{{ data.states | to_json}}\"}"]))

(defn fetch-shade-cover-states
  "Uses a template to fetch the states of all covers (window shades)
  known to Home Assistant. Returns a map from the entity name we use
  for the shade in our database to the attributes we track for it."
  []
  (let [url (str (:home-assistant-url env) "template")
        raw (-> (client/post url {:oauth-token  (:home-assistant-auth env)
                                  :content-type :json
                                  :body         fetch-template})
                :body
                (json/read-str))]
    (reduce-kv (fn [acc k v]
                 (if-let [[_ entity] (re-matches #"^cover\.(.*)_cover" k)]
                   (assoc acc entity {:moving? (boolean (#{"opening" "closing"} (get v "state")))
                                      :level   (get v "level")})
                   (throw (IllegalStateException. (str "Unexpected cover entity ID: " k)))))
               {}
               raw)))

(defn fetch-shade-battery-state
  "Requests current information about a particular shade's battery from
  Home Assistant."
  [shade-id]
  (let [url (str (:home-assistant-url env) "states/sensor." shade-id "_battery")]
    (-> (client/get url {:oauth-token (:home-assistant-auth env)})
        :body
        (json/read-str))))

(defn format-shade-battery-attributes
  "Converts the shade battery state returned by Home Assistant to the
  format we use."
  [shade]
  {:battery-level (parse-double (get shade "state"))})

(defn set-shade-level
  "Tells Home Assistant to move a shade to the specified level."
  [shade level]
  (let [url (str (:home-assistant-url env) "services/cover/set_cover_position")
        id  (str "cover." (:home_assistant_entity shade) "_cover")]
    (client/post url {:oauth-token  (:home-assistant-auth env)
                      :content-type :json
                      :body         (json/write-str {"entity_id" id
                                                     "position"  level})})))

(defn run-macro
  "Loads the entries available to the specified user of the specified
  macro, and sends instructions to configure the blinds accordingly.
  If `room-id` is not `nil`, only entries for blinds in that room
  will be used."
  [macro-id user-id room-id]
  (let [entries (->> (db/get-macro-entries {:macro macro-id
                                            :user  user-id})
                     (filter :home_assistant_entity))
        in-room (cond->> entries
                  room-id
                  (filter #(= (:room %) room-id)))]
    (when (seq in-room)
      (db/remove-from-active-sunblock {:ids (mapv :shade in-room)})
      (doseq [entry in-room]
        (let [target (util/narrow-macro-level entry)]
          (future
            (try
              (set-shade-level entry target)
              (swap! shade-state update-in [:shades (:shade entry)]
                 (fn [shade]
                   (assoc shade
                          :moving? (not= target (:level shade))
                          :target-level target)))
              (tickle-state-updater)
              (catch Throwable t
                (log/error t "Problem telling Home Assistant to move shade.")))))))))

(defn move-shades
  "Sets the shades mentioned in a preview request to the desired
  levels. Also handles taps to move shades on the room images."
  [preview]
  (when-not (empty? preview)
    (let [ids    (map (fn [k] (java.util.UUID/fromString (name k))) (keys preview))
          shades (filter :home_assistant_entity (db/get-shades {:ids ids}))]
      (db/remove-from-active-sunblock {:ids ids})
      (doseq [shade shades]
        ;; This painful bit is because JS sometimes sends us the values as
        ;; strings, and sometimes as Integers, which `Long/valueOf` does not
        ;; support.
        (let [level   (Long/valueOf (str (get preview (-> shade :id str keyword))))
              leveled (assoc shade :level level)
              target  (util/narrow-macro-level leveled)]
          (future
            (try
              (set-shade-level shade target)
              (swap! shade-state update-in [:shades (:id shade)]
                     (fn [state]
                       (assoc state :moving? true
                              :target-level target)))
              (tickle-state-updater)
              (catch Throwable t
                (log/error t "Problem telling Home Assistant to move shade" shade "to level" target)))))))))

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
                               (filter :home_assistant_entity))]
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
         (filter :home_assistant_entity (db/list-shades)))))

;;;; Sunrise protection and sun block logic

(defn run-sunrise-protect
  "We have just reached astronomical dawn, close the blackout
  curtains in all rooms marked for sunrise protection."
  []
  (log/info "Running sunrise-protect.")
  (doseq [shade (filter :home_assistant_entity (db/list-shades-for-sunrise-protect))]
    (future
      (try
        (let [target (:close_min shade 0)]
          (set-shade-level shade target)
          (swap! shade-state update-in [:shades (:id shade)]
                 (fn [state]
                   (assoc state :moving? true
                          :target-level target))))
        (tickle-state-updater)
        (catch Throwable t
          (log/error t "Problem telling Home Assistant to close shade for sunrise protection."))))))

(defn close-unobstructed-shade-set
  "Helper function to close a set of unobstructed shades during the
  processing of a sunblock group. Takes the list of unobstructed shade
  records, a snapshot of the current shade state, and the channel used
  to communicate with the blind controller daemon."
  [all-unobstructed]
  (let [unobstructed (filter :home_assistant_entity all-unobstructed)
        state        @shade-state]
    (when (seq unobstructed)
      ;; Save the starting positions of unobstructed shades so we can restore them when sunblock ends.
      (doseq [shade unobstructed]
        (let [level (get-in state [:shades (:id shade) :level])]
          (log/info "Saving sunblock_restore level of shade" (:name shade) "as" level)
          (db/set-shade-sunblock-restore! {:id               (:id shade)
                                           :sunblock_restore level})))
      ;; Close all the unobstructed shades in the sunblock group.
      (doseq [shade unobstructed]
        (let [target (:close_min shade)]
          (future
            (try
              (set-shade-level shade target)
              (swap! shade-state update-in [:shades (:id shade)]
               (fn [shade]
                 (assoc shade
                        :moving? (not= target (:level shade))
                        :target-level target)))
              (tickle-state-updater)
              (catch Throwable t
                (log/error t "Problem telling Home Assistant to close shade for sun block.")))))))))

(defn record-obstruction-results
  "Helper function to record all shades that have been delayed in
  closing by obstructions, and those that are now closed."
  [all-shades]
  (let [shades (filter :home_assistant_entity all-shades)
        state  @shade-state]
    (doseq [shade shades]
      (let [current-level   (or (get-in state [:shades (:id shade) :level]) 0)
            already-closed? (= current-level (:close_min shade))]
        (db/set-shade-sunblock-state! {:id    (:id shade)
                                       :state (cond
                                                already-closed?       "independent"
                                                (:obstructions shade) "delayed"
                                                :else                 "closed")})))))

(defn reopen-shades-in-sunblock-set
  "Helper function to reopen shades when a sunblock event ends."
  [all-shades]
  (let [shades (filter :home_assistant_entity all-shades)
        state  @shade-state]
    (doseq [shade shades]
        (let [target (max (or (:sunblock_restore shade) (:open_max shade))
                          (or (get-in state [:shades (:id shade) :level]) 0))]
          (future
            (try
              (set-shade-level shade target)
              (swap! shade-state update-in [:shades (:id shade)]
               (fn [shade]
                 (assoc shade
                        :moving? (not= target (:level shade))
                        :target-level target)))
              (tickle-state-updater)
              (catch Throwable t
                (log/error t "Problem telling Home Assistant to open shade for sun block."))))))))

;;;; Shade position visualization support

(defn current-shade-states
  "Return the current information we have about shades we manage."
  []
  (:shades @shade-state))

;;;; The state watcher daemon.

(def moving-interval
  "How often to check the blind positions if any are believed to be
  moving, in milliseconds."
  (jt/as (jt/duration 2 :seconds) :millis))

(def stopped-interval
  "How often to check the blind positions if none are believed to be
  moving, in milliseconds."
  (jt/as (jt/duration 30 :seconds) :millis))

(def battery-update-interval
  "How often to check the battery levels, in milliseconds."
  (jt/as (jt/duration 1 :days) :millis))

(defn- request-position-update
  "Requests the current blind positions on a separate thread. Also, if
  it's been long enough since we last checked the battery levels,
  check them again, and send an alarm if there is a low battery."
  []
  (future
    (try
      (when-not (throttled? :position-update)
        (log/info "Fetching blind position update from Home Assistant.")
        (doseq [[entity state] (fetch-shade-cover-states)]
          (if-let [shade (db/get-shade-by-home-assistant-entity {:home-assistant-entity entity})]
            (if (number? (:level state))
              (swap! shade-state update-in [:shades (:id shade)] merge state)
              (log/error "Received malformed variables for blind" (:name shade) "so ignoring HA positon update:" state))
            (log/warn "Received unrecognized Home Assistant entity name for position update:" entity)))
        (swap! shade-state assoc :last-update (System/currentTimeMillis))
        (let [bat-update (:last-battery-update @shade-state)]
          (when (or (not bat-update)
                    (> (- (System/currentTimeMillis) bat-update) battery-update-interval))
            (log/info "Requesting battery level updates from Home Assistant.")
            (doseq [shade (db/list-shades)]
              (when-let [entity (:home_assistant_entity shade)]
                (try
                  (let [battery (-> (fetch-shade-battery-state entity) (format-shade-battery-attributes))]
                    (swap! shade-state update-in [:shades (:id shade)] merge battery))
                  (catch Throwable t
                    (log/error t "Problem requesting battery information for" entity "from Home Assistant.")))))
            (swap! shade-state assoc :last-battery-update (System/currentTimeMillis))
            (let [levels    (->> (:shades @shade-state) vals (map :battery-level) (filter identity) (remove neg?))
                  min-level (apply min (conj levels 100.0))]  ; Don't crash if no levels yet known.
            (when (< min-level 5.0)
              (util/send-ifttt-notification (format "Lowest battery level: %.1f%%" (double min-level))))))))
      (catch Throwable t
        (log/error t "Problem requesting blind information from Home Assistant.")))))

(defn force-battery-update
  "Cause a battery update to occur soon, useful when replacing
  batteries to verify the results."
  []
  (swap! shade-state dissoc :last-battery-update))

(defn- next-wait
  "Calculate how long to wait for our next blind update; it will be much
  shorter if any blinds were last known to be moving."
  []
  (if (some :moving? (vals (:shades @shade-state))) moving-interval stopped-interval))

(defn send-alarm
  "Raise an alarm through an IFTTT web hook that will send a push
  notification because we have not received a shade update in a
  multiple of our update interval. Records that multiple to suppress
  redundant alarms."
  [multiple]
  (util/send-ifttt-notification  (str "No successful shade state update in " multiple " attempts!"))
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

            (and (>= delayed 6) (< (:alarm state 0) 6))
            (send-alarm 6)))
    (catch Throwable t
      (log/error t "Problem raising alarm about delayed shade updates."))))

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
                   (async/<! (async/timeout 200)) ; Wait for atom to be initialized.
                   (request-position-update)
                   (weather/update-when-due)
                   (loop [[_v c] (async/alts! [shutdown-chan tickle-chan (async/timeout (next-wait))] {:priority true})]
                     (when (and (not= c shutdown-chan) (:shutdown @shade-state))
                       (request-position-update)
                       (weather/update-when-due)
                       (alarm-if-no-updates)
                       (recur (async/alts! [shutdown-chan tickle-chan (async/timeout (next-wait))] {:priority true}))))
                   (catch Throwable t
                     (log/error t "Problem in Home Assistant state-updater go loop")))
                 (reset! shade-state {})) ; We have been shut down.
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
