(ns shade.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init
   (fn []
     (log/info "\n-=[shade started successfully]=-"))
   :stop
   (fn []
     (log/info "\n-=[shade has shut down successfully]=-"))
   :middleware identity})
