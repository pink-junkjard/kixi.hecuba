(ns kixi.hecuba.data.entities
  (:require [clojure.edn :as edn]
            [clojure.string :as string]
            [hickory.core :as hickory]
            [qbits.hayt :as hayt]
            [kixi.hecuba.storage.db :as db]
            [cheshire.core :as json]
            [kixi.hecuba.data.devices :as devices]
            [kixi.hecuba.data.profiles :as profiles]
            [kixi.hecuba.api :refer (update-stringified-list)]
            [clojure.tools.logging :as log]
            [hickory.core :as hickory]
            [clojure.tools.logging :as log]
            [kixi.hecuba.schema-utils :as su]
            [schema.core :as s]
            [kixi.hecuba.data.profiles :as profiles]))

(defn delete [entity_id session]
  (let [devices              (devices/get-devices session entity_id)
        device_ids           (map :device_id devices)
        delete-measurements? true
        deleted-devices      (doall (map #(devices/delete % delete-measurements? session) device_ids))
        deleted-entity       (db/execute
                              session
                              (hayt/delete :entities
                                           (hayt/where [[= :id entity_id]])))]
    {:devices deleted-devices
     :entities deleted-entity}))

(defn- encode-tech-icons [property_data]
  (if-let [icons (:technology_icons property_data)]
    (assoc
        property_data
      :technology_icons (->> (hickory/parse-fragment icons)
                             (keep (fn [ti] (-> ti hickory/as-hickory :attrs :src)))
                             (map #(clojure.string/replace % ".jpg" ".png"))))
    property_data))

(defn encode-map-vals [m]
  (into {} (map (fn [[k v]] [k (json/encode v)]) m)))

(defn encode-for-insert [entity]
  (-> entity
      (assoc :id (:entity_id entity))
      (dissoc entity :device_ids :entity_id)
      (cond-> (:notes entity) (update-stringified-list :notes)
              (:metering_point_ids entity) (update-stringified-list :metering_point_ids)
              (:devices entity) (assoc :devices (encode-map-vals (:devices entity)))
              (:property_data entity) (assoc :property_data (json/encode (:property_data entity))))))

(defn encode-for-update [entity]
  (dissoc (encode-for-insert entity) :id))

(defn decode-list [entity key]
  (->> (get entity key [])
       (mapv #(json/parse-string % keyword))
       (assoc entity key)))

(defn decode-map [entity key]
  (->> (get entity key {})
       (map (fn [[k v]] (vector k (json/parse-string v keyword))))
       (into {})
       (assoc entity key)))

(defn decode-edn-map [entity key]
  (->> (get entity key {})
       (map (fn [[k v]] (vector k (edn/read-string v))))
       (into {})
       (assoc entity key)))

(defn decode-entry [entity key]
  (try
    (assoc entity key (json/parse-string (get entity key) keyword))
    (catch Throwable t
      (log/errorf t "Died trying to parse key %s on %s" key entity)
      (throw t))))

(defn split-icon-string [icon-string]
  (if (re-find #"<img" icon-string)
    (->> (hickory/parse-fragment icon-string)
         (map (fn [ti] (-> ti hickory/as-hickory :attrs :src)))
         (keep identity)
         (map #(string/replace % ".jpg" ".png")))
    (->> (string/split icon-string #" ")
         (map #(string/replace % ".jpg" ".png")))))

(defn decode-tech-icons [entity session]
  (let [profiles     (profiles/get-profiles (:entity_id entity) session)
        last_profile (last profiles)
        profile_data (-> last_profile :profile_data)
        ks           [:property_data :technology_icons]]
    (-> (assoc-in entity ks {})
        (assoc-in (conj ks :ventilation_systems) (profiles/has-technology? last_profile :ventilation_systems))
        (assoc-in (conj ks :photovoltaics) (profiles/has-technology? last_profile :photovoltaics))
        (assoc-in (conj ks :solar_thermals) (profiles/has-technology? last_profile :solar_thermals))
        (assoc-in (conj ks :wind_turbines) (profiles/has-technology? last_profile :wind_turbines))
        (assoc-in (conj ks :small_hydros) (profiles/has-technology? last_profile :small_hydros))
        (assoc-in (conj ks :heat_pumps) (profiles/has-technology? last_profile :heat_pumps))
        (assoc-in (conj ks :chps) (profiles/has-technology? last_profile :chps))
        (assoc-in (conj ks :solid_wall_insulation) (profiles/has-walls-technology? last_profile "External"))
        (assoc-in (conj ks :cavity_wall_insulation) (profiles/has-walls-technology? last_profile "Filled cavity")))))

(defn decode-property-data [entity session]
  (-> entity
      (assoc :property_data
        (try
          (json/parse-string (:property_data entity) keyword)
          (catch Throwable t
            (log/errorf "Could not parse property_data %s for entity with id %s" (:property_data entity) (:entity_id entity))
            {})))
      (decode-tech-icons session)))

(defn decode [entity session]
  (when entity
    (-> entity
        (assoc :entity_id (:id entity))
        (dissoc :id)
        (cond-> (:property_data entity) (decode-property-data session)
                (:notes entity) (decode-list :notes)
                (:documents entity) (decode-list :documents)
                (:photos entity) (decode-list :photos)
                (:devices entity) (decode-edn-map :devices)
                (:metering_point_ids entity) (decode-entry :metering_point_ids)))))

;; See hecuba-schema.cql
(def InsertableEntity
  {:entity_id s/Str
   (s/optional-key :address_country) (s/maybe s/Str)
   (s/optional-key :address_county) (s/maybe s/Str)
   (s/optional-key :address_region) (s/maybe s/Str)
   (s/optional-key :address_street_two) (s/maybe s/Str)
   (s/optional-key :calculated_fields_labels) (s/maybe {s/Str s/Str})
   (s/optional-key :calculated_fields_last_calc) (s/maybe {s/Str s/Str}) ;; sc/ISO-Date-Time
   (s/optional-key :calculated_fields_values) (s/maybe {s/Str s/Str})
   (s/optional-key :devices) {s/Str s/Any}
   (s/optional-key :documents) [s/Str]
   (s/optional-key :metering_point_ids) [s/Str]
   (s/optional-key :name) (s/maybe s/Str)
   (s/optional-key :notes) [s/Str]
   (s/optional-key :photos) [s/Str]
   (s/optional-key :profiles) (s/maybe [(s/maybe {s/Any s/Any})])
   :project_id s/Str
   (s/optional-key :property_code) (s/maybe s/Str)
   (s/optional-key :property_data) {s/Keyword s/Any}
   (s/optional-key :retrofit_completion_date) (s/maybe s/Str) ;; sc/ISO-Date-Time
   :user_id s/Str})

;; FIXME -Here we make a tweak to the photos to allow alia update
;; syntax. We should have a better way to allow that update syntax.
(def UpdateableEntity
  (merge InsertableEntity
         {(s/optional-key :photos) [s/Any]}) ;; TODO - make more restrictive (see above)?
  )

(defn insert [session entity]
  (let [insertable-entity (-> entity (dissoc :profiles) (su/select-keys-by-schema InsertableEntity))
        profiles          (:profiles entity)]
    (s/validate InsertableEntity insertable-entity)
    (mapv #(profiles/insert session %) profiles)
    (db/execute session (hayt/insert :entities (hayt/values (encode-for-insert insertable-entity))))))

(defn update [session id entity]
  (let [insertable-entity (su/select-keys-by-schema entity InsertableEntity)
        _ (s/validate InsertableEntity (assoc insertable-entity :entity_id id))
        updateable-entity (-> insertable-entity
                              (dissoc :entity_id)
                              encode-for-update)
        profiles          (:profiles entity)]
    (mapv #(profiles/update session (:profile_id %) %) profiles)
    (db/execute session (hayt/update :entities
                                     (hayt/set-columns updateable-entity)
                                     (hayt/where [[= :id id]])))))

(defn get-by-id
  ([session entity_id]
     (-> (db/execute session
                     (hayt/select :entities
                                  (hayt/where [[= :id entity_id]])))
         first
         (decode session))))

(defn get-all
  ([session]
     (->> (db/execute session (hayt/select :entities))
          (map #(decode % session))))
  ([session project_id]
     (->> (db/execute session (hayt/select :entities (hayt/where [[= :project_id project_id]])))
          (map #(decode % session)))))

(defn add-image [session id image]
    (db/execute session (hayt/update :entities
                                     (hayt/set-columns {:photos [+ [ (json/encode image)]]})
                                     (hayt/where [[= :id id]]))))

(defn add-document [session id document]
    (db/execute session (hayt/update :entities
                                     (hayt/set-columns {:documents [+ [(json/encode document)]]})
                                     (hayt/where [[= :id id]]))))

(defn delete-document [session id idx]
  (db/execute session (hayt/delete :entities
                                   (hayt/columns {:documents idx})
                                   (hayt/where [[= :id id]]))))

(defn update-document
  "Updates document at given index in a list. Retrieves raw document data and merges it with edited data.
  This avoids overwriting existing information."
  [session id idx edited-data]
  (let [documents    (:documents (first (db/execute session (hayt/select :entities (hayt/where [[= :id id]])))))
        raw-document (when (seq documents) (nth documents idx))
        updated-doc  (merge (json/decode raw-document true) edited-data)]
    (when raw-document
      (db/execute-prepared session (str "UPDATE entities SET documents[" idx "] = '" (json/encode updated-doc) "' WHERE id = '" id "';")))))

(defn has-location? [{:keys [property_data] :as e}]
  (when (and property_data
             (contains? property_data :latitude)
             (contains? property_data :longitude))
    e))

(defn get-entities-having-location [session]
  (let [data
        (->> (db/execute session (hayt/select :entities) (hayt/columns [:id :name :property_data]))
             (map #(decode % session))
             (keep has-location?))]
    data))
