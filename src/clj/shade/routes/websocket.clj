(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [shade.db.core :as db]
            [ring.adapter.undertow.websocket :as ws]
            [clojure.tools.logging :as log]))

(def channel-open
  "Keeps track of the channel associated with the open web socket."
  (atom nil))

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
           channel)))

(defn on-message
  "Called when a message is received from the web socket."
  [{:keys [data]}]
  (println "Received message, data:" data))

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
    (ws/send (str {:action :set-levels
                   :blinds (mapv (fn [entry]
                                   {:id    (:controller_id entry)
                                    :level (:level entry)})
                                 entries)})
             @channel-open)))
