(defproject dash-clojuredocs "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [pegasus "0.7.0"]
                 [org.clojure/core.async "0.3.443"]
                 [com.billpiel/sayid "0.0.15"]]
  :main ^:skip-aot dash-clojuredocs.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
