(ns bluegenes.components.search.filters
  (:require [re-frame.core :as re-frame]
            [oops.core :refer [oget]]))

(defn remove-filter
  "the little x in the corner that allows you to remove filters, and its behaviour"
  [filter-name]
  [:a {:aria-label (str "Remove " filter-name " filter") ;;we need this to stop screen readers from reading the 'x' symbol out loud as though it was meaningful text
       :on-click (fn [e]
                   (.stopPropagation e) ;; if we don't do this the event bubbles to the tr click handler and re-applies the filter. lol.
                   (re-frame/dispatch [:search/remove-active-filter]))}
   [:span.close "×"]]) ;;that's a cute little &times; to us HTML folk

(defn display-active-filter
  "Outputs which filter is active (if any) at the top of the filter section"
  [active-filter]
  [:div.active
   [:h5 "Active filters: "]
   (if (some? active-filter)
     [:div.active-filter active-filter
      [remove-filter active-filter]]
     [:div "None"])])

(defn controls []
  [:form.controls
   [:label
    [:input {:type "checkbox" :on-click
             (fn [e]
       ;;toggle highlight.
               (re-frame/dispatch [:search/highlight-results (oget e "target" "checked")]))}]
    [:span.checkbox-material [:span.check]]
    "Highlight search terms in results (experimental, may be sluggish)"]])

(defn facet-display
  "Visual component which outputs the category filters."
  []
  (let [state (re-frame/subscribe [:search/full-results])
        facets (:facets @state)
        active (:active-filter @state)]
    (when (some? facets)
      [:div.facets
       [:h4 "Filter by:"]
       [display-active-filter active]
       [:div
       ;;TODO: Re-implement this filter when we implement RESTful server-side filters
      ;  [:h5 "Organisms"]
      ;  [:table
      ;   (for [[name value] (:organisms facets)]
      ;     ^{:key name}
      ;     [:tr
      ;      [:td.count value]
      ;      [:td name]])]
        [:h5 "Categories"]
        [:table [:tbody
                 (for [[facet-name value] (:category facets)]
                   ^{:key facet-name}
                   [:tr {:on-click (fn [e] (re-frame/dispatch [:search/set-active-filter facet-name]))
                         :class (if (= facet-name active) "active")}
                    [:td.count.result-type {:class (str "type-" (name facet-name))} value]
                    [:td facet-name (if (= facet-name active)
                                      [remove-filter facet-name])]])]]]
       [controls]])))
