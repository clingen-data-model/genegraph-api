(ns genegraph.api.embedding
  (:import [dev.langchain4j.model.embedding.onnx.bgesmallen
            BgeSmallEnEmbeddingModel]))

(defn embedding-model []
  (BgeSmallEnEmbeddingModel.))

(defn embed
  "Transform a block of text into a normalized embedding vector (float array)."
  [^BgeSmallEnEmbeddingModel model text]
  (let [e (.content (.embed model text))]
    (.normalize e)
    (.vector e)))

(comment
  (def model (embedding-model))

  (def v (embed model "BRCA1 is a tumor suppressor gene associated with hereditary breast and ovarian cancer."))

  (alength v)   ;; 384 — BGE small produces 384-dimensional vectors
  (take 5 v)

  )
