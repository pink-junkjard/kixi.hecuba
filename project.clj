(def jig-version "2.0.0-RC6")

(defproject hecuba "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [liberator "0.10.0"]
                 ;; [com.cemerick/friend "0.2.0"]

                 [prismatic/dommy "0.1.1"]
                 [cljs-ajax "0.2.3"]

                 [jig ~jig-version]
                 [jig/async ~jig-version]
                 [jig/cljs-builder ~jig-version]
                 [jig/http-kit ~jig-version]
                 [jig/bidi ~jig-version]

                 [clj-kafka "0.1.2-0.8"]]

  :source-paths ["src" "src-cljs"]

  :main kixi.hecuba :aot [kixi.hecuba]

  )
