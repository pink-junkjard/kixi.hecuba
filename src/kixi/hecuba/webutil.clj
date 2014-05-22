(ns kixi.hecuba.webutil
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cheshire.core :refer (decode decode-stream encode)]
   [cheshire.generate :refer (add-encoder)]
   [hiccup.core :refer (html)]
   [kixi.hecuba.security :as sec]
   [kixi.hecuba.data.misc :as misc]
   [clojure.string :as string]
   [clj-time.coerce :as tc]
   [clj-time.format :as tf]
   [clj-time.core :as t]
   [clj-time.periodic :as tp]
   [clojure.pprint :refer (pprint)]
   [clojure.walk :refer (postwalk)]
   [liberator.core :as liberator]))

(defprotocol Body
  (read-edn-body [body])
  (read-json-body [body]))

(extend-protocol Body
  String
  (read-edn-body [body] (edn/read-string body))
  (read-json-body [body] (decode body keyword))
  org.httpkit.BytesInputStream
  (read-edn-body [body] (io! (edn/read (java.io.PushbackReader. (io/reader body)))))
  (read-json-body [body] (io! (decode-stream (io/reader body) keyword))))

;; TODO - this is not the right place for these - where is?
(add-encoder java.util.UUID
             (fn [c jsonGenerator]
               (.writeString jsonGenerator (str c))))
(add-encoder java.util.Date
             (fn [c jsonGenerator]
               (.writeString jsonGenerator (str c))))
(add-encoder clojure.lang.Keyword
             (fn [c jsonGenerator]
               (.writeString jsonGenerator (name c))))

(defn uuid [] (java.util.UUID/randomUUID))

(def sha1-regex #"[0-9a-z-]+")

(defn stringify-values [m]
  (into {} (for [[k v] m] [k (str v)])))

(defn update-stringified-list [body selector]
  (update-in body [selector] #(when % (map encode %))))

(defn update-stringified-lists [body selectors]
  (reduce update-stringified-list body selectors))

(defn authorized? [querier typ]
  (fn [{{route-params :route-params :as req} :request}]
    (or
     (sec/authorized-with-basic-auth? req querier)
     (sec/authorized-with-cookie? req querier))))

(defmulti decode-body :content-type :default "application/json")

(defmethod decode-body "application/json" [{body :body}] (some-> body read-json-body))
(defmethod decode-body "application/edn" [{body :body}] (some-> body read-edn-body))

(defmulti render-items :content-type :default :unknown)
(defmethod render-items :unknown [_ items]
  ;; If content type is unknown return it to liberator unchanged and
  ;; liberator may render it
  items)

(defmethod render-items "application/html" [_ items]
  (let [fields (remove #{:href :type :parent}
                       (distinct (mapcat keys items)))]
    (let [DEBUG false]
      (html [:body
             [:h2 "Fields"]
             [:ul (for [k fields] [:li (name k)])]
             [:h2 "Items"]
             [:table
              [:thead
               [:tr
                [:th "Name"]
                (for [k fields] [:th (string/replace k "_" " ")])
                (when DEBUG [:th "Debug"])]]
              [:tbody
               (for [p items]
                 [:tr
                  [:td [:a {:href (:href p)} (:name p)]]
                  (for [k fields] [:td (let [d (k p)] (if (coll? d) (apply str (interpose ", " d)) (str d)))])
                  (when DEBUG [:td (pr-str p)])])]]]))))

(defmethod render-items "application/edn" [_ items]
  (pr-str (vec items)))

(defmethod render-items "application/json" [_ items]
  (map encode items))

(defmulti render-item :content-type :default :unknown)
(defmethod render-item :unknown [_ item]
   ;; If content type is unknown return it to liberator unchanged and
  ;; liberator may render it
  item)

(defmethod render-item "application/html" [_ item]
  (html
   [:body
    [:h1 (:name item)]
    [:pre (with-out-str
            (pprint item))]]))

(defmethod render-item "application/edn" [_ item] (pr-str item))

(defmethod render-item "application/json" [_ item] (encode item))

(defn assoc-conj
  "Associate a key with a value in a map. If the key already exists in the map,
  a vector of values is associated with the key."
  [map key val]
  (assoc map key
    (if-let [cur (get map key)]
      (if (vector? cur)
        (conj cur val)
        [cur val])
      val)))

(defn decode-query-params
  [params]
  (reduce
   (fn [m param]
     (if-let [[k v] (string/split param #"=" 2)]
       (assoc-conj m k v)
       m))
   {}
   (string/split params #"&")))

(defn get-month-partition-key
  "Returns integer representation of year and month from java.util.Date"
  [t] (Integer/parseInt (.format (java.text.SimpleDateFormat. "yyyyMM") t)))

(defn get-year-partition-key
  "Returns integer representation of year from java.util.Date"
  [t] (Integer/parseInt (.format (java.text.SimpleDateFormat. "yyyy") t)))

(defn db-timestamp
  "Returns java.util.Date from String timestamp."
  [t] (.parse (java.text.SimpleDateFormat.  "yyyy-MM-dd'T'HH:mm:ss") t))

(defn routes-from [ctx]
  (get-in ctx [:request :modular.bidi/routes]))

(def formatter (tf/formatter (t/default-time-zone) "yyyy-MM-dd'T'HH:mm:ssZ" "yyyy-MM-dd HH:mm:ss"))
(defn to-db-format [date] (tf/parse formatter date))
(defn db-to-iso [s] (let [date (misc/to-timestamp s)] (tf/unparse formatter (tc/from-date date))))

(defn time-range
  "Return a lazy sequence of DateTime's from start to end, incremented
  by 'step' units of time."
  [start end step]
  (let [start-date (t/first-day-of-the-month start)
        end-date   (t/last-day-of-the-month end)
        in-range-inclusive? (complement (fn [t] (t/after? t end-date)))]
    (take-while in-range-inclusive? (tp/periodic-seq start-date step))))

(defn parse-value
  "AMON API specifies that when value is not present, error must be returned and vice versa."
  [measurement]
  (let [value (:value measurement)]
    (if-not (empty? value)
      (-> measurement
          (update-in [:value] read-string)
          (dissoc :error))
      (dissoc measurement :value))))
