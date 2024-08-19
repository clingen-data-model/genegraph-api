(ns genegraph.notebooks
  (:require [genegraph.gene-validity :as gv]
            [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [nextjournal.clerk :as clerk]))



(def db-app-def
  {:type :genegraph-app
   :storage {:gv-tdb
             (assoc gv/gv-tdb
                    :snapshot-handle {:type :file
                                      :base "/Users/tristan/code/genegraph-gene-validity/data/"
                                      :path "gv-tdb-v12.nq.gz"})}})


(comment
  (def db-app (p/init db-app-def))


  (p/start db-app)
  (def db @(get-in db-app [:storage :gv-tdb :instance]))
  (storage/restore-snapshot (get-in db-app [:storage :gv-tdb]))
  (p/stop db-app)
  (+ 1 1)

  )

(comment
  (clerk/serve! {:watch-paths ["notebooks"]})
  (clerk/show! "notebooks/variants_by_moi.clj")
  (clerk/build! {:paths ["notebooks/variants_by_moi.clj"]})
  )
