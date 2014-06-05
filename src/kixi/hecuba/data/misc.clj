(ns kixi.hecuba.data.misc
  "Common functions"
  (:require [clj-time.core :as t]
            [clj-time.format :as tf]
            [clj-time.coerce :as tc]
            [kixi.hecuba.storage.db :as db]
            [qbits.hayt :as hayt]
            [clojure.tools.logging :as log]))


(def truthy? #{"true"})

(defn metadata-is-number? [{:keys [metadata] :as m}]
  (truthy? (get metadata "is-number")))

;;;;; Time conversion functions ;;;;;

(defn hourly-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date-hour) (tc/from-date t))))
(defn daily-timestamp [t]
  (tc/to-date (tf/unparse (tf/formatters :date) (tc/from-date t))))

(defn get-year-partition-key [timestamp] (Long/parseLong (format "%4d" (t/year timestamp))))

(defmulti get-month-partition-key type)
(defmethod get-month-partition-key java.util.Date [t]
  (let [timestamp (tc/from-date t)] (Long/parseLong (format "%4d%02d" (t/year timestamp) (t/month timestamp)))))
(defmethod get-month-partition-key org.joda.time.DateTime [t] (Long/parseLong (format "%4d%02d" (t/year t) (t/month t))))

(defmulti truncate-seconds type)
(defmethod truncate-seconds java.util.Date [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH:mm") (tc/from-date t))]
    (tc/to-date  (tf/parse (tf/formatter "yyyy-MM-dd'T'HH:mm") time-str))))
(defmethod truncate-seconds org.joda.time.DateTime [t]
  (let [time-str (tf/unparse (tf/formatter "yyyy-MM-dd'T'HH:mm") t)]
    (tf/parse (tf/formatter "yyyy-MM-dd'T'HH:mm") time-str)))

;;;;; Last check functions ;;;;;

(defn all-sensors
  "Given a querier, retrieves all sensors data joined with their metadata."
  [store]
  (db/with-session [session (:hecuba-session store)]
    (let [all-sensors-metadata (db/execute session (hayt/select :sensor_metadata))]
      (map #(merge (first (db/execute session 
                                      (hayt/select :sensors
                                                   (hayt/where [[= :device_id (:device_id %)] [= :type (:type %)]]))))
                   %) all-sensors-metadata))))

(defn start-end-dates
  "Given a sensor, table and where clause, returns start and end dates for (re)calculations."
  [column sensor where]
  (let [range (-> sensor column)]
    (when-not (empty? range)
      {:start-date (tc/from-date (get range "start")) :end-date (tc/from-date (get range "end"))})))


;;;;; Parsing of measurements ;;;;;

(defn numbers-as-strings? [& strings]
  (every? #(re-find #"^-?\d+(?:\.\d+)?$" %) strings))

(defn parse-double [txt]
  (Double/parseDouble txt))

(defn parse-value
  [m]
  (assoc-in m [:value] (parse-double (:value m))))

(defn sort-measurments
  [m]
  (sort-by :timestamp m) m)

(defn parse-measurements
  "Takes measurements in the format returned from the database.
   Returns a list of maps, with all values parsed approprietly."
  [measurements]
  (map (fn [m] (assoc-in m [:value] (let [value (:value m)] (if-not (empty? value) (read-string value) nil)))) measurements))

(defn find-broken-sensors
  "Finds sensors with bad metadata and label as broken.
  Returns sequence of maps."
  [store]
  (let [where [= :status "Broken"]]
    (db/with-session [session (:hecuba-session store)]
      (db/execute session
                  (hayt/select :sensors (hayt/where where))))))

(defn find-mislabelled-sensors
  "Finds sensots that are marked as mislabelled.
  Returns sequence of maps."
  [store]
  (let [where [= :mislabelled "true"]]
    (db/with-session [session (:hecuba-session store)]
      (db/execute session
                  (hayt/select :sensor_metadata (hayt/where where))))))

(defn reset-date-range
  "Given querier, commander, sensor, column and start/end dates, update these dates in sensor metadata."
  [store {:keys [device_id type period]} col start-date end-date]
  (db/with-session [session (:hecuba-session store)]
    (let [where               [[= :device_id device_id] [= :type type]]
          current-metadata    (first (db/execute session (hayt/select :sensor_metadata (hayt/where where))))
          current-range       (get current-metadata col)
          current-start       (tc/from-date (get current-range "start"))
          current-end         (tc/from-date (get current-range "end"))]
      (when-not (and (t/before? current-start start-date)
                     (t/after? current-end end-date))
        (db/execute session (hayt/update :sensor_metadata (hayt/set-columns {col nil}) (hayt/where where)))))))

;; Datasets helper functions

(defn output-unit-for [t]
  (log/error t)
  (case (.toUpperCase t)
    "VOL2KWH" "kWh"
    "TOTAL-KWH" "kWh"))

(defn output-type-for [t operation]
  (case (.toUpperCase operation)
    "VOL2KWH" (str "converted_" t)
    "KWH2CO2" (str t "_converted_co2")))
