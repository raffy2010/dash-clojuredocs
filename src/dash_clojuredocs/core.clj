(ns dash-clojuredocs.core
  (:gen-class)
  (:require [pegasus.core :refer [crawl]]
            [pegasus.process :refer [PipelineComponentProtocol]]
            [pegasus.dsl :refer :all]
            [clojure.core.async :refer [chan alts! <! >! <!! go-loop go timeout pipeline]])
  (:import (java.io StringReader)))

(defn crawl-clojuredocs
  []
  (let [config (crawl {:seeds ["http://clojuredocs.org/quickref"]
                       :user-agent "dash crawler"
                       :writer (->AppWriter)
                       ;:extractor (defextractors
                                    ;(extract :at-selector [:div.group :dt :a]
                                             ;:follow :href)
                                    ;(extract :at-selector []))
                       :corpus-size 1
                       :job-dir "/tmp/clojuredocs"})]
    (while (not (:stop @(:state config)))
      (Thread/sleep 1000)
      (println (:num-visited @(:state config))))))


(deftype AppExtractor []
  PipelineComponentProtocol

  (initialize
    [this config])

  (run
    [this obj config]
    obj)

  (clean
    [this obj]
    nil))


(deftype AppWriter []
  PipelineComponentProtocol

  (initialize
    [this config
     config])

  (run
    [this obj config]
    (merge obj {}))

  (clean
    [this config]
    nil))

(defn -main
  []
  (crawl-clojuredocs))
