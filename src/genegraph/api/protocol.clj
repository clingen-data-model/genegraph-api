(ns genegraph.api.protocol)

(defmulti process-base-event #(get-in % [:genegraph.framework.event/data :action]))


