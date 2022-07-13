(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [shade.db.core :as db]
            [ring.adapter.undertow.websocket :as ws]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [mount.core :refer [defstate]])
  (:import (java.util.concurrent TimeUnit)))

(def channel-open
  "Keeps track of the channel associated with the open web socket."
  (atom nil))

(def shade-state
  "Keeps track of the latest information we have about all the shades."
  (atom {}))

(defn tickle-state-updater
  "Causes the state updater to immediately check for shades that need
  updates."
  []
  (when-let [tickle-chan (:tickle @shade-state)]
    (async/>!! tickle-chan true)))

(defn on-open
  "Called when a connection to the web socket is opened."
  [{:keys [channel]}]
  (println "Web socket opened!")
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
        (println "Received updated blind positions.")
        (doseq [[k v] (gather-director-vars (:positions message))]
          (when-let [shade (db/get-shade-by-controller-id {:id k})]
            (swap! shade-state update-in [:shades (:id shade)]
                   merge {:moving?       (zero? (get v "Stopped"))
                          :level         (get v "Level")
                          :target-level  (get v "Target Level")})))
        (swap! shade-state assoc :last-update (System/currentTimeMillis)))

      :batteries
      (future
        (println "Received updated battery levels.")
        (let [vars (gather-director-vars (:batteries message))]
          (doseq [shade (db/list-shades)]
                (if-let [level (get-in vars [(:parent_id shade) "Battery Level"])]
                  (swap! shade-state assoc-in [:shades (:id shade) :battery-level] level)
                  (println "Could not find battery level for shade with parent ID" (:parent_id shade))))
              (swap! shade-state assoc :last-battery-update (System/currentTimeMillis))))

      :set-levels
      (println "Received acknowledgement of set-levels command.")

      (println "Received unrecognized action:" action))))

(defn on-close
  "Called when the web socket is closed."
  [{:keys [ws-channel]}]
  (println "Web socket closed!")
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
                                      :level (:level entry)})
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
              (assoc macro :in-effect (every? #(= (:level %) (get-in state [(:shade %) :level])) entries))))
          macros)))

(def moving-interval
  "How often to check the blind positions if any are believed to be
  moving, in milliseconds."
  (.toMillis TimeUnit/SECONDS 4))

(def stopped-interval
  "How often to check the blind positions if none are believed to be
  moving, in milliseconds."
  (.toMillis TimeUnit/SECONDS 30))

(def battery-update-interval
  "How often to check the battery levels, in milliseconds."
  (.toMillis TimeUnit/DAYS 1))

(defn- request-position-update
  "Requests the current blind positions on a separate thread if the web
  socket is open. Also, if it's been long enough since we last checked
  the battery levels, check them again."
  []
  (future
    (when-let [ch @channel-open]
      (ws/send (str {:action :positions}) ch)
      (let [last-update (:last-battery-update @shade-state)]
        (when (or (not last-update)
                  (> (- (System/currentTimeMillis) last-update) battery-update-interval))
          (ws/send (str {:action :batteries}) ch))))))

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
           (if (empty? old-state)
             (let [shutdown-chan (async/promise-chan)
                   tickle-chan   (async/chan 1)]
               (async/go
                 (try
                   (async/<! (async/timeout 200))  ; Wait for atom to be initialized.
                   (request-position-update)
                   (loop [[_v c] (async/alts! [shutdown-chan tickle-chan (async/timeout (next-wait))] {:priority true})]
                     (when (not= c shutdown-chan)
                       (request-position-update)
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
