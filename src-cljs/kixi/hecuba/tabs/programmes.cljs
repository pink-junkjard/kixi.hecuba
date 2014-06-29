(ns kixi.hecuba.tabs.programmes
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    (:require
     [om.core :as om :include-macros true]
     [om.dom :as dom :include-macros true]
     [cljs.core.async :refer [<! >! chan put! sliding-buffer close! pipe map< filter< mult tap map>]]
     [goog.userAgent :as agent]
     [ajax.core :refer (GET POST)]
     [clojure.string :as str]
     [kixi.hecuba.navigation :as nav]
     [kixi.hecuba.widgets.datetimepicker :as dtpicker]
     [kixi.hecuba.widgets.chart :as chart]
     [kixi.hecuba.bootstrap :as bs]
     [kixi.hecuba.common :refer (index-of map-replace find-first interval)]
     [kixi.hecuba.history :as history]
     [kixi.hecuba.model :refer (app-model)]
     [sablono.core :as html :refer-macros [html]]))

(when (or (not agent/IE)
          (agent/isVersionOrHigher 9))
  (enable-console-print!))

(defn log [& msgs]
  (when (or (not agent/IE)
            (agent/isVersionOrHigher 9))
    (apply println msgs)))

;; our banner is 50px so we need to tweak the scrolling
(defn fixed-scroll-to-element [element]
  (let [rect (-> (.getElementById js/document element)
                 .getBoundingClientRect)
        top (.-top rect)]
    (.scrollBy js/window 0 (- top 50))))

(defn scroll-to-element [element]
  (-> (.getElementById js/document element)
      .scrollIntoView))

(defn back-to-programmes [history]
  (fn [_ _]
    (history/update-token-ids! history :sensors nil)
    (history/update-token-ids! history :devices nil)
    (history/update-token-ids! history :properties nil)
    (history/update-token-ids! history :projects nil)
    (history/update-token-ids! history :programmes nil)
    (fixed-scroll-to-element "programmes-div")))

(defn back-to-projects [history]
  (fn [_ _]
    (history/update-token-ids! history :sensors nil)
    (history/update-token-ids! history :devices nil)
    (history/update-token-ids! history :properties nil)
    (history/update-token-ids! history :projects nil)
    (fixed-scroll-to-element "projects-div")))

(defn back-to-properties [history]
  (fn [_ _]
    (history/update-token-ids! history :sensors nil)
    (history/update-token-ids! history :devices nil)
    (history/update-token-ids! history :properties nil)
    (fixed-scroll-to-element "properties-div")))

(defn back-to-devices [history]
  (fn [_ _]
    (history/update-token-ids! history :sensors nil)
    (history/update-token-ids! history :devices nil)
    (fixed-scroll-to-element "devices-div")))

(defn update-when [x pred f & args]
  (if pred (apply f x args) x))

(defn uri-for-selection-change
  "Returns the uri to load because of change of selection. Returns nil
   if no change to selection"
  [current-selected selection-key template nav-event]
  (let [ids          (-> nav-event :args :ids)
        new-selected (get ids selection-key)]
    (when (or (nil? current-selected)
              (nil? new-selected)
              (not= current-selected
                    new-selected))
      (vector new-selected
              (map-replace template ids)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Slugs
(defn slugify-programme [programme]
  (assoc programme :slug (:name programme)))

(defn slugify-project "Create a slug for projects in the UI"
  [project]
  (assoc project :slug (:name project)))

(defn- postal-address-filter [property_data]
  (filter #(when %
             (re-seq #"[A-Za-z0-9]" %))
          [(:address_street_two property_data)
           (:address_city property_data)
           (:address_code property_data)
           (:address_country property_data)]))

(defn postal-address-html
  [property_data]
  (interpose [:br ] (postal-address-filter property_data)))

(defn postal-address
  ([property_data separator]
     (str/trim (str/join separator (postal-address-filter property_data))))
  ([property_data]
     (postal-address property_data ", ")))

(defn slugify-property
  "Create a slug for a property in the UI"
  [property]
  (let [property_data (:property_data property)]
    (assoc property :slug (let [property_code (get property :property_code "CODELESS")
                                addr (postal-address property_data)]
                            (if (empty? addr)
                              property_code
                              (str property_code  ", " addr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Fetchers
(defn fetch-programmes [data]
  (om/update! data [:programmes :fetching] :fetching)
  (GET (str "/4/programmes/")
       {:handler  (fn [x]
                    (log "Fetching programmes.")
                    (om/update! data [:programmes :data] (mapv slugify-programme x))
                    (om/update! data [:programmes :fetching] (if (empty? x) :no-data :has-data)))
        :error-handler (fn [{:keys [status status-text]}]
                         (om/update! data [:programmes :fetching] :error)
                         (om/update! data [:programmes :error-status] status)
                         (om/update! data [:programmes :error-text] status-text))
        :headers {"Accept" "application/edn"}
        :response-format :text}))

(defn fetch-projects [programme-id data]
  (om/update! data [:projects :fetching] :fetching)
  (GET (str "/4/programmes/" programme-id "/projects/")
       {:handler  (fn [x]
                    (log "Fetching projects for programme: " programme-id)
                    (om/update! data [:projects :data] (mapv slugify-project x))
                    (om/update! data [:projects :fetching] (if (empty? x) :no-data :has-data)))
        :error-handler (fn [{:keys [status status-text]}]
                         (om/update! data [:projects :fetching] :error)
                         (om/update! data [:projects :error-status] status)
                         (om/update! data [:projects :error-text] status-text))
        :headers {"Accept" "application/edn"}
        :response-format :text}))

(defn fetch-properties [project-id data]
  (om/update! data [:properties :fetching] :fetching)
  (GET (str "/4/projects/" project-id "/properties/")
       {:handler  (fn [x]
                    (log "Fetching properties for project: " project-id)
                    (om/update! data [:properties :data] (mapv slugify-property x))
                    (om/update! data [:properties :fetching] (if (empty? x) :no-data :has-data)))
        :error-handler (fn [{:keys [status status-text]}]
                         (om/update! data [:properties :fetching] :error)
                         (om/update! data [:properties :error-status] status)
                         (om/update! data [:properties :error-text] status-text))
        :headers {"Accept" "application/edn"}
        :response-format :text}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Details Helpers
(defn flatten-device [device]
  (let [device-keys   (->> device keys (remove #(= % :readings)))
        parent-device (select-keys device device-keys)
        readings      (:readings device)]
    (map #(assoc % :parent-device parent-device) readings)))

(defn extract-sensors [devices]
  (vec (mapcat flatten-device devices)))

(defn get-property-details [selected-property-id data]
  (->>  data
        :properties
        :data
        (filter #(= (:id %) selected-property-id))
        first))

(defn get-sensors [selected-property-id data]
  (if selected-property-id
    (if-let [property-details (get-property-details selected-property-id data)]
      (extract-sensors (:devices property-details))
      [])
    []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; History loop - this drives the fetches and clear downs
(defn history-loop [history-channel data]
  (go-loop []
    (let [nav-event (<! history-channel)
          history-status (-> nav-event :args :ids)
          {:keys [programmes projects properties sensors]} history-status
          old-nav (:active-components @data)
          old-programmes (:programmes old-nav)
          old-projects (:projects old-nav)
          old-properties (:properties old-nav)]
      (log "Old Programmes: " old-programmes " Old Projects: " old-projects " Old Properties: " old-properties)
      (log "New Programmes: " programmes " New Projects: " projects " New Properties: " properties)

      ;; Clear down
      (when (or (nil? programmes)
                (empty? (-> @data :programmes :data)))
        (log "Clearing projects.")
        (om/update! data [:programmes :selected] nil)
        (om/update! data [:projects :data] [])
        (om/update! data [:projects :programme_id] nil)
        (fetch-programmes data))

      (when-not projects
        (log "Clearing properties.")
        (om/update! data [:projects :selected] nil)
        (om/update! data [:properties :data] [])
        (om/update! data [:properties :project_id] nil))

      (when-not properties
        (log "Clearing devices, sensors and measurements.")
        (om/update! data [:properties :selected] nil)
        (om/update! data [:property-details :data] {})
        (om/update! data [:property-details :property_id] nil)
        (om/update! data [:devices :data] [])
        (om/update! data [:sensors :data] [])
        (om/update! data [:measurements :data] []))

      (when-not sensors
        (om/update! data [:sensors :selected] nil))

      (when (and (not= programmes old-programmes)
                 programmes)
        (log "Setting selected programme to: " programmes)
        (om/update! data [:programmes :selected] programmes)
        (om/update! data [:projects :programme_id] programmes)
        (fetch-projects programmes data))

      (when (and (not= projects old-projects)
                 projects)
        (log "Setting selected project to: " projects)
        (om/update! data [:projects :selected] projects)
        (om/update! data [:properties :project_id] projects)
        (fetch-properties projects data))

      (when (and (not= properties old-properties)
                 properties)
        (log "Setting property details to: " properties)
        (om/update! data [:properties :selected] properties))

      (when sensors
        (om/update! data [:sensors :selected] sensors))
      
      ;; Update the new active components
      (om/update! data :active-components history-status))
    (recur)))

(defn selected-range-change
  [selected selection-key {{ids :ids search :search} :args}]
  (let [new-selected (get ids selection-key)]
    (when (or (nil? selected)
              (not= selected new-selected))
      (vector new-selected ids search))))

(defn chart-feedback-box [cursor owner]
  (om/component
   (dom/div nil cursor)))

(defn chart-ajax [in data {:keys [selection-key content-type]}]
  (go-loop []
    (let [nav-event (<! in)]
      (when-let [[new-range ids search] (selected-range-change (:range @data)
                                                               selection-key
                                                               nav-event)]
        (let [[start-date end-date] search
              entity_id        (get ids :properties)
              sensor_id        (get ids :sensors)
              [type device_id] (str/split sensor_id #"-")]

          (om/update! data :range {:start-date start-date :end-date end-date})
          (om/update! data :sensors sensor_id)
          (om/update! data :measurements [])

          ;; TODO ajax call should not be made on each change, only on this particular cursor update.
          (when (and (not (empty? start-date))
                     (not (empty? end-date))
                     (not (nil? device_id))
                     (not (nil? entity_id))
                     (not (nil? type)))

            ;; FIXME Should be a multimethod
            (let [url (case (interval start-date end-date)
                        :raw (str "/4/entities/" entity_id "/devices/" device_id "/measurements/"
                                  type "?startDate=" start-date "&endDate=" end-date)
                        :hourly_rollups (str "/4/entities/" entity_id "/devices/" device_id "/hourly_rollups/"
                                             type "?startDate=" start-date "&endDate=" end-date)
                        :daily_rollups (str "/4/entities/" entity_id "/devices/" device_id "/daily_rollups/"
                                            type "?startDate=" start-date "&endDate=" end-date))]
              (GET url
                   {:handler #(om/update! data :measurements %)
                    :headers {"Accept" "application/json"}
                    :response-format :json
                    :keywords? true}))))))
    (recur)))

(defn device-detail [{:keys [selected data] :as cursor} owner]
  (om/component
   (let [row      (first (filter #(= (:id %) selected) data))]
     (let [{:keys [description name
                   latitude longitude]} (:location row)]
       (dom/div nil
                (dom/h3 nil (apply str  "Device Detail "  (interpose \/ (remove nil? [description name])))) ;; TODO add a '-'
                (dom/p nil (str "Latitude: " latitude))
                (dom/p nil (str "Longitude: " longitude)))))))

(defn row-for [{:keys [selected data]}]
  (find-first #(= (:id %) selected) data))

(defn title-for [cursor & {:keys [title-key] :or {title-key :slug}}]
  (let [row (row-for cursor)]
    (get-in row (if (vector? title-key) title-key (vector title-key)))))

(defn title-for-sensor [{:keys [selected]}]
  (let [[type _] (str/split selected #"-")]
    type))

(defn error-row [data]
  [:div.row
   [:div.col-md-12.text-center
    [:p.lead {:style {:padding-top 30}}
     "There has been an error. Please contact " [:a {:href "mailto:support@mastodonc.com"} "support@mastodonc.com"]]
    [:p "Error Code: " (:error-status data) " Message: " (:error-text data)]]])

(defn no-data-row [data]
  [:div.row [:div.col-md-12.text-center [:p.lead {:style {:padding-top 30}} "No data available for this selection."]]])

(defn fetching-row [data]
  [:div.row [:div.col-md-12.text-center [:p.lead {:style {:padding-top 30}} "Fetching data for selection." ]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; programmes
(defn programmes-table [data owner]
  (reify
    om/IRender
    (render [_]
      (let [programmes (-> data :programmes)
            table-id   "programme-table"
            history    (om/get-shared owner :history)]
        (html
         [:table {:className "table table-hover"}
          [:thead
           [:tr
            [:th "Name"]
            [:th "Organisations"]
            [:th "ID"]
            [:th "Created At"]]]
          [:tbody
           (for [row (sort-by :name (:data programmes))]
             (let [{:keys [id lead_organisations name description created_at]} row]
               [:tr {:onClick (fn [_ _]
                                (om/update! programmes :selected id)
                                (history/update-token-ids! history :programmes id)
                                (fixed-scroll-to-element "projects-div"))
                     :className (if (= id (:selected programmes)) "success")
                     :id (str table-id "-selected")}
                [:td name]
                [:td lead_organisations]
                [:td id]
                [:td created_at]]))]])))))

(defn programmes-div [data owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.row#programmes-div
        [:div {:class "col-md-12"}
         [:h1 "Programmes"]
         (om/build programmes-table data)]]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; projects
(defmulti projects-table-html (fn [projects owner] (:fetching projects)))
(defmethod projects-table-html :fetching [projects owner]
  (fetching-row projects))

(defmethod projects-table-html :no-data [projects owner]
  (no-data-row projects))

(defmethod projects-table-html :error [projects owner]
  (error-row projects))

(defmethod projects-table-html :has-data [projects owner]
  (let [table-id   "projects-table"
        history    (om/get-shared owner :history)]
    [:div.row
     [:div.col-md-12
      [:table {:className "table table-hover"}
       [:thead
        [:tr [:th "Name"] [:th "Type"] [:th "Description"] [:th "Created At"] [:th "Organisation"] [:th "Project Code"]]]
       [:tbody
        (for [row (sort-by :id (:data projects))]
          (let [{:keys [id name type_of description created_at organisation project_code]} row]
            [:tr {:onClick (fn [_ _]
                             (om/update! projects :selected id)
                             (history/update-token-ids! history :projects id)
                             (fixed-scroll-to-element "properties-div"))
                  :className (if (= id (:selected projects)) "success")
                  :id (str table-id "-selected")}
             [:td name]
             [:td type_of]
             [:td description]
             [:td created_at]
             [:td organisation]
             [:td project_code]]))]]]]))

(defmethod projects-table-html :default [projects owner]
  [:div.row [:div.col-md-12]])

(defn projects-table [projects owner]
  (reify
    om/IRender
    (render [_]
      (html (projects-table-html projects owner)))))

(defn projects-div [data owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [programmes projects active-components]} data
            history (om/get-shared owner :history)]
        (html
         [:div.row#projects-div
          [:div {:class (str "col-md-12 " (if (:programme_id projects) "" "hidden"))}
           [:h2 "Projects"]
           [:ul {:class "breadcrumb"}
            [:li [:a
                  {:href "/app"}
                  (title-for programmes)]]]
           (om/build projects-table projects {:opts {:histkey :projects}})]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; properties
(defmulti properties-table-html (fn [properties owner] (:fetching properties)))
(defmethod properties-table-html :fetching [properties owner]
  (fetching-row properties))

(defmethod properties-table-html :no-data [properties owner]
  (no-data-row properties))

(defmethod properties-table-html :error [properties owner]
  (error-row properties))

(defmethod properties-table-html :has-data [properties owner]
  (let [table-id "properties-table"
        history  (om/get-shared owner :history)]
    [:div.col-md-12
     [:table {:className "table table-hover"}
      [:thead
       [:tr [:th "ID"] [:th "Type"] [:th "Address"] [:th "Region"] [:th "Ownership"] [:th "Technologies"] [:th "Monitoring Hierarchy"]]]
      (for [property-details (sort-by #(-> % :property_code) (:data properties))]
        (let [property_data (:property_data property-details)
              id            (:id property-details)]
          [:tr
           {:onClick (fn [_ _]
                       (om/update! properties :selected id)
                       (history/update-token-ids! history :properties id))
            :className (if (= id (:selected properties)) "success")
            :id (str table-id "-selected")}
           [:td (:property_code property-details)]
           [:td (:property_type property_data)]
           [:td (postal-address property_data)]
           [:td (:address_region property_data)]
           [:td (:ownership property_data)]
           [:td (for [ti (:technology_icons property_data)]
                  [:img.tmg-responsive {:src ti :width 40 :height 40}])]
           [:td (:monitoring_hierarchy property_data)]]))]]))

(defmethod properties-table-html :default [properties owner]
  [:div.row [:div.col-md-12]])

(defn properties-table [properties owner]
  (reify
    om/IRender
    (render [_]
      (html (properties-table-html properties owner)))))

(defn properties-div [data owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [programmes projects properties active-components]} data
            history (om/get-shared owner :history)]
        (html
         [:div.row#properties-div
          [:div {:class (str "col-md-12 " (if (:project_id properties) "" "hidden"))}
           [:h2 "Properties"]
           [:ul {:class "breadcrumb"}
            [:li [:a
                  {:href "/app"}
                  (title-for programmes)]]
            [:li [:a
                  {:onClick (back-to-projects history)}
                  (title-for projects)]]]
           (om/build properties-table properties {:opts {:histkey :properties}})]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sensors
(defn status-label [status]
  (if (= status "OK")
    [:span {:class "label label-success"} status]
    [:span {:class "label label-danger"} status]))

(defn sorting-th [owner label header-key]
  (let [{:keys [sort-spec th-chan]} (om/get-state owner)
        {:keys [sort-key sort-asc]} sort-spec]
    [:th {:onClick (fn [_ _] (put! th-chan header-key))}
     (str label " ")
     (if (= sort-key header-key)
       (if sort-asc
         [:i.fa.fa-sort-asc]
         [:i.fa.fa-sort-desc]))]))

(defn sensors-table [data owner {:keys [histkey path]}]
  (reify
    om/IInitState
    (init-state [_]
      {:th-chan (chan)
       :sort-spec {:sort-key :type
                   :sort-asc true}})
    om/IWillMount
    (will-mount [_]
      (go-loop []
        (let [{:keys [th-chan sort-spec]} (om/get-state owner)
              {:keys [sort-key sort-asc]} sort-spec
              th-click                    (<! th-chan)]
          (if (= th-click sort-key)
            (om/update-state! owner #(assoc %
                                       :sort-spec {:sort-key th-click
                                                   :sort-asc (not sort-asc)}))
            (om/update-state! owner #(assoc %
                                       :sort-spec {:sort-key th-click
                                                   :sort-asc true}))))
        (recur)))
    om/IRenderState
    (render-state [_ state]
      (let [{:keys [sort-key sort-asc]} (:sort-spec state)
            sensors                     (:sensors data)
            selected-property-id        (-> data :active-components :properties)
            flattened-sensors           (get-sensors selected-property-id data)
            chart                       (:chart data)
            history                     (om/get-shared owner :history)
            table-id                    "sensors-table"]
        (html
         [:table {:className "table table-hover"}
          [:thead
           [:tr
            (sorting-th owner "Type" :type)
            (sorting-th owner "Unit" :unit)
            (sorting-th owner "Period" :period)
            (sorting-th owner "Device" :device_id)
            (sorting-th owner "Status" :status)]]
          [:tbody
           (for [row (if sort-asc
                       (sort-by sort-key flattened-sensors)
                       (reverse (sort-by sort-key flattened-sensors)))]
             (let [{:keys [device_id type unit period status]} row
                   id (str type "-" device_id)]
               [:tr {:onClick (fn [_ _]
                                (om/update! sensors :selected id)
                                (om/update! chart :sensor id)
                                (om/update! chart :unit unit)
                                (history/update-token-ids! history :sensors id))
                     :className (if (= id (:selected sensors)) "success")
                     :id (str table-id "-selected")}
                [:td type]
                [:td unit]
                [:td period]
                [:td device_id]
                [:td (status-label status)]]))]])))))

(defn chart-summary
  "Show min, max, delta and average of chart data."
  [chart owner]
  (reify
    om/IRender
    (render [_]
      (let [{:keys [unit measurements]} chart
            ;; FIXME why are measurements nested? (in prep for multi-series?)
            series-1 (:measurements measurements)
            vals-1 (map :value series-1)
            series-1-min (apply min vals-1)
            series-1-max (apply max vals-1)
            series-1-sum (reduce + vals-1)
            series-1-count (count series-1)
            series-1-mean (if (not= 0 series-1-count) (/ series-1-sum series-1-count) "NA")]
        (html
         (if (seq series-1)
           [:div.col-md-12#summary-stats
            [:div {:class "col-md-3"}
             (bs/panel "Minimum" (str (.toFixed (js/Number. series-1-min) 3) " " unit))]
            [:div {:class "col-md-3"}
             (bs/panel "Maximum" (str (.toFixed (js/Number. series-1-max) 3) " " unit))]
            [:div {:class "col-md-3"}
             (bs/panel "Average (mean)" (str (.toFixed (js/Number. series-1-mean) 3) " " unit))]
            [:div {:class "col-md-3"}
             (bs/panel "Range" (str (.toFixed (js/Number. (- series-1-max series-1-min)) 3) " " unit))]]
           [:div.row#summary-stats [:div.col-md-12.text-center [:p.lead {:style {:padding-top 30}} "No data."]]]))))))

(defn sensors-div [data owner]
  (reify
    om/IRender
    (render [_]
      (html
       [:div.col-md-12
        [:h3 {:id "sensors"} "Sensors"]
        (om/build sensors-table data {:opts {:histkey :sensors
                                             :path    :readings}})
        ;; FIXME: We should have better handling for IE8 here.
        (if (or (not agent/IE)
                (agent/isVersionOrHigher 9))
          [:div {:id "chart-div"}
           [:div {:id "date-picker"}
            (om/build dtpicker/date-picker data {:opts {:histkey :range}})]
           (om/build chart-feedback-box (get-in data [:chart :message]))
           (om/build chart-summary (:chart data))
           [:div {:className "well" :id "chart" :style {:width "100%" :height 600}}
            (om/build chart/chart-figure (:chart data))]]
          [:div.col-md-12.text-center
           [:p.lead {:style {:padding-top 30}}
            "Charting in Internet Explorer version " agent/VERSION " coming soon."]])]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; property-details
(defn detail-section [title text]
  (if (and text (re-seq #"[A-Za-z0-9]" text))
    [:div [:h3 title]
     [:p text]]
    [:div {:class "hidden"}]))

(defn property-details-div [data owner]
  (reify
    om/IInitState
    (init-state [_]
      {:active-tab :overview})
    om/IRenderState
    (render-state [_ state]
      (let [active-tab           (:active-tab state)
            selected-property-id (-> data :active-components :properties)
            properties           (-> data :properties :data)
            property-details     (get-property-details selected-property-id data)
            property_data        (:property_data property-details)]
        (html [:div {:class (str "col-md-12" (if selected-property-id "" " hidden"))}
               [:h2 "Property Details"]
               (bs/panel
                (:slug property-details)
                [:div ;; Tab Container
                 [:ul.nav.nav-tabs {:role "tablist"}
                  [:li {:class (if (= active-tab :overview) "active" nil)}
                   [:a {:onClick (fn [_ _] (om/set-state! owner :active-tab :overview))}
                    "Overview"]]
                  [:li {:class (if (= active-tab :profiles) "active" nil)}
                   [:a {:onClick (fn [_ _] (om/set-state! owner :active-tab :profiles))}
                    "Profiles"]]
                  [:li {:class (if (= active-tab :sensors) "active" nil)}
                   [:a {:onClick (fn [_ _] (om/set-state! owner :active-tab :sensors))}
                    "Sensor Data"]]]
                 ;; Overview
                 [:div {:class (if (not= active-tab :overview) "hidden" "col-md-12")}
                  [:h3 "Overview"]
                  [:div.col-md-4
                   [:dl.dl-horizontal
                    [:dt "Property Code"] [:dd (:property_code property-details)]
                    [:dt "Address"] [:dd (postal-address-html property_data)]
                    [:dt "Property Type"] [:dd (:property_type property_data)]
                    [:dt "Built Form"] [:dd (:built_form property_data)]
                    [:dt "Age"] [:dd (:age property_data)]
                    [:dt "Ownership"] [:dd (:ownership property_data)]
                    [:dt "Project Phase"] [:dd (:project_phase property_data)]
                    [:dt "Monitoring Hierarchy"] [:dd (:monitoring_hierarchy property_data)]
                    [:dt "Practical Completion Date"] [:dd (:practical_completion_date property_data)]
                    [:dt "Construction Date"] [:dd (:construction_date property_data)]
                    [:dt "Conservation Area"] [:dd (:conservation_area property_data)]
                    [:dt "Listed Building"] [:dd (:listed property_data)]
                    [:dt "Terrain"] [:dd (:terrain property_data)]
                    [:dt "Degree Day Region"] [:dd (:degree_day_region property_data)]
                    ]]
                  [:div.col-md-2
                   (when-let [pic (:path (first (:photos property-details)))]
                     [:img.img-thumbnail.tmg-responsive
                      {:src (str "https://s3-us-west-2.amazonaws.com/get-embed-data/" pic)}])]
                  [:div.col-md-6
                   (for [ti (:technology_icons property_data)]
                     [:img.tmg-responsive {:src ti :width 80 :height 80}])]
                  [:div.col-md-12
                   (detail-section "Description" (:description property_data))
                   (detail-section "Project Summary" (:project_summary property_data))
                   (detail-section "Project Team" (:project_team property_data))
                   (detail-section "Design Strategy" (:design_strategy property_data))
                   (detail-section "Energy Strategy" (:energy_strategy property_data))
                   (detail-section "Monitoring Policy" (:monitoring_policy property_data))
                   (detail-section "Other Notes" (:other_notes property_data))]]
                 ;; Sensors
                 [:div {:class (if (not= active-tab :sensors) "hidden" nil)}
                  (om/build sensors-div data)]
                 ;; Profiles
                 [:div {:class (if (not= active-tab :profiles) "hidden" "col-md-12")}
                  [:h3 "Profiles"]
                  [:div.col-md-12.text-center
                   [:p.lead {:style {:padding-top 30}}
                    "Profiles go here."]]]]
                
                )])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main View
(defn programmes-tab [data owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [history     (om/get-shared owner :history)
            property-chan (om/get-shared owner :property-chan)
            m           (mult (history/set-chan! history (chan)))
            tap-history #(tap m (chan))]

        ;; handle navigation changes
        (history-loop (tap-history) data)

        (chart-ajax (tap-history)
                    (:chart data)
                    {:template "/4/entities/:properties/devices/:devices/measurements?startDate=:start-date&endDate=:end-date"
                     :content-type  "application/json"
                     :selection-key :range})))
    om/IRender
    (render [_]

      (html [:div
             (om/build programmes-div data)
             (om/build projects-div data)
             (om/build properties-div data)
             (om/build property-details-div data)
             ;; (om/build devices-div data)
             ;; 
             ]))))
