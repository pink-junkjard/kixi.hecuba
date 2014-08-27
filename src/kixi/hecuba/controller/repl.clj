(ns kixi.hecuba.controller.repl
  "Useful functions for interacting with the pipeline from the repl."
  (:require [kixipipe.scheduler       :as s]
            [kixipipe.pipeline        :refer [submit-item shutdown-pipe]]
            [modular                  :refer (system)]))

(defmacro defreplmethods
  [name & options]
  `(let [options# (apply hash-map '~options)]
     (defn
       ~name
       ([~'type] (submit-item (-> system :pipeline :head)
                    (merge {:type ~'type}
                           options#)))
       ([] (~name nil)))))

(defreplmethods rollups-> :dest :calculated-datasets :type :rollups)
(defreplmethods difference-series-> :dest :calculated-datasets :type :difference-series)
(defreplmethods median-calculation-> :dest :data-quality :type :median-calculation)
(defreplmethods mislabelled-check-> :dest :data-quality :type :mislabelled-sensors)
(defreplmethods median-spike-check-> :dest :data-quality :type :spike-check)
(defreplmethods synthetic-readings-> :dest :calculated-datasets :type :synthetic-readings)
(defreplmethods resolution-> :dest :data-quality :type :resolution)
(defreplmethods convert-to-co2-> :dest :calculated-datasets :type :convert-to-co2)
(defreplmethods convert-to-kwh-> :dest :calculated-datasets :type :convert-to-kwh)
(defreplmethods sensor-status-check-> :dest :data-quality :type :sensor-status)
(defreplmethods actual-annual-calculation-> :dest :calculated-fields :type :actual-annual)
(defreplmethods recalculate-> :dest :recalculate) ;; (recalculate-> :rollups)
