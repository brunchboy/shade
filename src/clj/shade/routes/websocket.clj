(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [shade.db.core :as db]
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

(defn on-message
  "Called when a message is received from the web socket."
  [{:keys [data]}]
  (println "Received message, data:" data)
  (let [{:keys [action] :as message} (edn/read-string data)]
    (cond (= action :status)
          (future
            (doseq [[controller-id status] (:blinds message)]
              (let [shade (db/get-shade-by-controller-id {:id controller-id})]
                (swap! shade-state update-in [:shades (:id shade)]
                       merge {:moving?     (not (:stopped status))
                              :level       (:level status)
                              :last-update (System/currentTimeMillis)})))))))

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
  [{:keys [channel error]}]
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
  "How often to check the state of a blind that is believed to be
  moving, in milliseconds."
  5000)

(def stopped-interval
  "How often to check the state of a blind that is believed to be
  moving, in milliseconds."
  120000)

(defn- stale?
  [shade state]
  (let [{:keys [moving? last-update]} (get state (:id shade))]
    (or (not last-update)
        (> (- (System/currentTimeMillis) last-update)
           (if moving? moving-interval stopped-interval)))))

(defn- shades-to-update
  "Checks the latest shade state information and returns a tuple
  containing a list of the shade IDs which need to be updated now, and
  how long we should wait for the next update (if any shades are
  believed to be moving, that will be shorter)."
  []
  (let [all-shades (db/list-shades)
        state      (:shades @shade-state)
        result     (reduce (fn [acc shade]
                             (cond-> acc
                               (stale? shade state)
                               (update :ids conj (:controller_id shade))

                               (get-in state [(:id shade) :moving?])
                               (assoc :any-moving? true)))
                           {:ids        []
                            :any-moving? false}
                           all-shades)]
    [(:ids result) (if (:any-moving? result) moving-interval stopped-interval)]))

(defn request-updates
  "Given a list of shade IDs needing updates, requests them on a
  separate thread."
  [to-update]
  (when (seq to-update)
    (future
      (when-let [ch @channel-open]
        (ws/send (str {:action :status
                       :blinds to-update})
                 ch)))))

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
                   (loop [[to-update next-wait] (async/<! (async/thread (shades-to-update)))
                          timeout-chan (async/timeout next-wait)
                          _ (println "to-update:" to-update next-wait)
                          _ (request-updates to-update)
                          [_v c] (async/alts! [shutdown-chan tickle-chan timeout-chan] {:priority true})]
                     (when (not= c shutdown-chan)
                       (let [[to-update next-wait] (async/<! (async/thread (shades-to-update)))
                             timeout-chan (async/timeout next-wait)]
                         (recur [to-update next-wait]
                                timeout-chan
                                (println "to-update:" to-update next-wait)
                                (request-updates to-update)
                                (async/alts! [shutdown-chan tickle-chan timeout-chan] {:priority true})))))
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
