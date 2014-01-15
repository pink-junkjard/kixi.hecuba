(ns kixi.hecuba.main-om
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]
   [cljs.core.async :refer [<! chan put! sliding-buffer]]
   [ajax.core :refer (GET POST)]))

(defn table-row [data owner]
  (om/component
      (dom/tr #js {:onClick (fn [e] (.log js/console "ooh!"))}
           (dom/td nil (:name data))
           (dom/td nil (apply str (interpose ", " (:leaders data)))))))

(defn table [data owner]
  (reify
    om/IRender
    (render [_]
      (dom/div #js {:className "table-responsive"}
           (dom/table #js {:className "table table-bordered table-hover table-striped"}
                (dom/thead nil
                     (dom/tr nil
                          (dom/th nil "Name")
                          (dom/th nil "Leaders")))
                (dom/tbody nil
                     (om/build-all table-row
                         (:projects data)
                         {:key :name})))))))

(def app-model
  {:active "dashboard"
   :menuitems [{:name "dashboard" :label "Dashboard" :href ""}
               {:name "overview" :label "Overview"}
               {:name "users" :label "Users"}
               {:name "programmes" :label "Programmes"}
               {:name "projects" :label "Project"}
               {:name "properties" :label "Properties"}
               {:name "about" :label "About"}
               {:name "documentation" :label "Documentation"}
               {:name "api_users" :label "API users"}]})

(def projects (atom {:projects []}))

;; Attach projects to a table component at hecuba-projects
(om/root projects table (.getElementById js/document "hecuba-projects"))

;; Get the real project data
(GET "/projects/" {:handler #(swap! projects assoc-in [:projects] %)
                   :headers {"Accept" "application/edn"}})
