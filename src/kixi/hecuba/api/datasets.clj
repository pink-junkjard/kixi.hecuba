(ns kixi.hecuba.api.datasets
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [kixi.hecuba.storage.db :as db]
   [kixi.hecuba.security :as sec]
   [kixi.hecuba.webutil :as util]
   [kixi.hecuba.data.misc :as misc]
   [kixi.hecuba.webutil :refer (decode-body authorized? uuid)]
   [liberator.core :refer (defresource)]
   [liberator.representation :refer (ring-response)]
   [kixi.hecuba.storage.sha1 :as sha1]
   [kixi.hecuba.api.devices :as d]
   [kixi.hecuba.web-paths :as p]
   [cheshire.core :as json]
   [kixi.hecuba.data.programmes :as programmes]
   [kixi.hecuba.data.entities :as entities]
   [kixi.hecuba.data.devices :as devices]
   [kixi.hecuba.data.sensors :as sensors]
   [kixi.hecuba.data.datasets :as datasets]
   [kixi.hecuba.data.entities.search :as search]
   [kixi.hecuba.security :refer (has-admin? has-programme-manager? has-project-manager? has-user?) :as sec]
   [clojure.core.match :refer (match)]))

(def ^:private entity-dataset-resource (p/resource-path-string :entity-dataset-resource))

(defn allowed?* [entity-id programme-id project-id allowed-programmes allowed-projects role request-method store]
  (log/infof "allowed?* programme-id: %s project-id: %s allowed-programmes: %s allowed-projects: %s role: %s request-method: %s"
             programme-id project-id allowed-programmes allowed-projects role request-method)
  (db/with-session [session (:hecuba-session store)]
    (let [all-datasets (datasets/get-all session)
          public?      (-> (search/get-by-id entity-id (:search-session store)) :public_access)]
      (match  [(has-admin? role)
               (has-programme-manager? programme-id allowed-programmes)
               (has-project-manager? project-id allowed-projects)
               (has-user? programme-id allowed-programmes project-id allowed-projects)
               request-method]

              [true _ _ _ _]    [true {::items all-datasets}]
              [_ true _ _ _]    [true {::items all-datasets}]
              [_ _ true _ _]    [true {::items all-datasets}]
              [_ _ _ true :get] [true {::items all-datasets}]
              [_ _ _ _ :get]    [true {::items (when public? all-datasets)}]
              :else false))))

(defn sensors-for-dataset
  "Returns all the sensors for the given dataset."
  [{:keys [operands]} store]
  (db/with-session [session (:hecuba-session store)]
    (let [parsed-sensors (map (fn [s] (into [] (next (re-matches #"(\w+)-(\w+)" s)))) operands)
          sensor (fn [[type device_id]]
                   (sensors/get-by-id {:device_id device_id :type type} session))]
      (->> (mapcat sensor parsed-sensors)
           (map #(misc/merge-sensor-metadata store %))))))

(defn synthetic-device [entity_id description]
  (hash-map :description     description
            :parent_id       (str (uuid))
            :entity_id       entity_id
            :location        "{\"name\": \"Synthetic\", \"latitude\": \"0\", \"longitude\": \"0\"}"
            :privacy         "private"
            :metering_point_id (str (uuid))
            :synthetic       true))

(defn synthetic-sensor [operation type device_id unit]
  (let [period (case operation
                 :sum "PULSE"
                 :subtract "PULSE"
                 :divide "INSTANT"
                 :multiply-series-by-field "INSTANT"
                 :divide-series-by-field "INSTANT")]
    {:device_id  device_id
     :type       type
     :unit       unit
     :period     period
     :status     "N/A"
     :synthetic  true}))

(defn synthetic-sensor-metadata [type device_id & [range]]
  (merge {:type      type
          :device_id device_id}
         (when-let [{:keys [start-date end-date]} range]
           {:calculated_datasets {"start" start-date "end" end-date}})))

(defn- entity_id-from [ctx]
  (get-in ctx [:request :route-params :entity_id]))

(defn- name-from [ctx]
  (get-in ctx [:request :route-params :name]))

(defn create-output-sensors [store device_id unit type operation range]
  (db/with-session [session (:hecuba-session store)]
    (let [synthetic (synthetic-sensor operation type device_id unit)]
      (datasets/insert-sensor synthetic (synthetic-sensor-metadata type device_id range) session)

      (when-let [default (d/calculated-sensor synthetic)]
        (datasets/insert-sensor default (synthetic-sensor-metadata (:type default) device_id) session)))))

(defn index-allowed? [store]
  (fn [ctx]
    (let [{:keys [body request-method session params]} (:request ctx)
          {:keys [projects programmes role]} (sec/current-authentication session)
          entity_id (:entity_id params)
          {:keys [project_id programme_id]} (when entity_id
                                              (search/get-by-id entity_id (:search-session store)))]
      (when (and project_id programme_id)
        (allowed?* entity_id programme_id project_id programmes projects role request-method store)))))

(defn index-malformed? [ctx]
  (let [request (:request ctx)
        method  (:request-method request)
        {:keys [route-params request-method]} request
        entity_id (:entity_id route-params)]
    (case method
      :post (let [body (decode-body request)
                  {:keys [operation]} body]
              (if (some #{operation} ["divide" "sum" "subtract" "multiply-series-by-field"
                                      "divide-series-by-field"])
                [false {:dataset body}]
                true))
      :get (if (seq entity_id) false true)
      false)))

(defn all-sensors-exist? [entity body store]
  (let [{:keys [operation operands name]} body
        sensors  (sensors-for-dataset body store)]
    (when (and (seq entity)
               (= (count operands) (count sensors)))
      {::item {:entity_id (:entity_id entity) :sensors sensors :operation operation :operands operands :name name}})))

(defn sensor-and-field-exist? [entity body store]
  (let [{:keys [operation operands name]} body
        sensors  (sensors-for-dataset {:operands (take 1 operands)} store)]
    (when (and (seq entity)
               (pos? (count sensors)))
      {::item {:entity_id (:entity_id entity) :sensors sensors :operation operation :operands operands :name name}})))

(defmulti exists? (fn [entity body store] (:operation body)))

(defmethod exists? :sum [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :divide [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :subtract [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :multiply [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :multiply-series-by-field [entity body store]
  (sensor-and-field-exist? entity body store))

(defmethod exists? :divide-series-by-field [entity body store]
  (sensor-and-field-exist? entity body store))

(defmethod exists? :default [entity body store] false)

(defn index-exists? [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [{:keys [request dataset]} ctx
          {:keys [route-params request-method]} request
          entity_id (:entity_id route-params)
          entity    (search/get-by-id entity_id (:search-session store))
          editable? (:public_access entity)]
      (case request-method
        :post (let [{:keys [operation]} dataset]
                (exists? entity (assoc dataset :operation (keyword (.toLowerCase operation))) store))
        :get (let [items (datasets/get-by-id entity_id session)]
               {::items (mapv #(assoc % :editable editable?) items)})))))

(defn stringify [k] (name k))

(defmulti get-unit (fn [item] (:operation item)))
(defmethod get-unit :sum [item]
  (let [{:keys [sensors]} item]
    (:unit (first sensors))))
(defmethod get-unit :subtract [item]
  (let [{:keys [sensors]} item]
    (:unit (first sensors))))
(defmethod get-unit :divide [item]
  (let [{:keys [sensors]} item]
    (str (:unit (first sensors)) "/" (:unit (last sensors)))))
(defmethod get-unit :multiply-series-by-field [item]
  (let [{:keys [sensors operands]} item
        [field unit] (string/split (last operands) #"-")]
    (str (:unit (first sensors)) "/" unit)))
(defmethod get-unit :divide-series-by-field [item]
  (let [{:keys [sensors operands]} item
        [field unit] (string/split (last operands) #"-")]
    (str (:unit (first sensors)) "/" unit)))

(defn index-post! [store ctx]
   (db/with-session [session (:hecuba-session store)]
     (let [{:keys [sensors operation operands entity_id name] :as item} (::item ctx)
           unit          (get-unit item)
           device        (synthetic-device entity_id "Synthetic")
           device_id     (sha1/gen-key :device (assoc device :description name))
           operation-str (stringify operation)
           range         (when-let [[start end] (misc/range-for-all-sensors sensors)]
                           {:start-date start :end-date end})]
       (devices/insert session entity_id (assoc device :device_id device_id))
       (create-output-sensors store device_id unit name operation range)
       (datasets/insert {:entity_id entity_id
                         :name      name
                         :operands  operands
                         :operation operation-str
                         :device_id device_id} session)
       (-> (search/searchable-entity-by-id entity_id session)
           (search/->elasticsearch (:search-session store)))
       (hash-map ::name name
                 ::entity_id entity_id))))

(defn index-handle-ok [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [items (::items ctx)]
      (util/render-items (:request ctx) items))))

(defn index-handle-created [ctx]
  (let [entity_id   (::entity_id ctx)
        name        (::name ctx)]
    (if (and entity_id name)
      (let [location (format entity-dataset-resource entity_id name)]
        (ring-response {:headers {"Location" location}
                        :body (json/encode {:location location
                                            :status "OK"
                                            :version "4"})}))
      (ring-response {:status 400 :body (json/encode "Provide valid entity_id, sensors and operation.")}))))

(defresource index [store]
  :allowed-methods       #{:get :post}
  :available-media-types #{"application/edn" "application/json"}
  :known-content-type?   #{"application/edn" "application/json"}
  :authorized?           (authorized? store)
  :allowed?              (index-allowed? store)
  :malformed?            index-malformed?
  :exists?               (partial index-exists? store)
  :post-to-missing?      (constantly false)
  :post!                 (partial index-post! store)
  :handle-ok             (partial index-handle-ok store)
  :handle-created        index-handle-created)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resource

(defn resource-allowed? [store]
  (fn [ctx]
    (let [{:keys [request-method session params]} (:request ctx)
          {:keys [projects programmes role]} (sec/current-authentication session)
          entity_id (:entity_id params)
          {:keys [project_id programme_id]} (when entity_id
                                              (search/get-by-id entity_id (:search-session store)))]
      (if (and project_id programme_id)
        (allowed?* programme_id project_id programmes projects role request-method)
        true))))

(defn resource-exists? [store ctx]
  (db/with-session [session [:hecuba-session store]]
    (let [request      (:request ctx)
          route-params (:route-params request)
          {:keys [entity_id name]} route-params]
      (log/infof "resource-exists? :%s:%s" entity_id name)

      (when-let [item (datasets/get-by-id name entity_id session)]
        {::item item}
        #_(throw (ex-info (format "Cannot find item of id %s")))))))

(defn resource-post! [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [request (:request ctx)
          {:keys [operands name operation]} (decode-body request)
          converted-type (misc/output-type-for operation)]

      (datasets/insert {:entity_id (entity_id-from ctx)
                        :name      (name-from ctx)
                        :operands  operands
                        :operation operation} session))))

(defn resource-handle-ok [ctx]
  (let [item (::item ctx)]
    (util/render-item ctx item)))

(defresource resource [store]
  :allowed-methods       #{:get :post}
  :available-media-types #{"application/edn" "application/json"}
  :known-content-type?   #{"application/edn" "application/json"}
  :authorized?           (authorized? store :datasets)
  :allowed?              (resource-allowed? store)
  :exists?               (partial resource-exists? store)
  :post!                 (partial resource-post! store)
  :handle-ok             resource-handle-ok)
