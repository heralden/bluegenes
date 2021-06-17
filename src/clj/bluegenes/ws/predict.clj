(ns bluegenes.ws.predict
  (:require [compojure.core :refer [defroutes GET]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.http-response :as response]
            [clj-http.client :as client]))

(defn predict-query [req]
  (try
    (let [{:keys [query beam_size candidates]} (:params req)]
      (client/get "http://polyglotter.apps.intermine.org/predict_query"
                  {:query-params {"query" query
                                  "beam_size" beam_size
                                  "candidates" candidates}}))
    (catch Exception e
      (response/internal-server-error
        (str "Failed to reach server for query prediction: " (.getMessage e))))))

(defroutes routes
  (wrap-params
   (wrap-keyword-params
    (GET "/query" [] predict-query))))
