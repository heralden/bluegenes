(ns bluegenes.route
  (:require [re-frame.core :refer [subscribe dispatch reg-sub reg-event-db reg-event-fx reg-fx]]
            [oops.core :refer [ocall]]
            [reitit.core :as r]
            [reitit.coercion :as rc]
            [reitit.coercion.spec :as rcs]
            [reitit.frontend :as rf]
            [reitit.frontend.controllers :as rfc]
            [reitit.frontend.easy :as rfe]
            [bluegenes.config :refer [read-default-ns]]))

;; # Quickstart guide:
;; (aka. I just want to route something but don't want to read all this code!)
;; - Don't forget to import the route namespace!
;; ```
;; (require '[bluegenes.route :as route])
;; ```
;; - Href
;; ```
;; [:a {:href (route/href ::route/upload-step {:step "save"})} "My anchor"]
;; ```
;; - Dispatch (for on-click and event handlers)
;; ```
;; (dispatch [::route/navigate ::route/upload-step {:step "save"}])
;; ```
;; ## Order of arguments: `route params query`
;; Only route is required, the rest are optional. Although you will get a
;; warning if you use a route that expects params, without specifying params.
;; Note that the `:mine` param is an exception to this, since it's the parent
;; of all routes, so it gets injected automatically from db if not specified.
;;
;; There is also an event you can dispatch to step back in the history.
;; ```
;; (dispatch [::route/go-back])
;; ```

;; Based on the official reitit frontend-re-frame example: (2019.06.18)
;; https://github.com/metosin/reitit/tree/master/examples/frontend-re-frame

;;; Events ;;;

(reg-event-fx
 ::navigate
 (fn [{db :db} [_ route & [params query]]]
   {::navigate! {:k route
                 :query query
                 :params (update params :mine #(or % (:current-mine db)))}}))

(reg-event-fx
 ::go-back
 (fn [{db :db} [_]]
   {::go-back! {}}))

;; This event handler is for internal use by router.
;; Do not dispatch unless you know what you're doing!
(reg-event-db
 ::navigated
 (fn [db [_ new-match]]
   (let [old-match   (:current-route db)
         controllers (rfc/apply-controllers (:controllers old-match) new-match)]
     (assoc db :current-route (assoc new-match :controllers controllers)))))

;; The use-case of fetching lists only by name isn't used elsewhere, and is
;; surprisingly difficult. So we have our own biga$$ event handler right here.
(reg-event-fx
 ::view-list
 (fn [{db :db} [_ list-name]]
   (let [current-mine (:current-mine db)
         queries (get-in db [:results :queries])]
     (if (contains? queries list-name)
       ;; We've already queried it so cut the bull$#!|. (Something else ran
       ;; `:results/history+`, so we skip right to `:results/load-history`.)
       {:dispatch [:results/load-history list-name]}
       (let [lists (get-in db [:assets :lists current-mine])
             ;; Get data from the assets list map of the same name.
             {:keys [type name title]} (first (filter #(= (:name %) list-name) lists))
             ;; Get summary fields from assets.
             summary-fields (get-in db [:assets :summary-fields current-mine (keyword type)])]
         ;; Now we can build our query for use with `:results/history+`.
         {:dispatch-n [[:results/history+
                        {:source current-mine
                         :type :query
                         :intent :list
                         :value {:title title
                                 :from type
                                 :select summary-fields
                                 :where [{:path type
                                          :op "IN"
                                          :value name}]}}
                        true] ; This is so we don't dispatch a route navigation.
                       ;; Hence, we need to dispatch `:results/load-history` manually.
                       [:results/load-history list-name]]})))))

;;; Subscriptions ;;;

(reg-sub
 ::current-route
 (fn [db]
   (:current-route db)))

;;; Effects ;;;
;; You should dispatch the event handlers above instead of using the effects
;; directly. Such is the way of re-frame!

(reg-fx
 ::navigate!
 (fn [{:keys [k params query]}]
   (rfe/push-state k params query)))

(reg-fx
 ::go-back!
 (fn [_]
   (.back js/window.history)))

(defn href
  "Return relative url for given route. Url can be used in HTML links."
  ([k]
   (href k nil nil))
  ([k params]
   (href k params nil))
  ([k params query]
   (let [current-mine (subscribe [:current-mine-name])]
     (rfe/href k (update params :mine #(or % @current-mine)) query))))

;; The majority of the routes fire a `:set-active-panel` but ours is slightly
;; different from what's in the re-frame boilerplate. Our `:set-active-panel`
;; event takes some extra values:
;; ```
;; [:set-active-panel
;; :panel-name
;; {some data to store in {:db {:panel-params}}
;; :some-event-to-fire-after-the-route-has-dispatched])
;; ```
;; An alternative way to handling routing would be to replace
;; `:set-active-panel` with a router component, which displays the view (which
;; would be added to the route data map) corresponding to `::current-route`.
;; This means we would have to move the logic handling buffering and forwarding
;; from the `:set-active-panel` event handler into a top-level `:controllers`
;; for all our routes. Doing this would fix the discrepancy we currently have
;; between `:set-active-panel` and the router events.
(def routes
  ["/"
   [":mine"
    {:controllers
     [{:parameters {:path [:mine]}
       :start (fn [{{:keys [mine]} :path}]
                (dispatch [:set-current-mine mine]))}]}
    [""
     {:name ::home
      :controllers
      [{:start (fn []
                 (dispatch [:set-active-panel :home-panel
                            nil
                            [:bluegenes.events.blog/fetch-rss]]))}]}]
    ["/admin"
     {:name ::admin
      :controllers
      [{:start (fn []
                 (dispatch [:set-active-panel :admin-panel
                            nil
                            [:bluegenes.pages.admin.events/init]])
                 (dispatch [:bluegenes.components.tools.events/fetch-tools]))}]}]
    ["/tools"
     {:name ::tools
      :controllers
      [{:start (fn []
                 (dispatch [:set-active-panel :tools-panel
                            nil
                            [:bluegenes.pages.tools.events/init]]))}]}]
    ["/profile"
     {:name ::profile
      :controllers
      [{:start #(dispatch [:set-active-panel :profile-panel
                           nil
                           [:bluegenes.pages.profile.events/load-profile]])}]}]
    ["/debug/:panel"
     {:name ::debug
      :controllers
      [{:parameters {:path [:panel]}
        :start (fn [{{:keys [panel]} :path}]
                 (dispatch [:set-active-panel :debug-panel
                            nil
                            [:bluegenes.pages.developer.events/panel panel]]))}]}]
    ["/templates"
     {:name ::templates
      :controllers
      [{:start #(dispatch [:set-active-panel :templates-panel])}]}]
    ["/templates/:template"
     {:name ::template
      :controllers
      [{:parameters {:path [:template]}
        :start (fn [{{:keys [template]} :path}]
                 (dispatch [:template-chooser/open-template (keyword template)]))
        :stop (fn []
                (dispatch [:template-chooser/deselect-template]))}]}]
    ["/upload"
     [""
      {:name ::upload
       :controllers
       [{:start #(dispatch [:set-active-panel :upload-panel])}]}]
     ["/:step"
      {:name ::upload-step
       :controllers
       [{:parameters {:path [:step]}
         :start (fn [{{:keys [step]} :path}]
                  (dispatch [:set-active-panel :upload-panel
                             {:step (keyword step)}]))}]}]]
    ["/upgrade"
     {:name ::upgrade
      :controllers
      [{:parameters {:query [:name]}
        :start (fn [{{:keys [name]} :query}]
                 (dispatch [:set-active-panel :upgrade-panel
                            {:upgrade-list name}
                            [:bluegenes.components.idresolver.events/resolve-identifiers {:name name}]]))}]}]
    ["/search"
     {:name ::search
      :controllers
      [{:parameters {:query [:keyword]}
        :start (fn [{{:keys [keyword]} :query}]
                 (dispatch [:search/start-scroll-handling])
                 (dispatch [:set-active-panel :search-panel
                            nil
                            [:search/begin-search keyword]]))
        :stop #(dispatch [:search/stop-scroll-handling])}]}]
    ["/querybuilder"
     {:name ::querybuilder
      :controllers
      [{:start #(dispatch [:set-active-panel :querybuilder-panel
                           nil
                           [:bluegenes.pages.querybuilder.events/load-querybuilder]])}]}]
    ["/results/:title"
     {:name ::results
      :controllers
      [{:parameters {:path [:title]}
        :start (fn [{{:keys [title]} :path}]
                 ;; We have to clear the previous query and tools entity, as
                 ;; otherwise we may show old results and tools from the
                 ;; previous invocation. This way, we'll only show the new
                 ;; results and tools once they're ready.
                 (dispatch [:results/clear])
                 (dispatch [:clear-ids-tool-entity])
                 (dispatch [:viz/clear])
                 (dispatch [:set-active-panel :results-panel
                            nil
                            [::view-list title]])
                 (dispatch [:results/listen-im-table-changes]))
        :stop (fn []
                (dispatch [:results/unlisten-im-table-changes]))}]}]
    ["/regions"
     {:name ::regions
      :controllers
      [{:start #(dispatch [:set-active-panel :regions-panel])}]}]
    ["/lists"
     {:name ::lists
      :controllers
      [{:start (fn []
                 (dispatch [:set-active-panel :lists-panel
                            nil
                            [:lists/initialize]]))}]}]
    ["/report/:type/:id"
     {:name ::report
      :controllers
      [{:parameters {:path [:mine :type :id]}
        :start (fn [{{:keys [mine type id]} :path}]
                 (dispatch [:clear-ids-tool-entity])
                 (dispatch [:viz/clear])
                 (dispatch [:set-active-panel :reportpage-panel
                            {:type type, :id id, :format "id", :mine mine}
                            [:load-report mine type id]]))
        :stop #(dispatch [:bluegenes.pages.reportpage.events/stop-scroll-handling])}]}]
    ["/share/:lookup"
     {:name ::share
      :controllers
      [{:parameters {:path [:mine :lookup]}
        :start (fn [{{:keys [mine lookup]} :path}]
                 (dispatch [:handle-permanent-url mine lookup]))}]}]]])
;; You can do initialisations by adding a :start function to :controllers.
;; :start (fn [& params] (js/console.log "Entering page"))
;; Teardowns can also be done by using the :stop key.
;; :stop  (fn [& params] (js/console.log "Leaving page"))])

(defn on-navigate [new-match]
  ;; - Put side-effects you want to run on every page load/change here!
  ;; Make sure there are no hanging popovers.
  (ocall (js/$ ".popover") "remove")
  ;; Track the page (new-match has :data, so can use anything from `routes`).
  (try
    (js/ga "send" "pageview" (:path new-match))
    (catch js/Error _))
  ;; - Handle actual navigation.
  (if new-match
    (dispatch [::navigated new-match])
    ;; We end up here when the URL path is empty.
    (dispatch [::navigate ::home {:mine (name (read-default-ns))}])))

(def router
  (rf/router
   routes
   {:data {:coercion rcs/coercion}}))

(defn init-routes! []
  (rfe/start!
   router
   on-navigate
   {:use-fragment false}))
