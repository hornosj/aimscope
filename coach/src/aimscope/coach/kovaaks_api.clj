(ns aimscope.coach.kovaaks-api
  "Cliente mínimo da API do webapp kovaaks.com (aberta, NÃO documentada —
  verificada em 2026-07-01). Regras da emenda ao grill #8:
  - cache local OBRIGATÓRIO (TTL 24h) — nunca martelar o backend deles
  - rate educado (1 req/s), timeout curto, falha = degrada silenciosamente
  Endpoints (via wrapper 333moxy/kovaaker): scenario/popular (+search),
  playlist/popular, leaderboard/scores/global."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.time Instant Duration]))

(def ^:private base "https://kovaaks.com/webapp-backend")
(def ^:private ttl-h 24)
(defonce ^:private last-req (atom 0))

(defn- cache-file [k]
  (io/file (System/getenv "LOCALAPPDATA") "aimscope" "cache"
           (str (Math/abs (hash k)) ".json")))

(defn- fresh? [f]
  (and (.exists f)
       (< (- (System/currentTimeMillis) (.lastModified f))
          (* ttl-h 3600 1000))))

(defn- polite-get [url params]
  (let [since (- (System/currentTimeMillis) @last-req)]
    (when (< since 1000) (Thread/sleep (- 1000 since))))
  (reset! last-req (System/currentTimeMillis))
  (-> (http/get url {:query-params params :timeout 15000
                     :headers {"user-agent" "aimscope/0.1 (analise pessoal de treino)"}})
      :body (json/parse-string true)))

(defn fetch
  "GET com cache. Devolve corpo parseado ou nil (offline/erro = degrada)."
  [path params]
  (let [k (str path "?" (pr-str (sort params)))
        f (cache-file k)]
    (if (fresh? f)
      (json/parse-string (slurp f) true)
      (try
        (let [body (polite-get (str base path) params)]
          (io/make-parents f)
          (spit f (json/generate-string body))
          body)
        (catch Exception e
          (.println System/err (str "[kovaaks-api] falha (seguindo sem): " (.getMessage e)))
          (when (.exists f) (json/parse-string (slurp f) true)))))))

(defn scenario-search [q]
  (fetch "/scenario/popular" {"page" 0 "max" 20 "scenarioNameSearch" q}))

(defn popular-scenarios [page max*]
  (fetch "/scenario/popular" {"page" page "max" max*}))

(defn global-leaderboard [leaderboard-id page]
  (fetch "/leaderboard/scores/global" {"leaderboardId" leaderboard-id "page" page "max" 20}))

(defn find-leaderboard
  "Acha o leaderboardId de um cenário pelo NOME EXATO (o CSV do KovaaK's usa o
  mesmo nome do webapp). nil se não achar/offline."
  [scenario-name]
  (when-let [body (fetch "/scenario/popular"
                         {"page" 0 "max" 25 "scenarioNameSearch" scenario-name})]
    (some (fn [row]
            (when (= (:scenarioName row) scenario-name)
              {:leaderboard-id (:leaderboardId row)
               :entries (get-in row [:counts :entries])}))
          (:data body))))

(defn score-percentile
  "Percentil global de `score` num leaderboard (busca binária por páginas de
  20, desc). Devolve {:rank r :total n :top-pct p} ou nil. Máx ~18 fetches,
  todos cacheados 24h — educado por construção."
  [leaderboard-id score]
  (when-let [p0 (global-leaderboard leaderboard-id 0)]
    (let [total (:total p0)
          pages (long (Math/ceil (/ (double total) 20.0)))]
      (when (pos? total)
        (loop [lo 0 hi (dec pages) it 0 best-rank nil]
          (if (or (> lo hi) (> it 18))
            (when best-rank
              {:rank best-rank :total total
               :top-pct (* 100.0 (/ (double best-rank) total))})
            (let [mid (quot (+ lo hi) 2)
                  body (if (zero? mid) p0 (global-leaderboard leaderboard-id mid))
                  rows (:data body)]
              (if (empty? rows)
                (recur lo (dec mid) (inc it) best-rank)
                (let [hi-score (:score (first rows))
                      lo-score (:score (last rows))]
                  (cond
                    ;; score melhor que o topo da página: está em página anterior
                    (> score hi-score)
                    (recur lo (dec mid) (inc it) (:rank (first rows)))
                    ;; pior que o fim da página: página posterior
                    (< score lo-score)
                    (recur (inc mid) hi (inc it)
                           (or best-rank (inc (:rank (last rows)))))
                    ;; dentro da página: primeiro rank cujo score <= o nosso
                    :else
                    (let [row (first (filter #(<= (:score %) score) rows))]
                      {:rank (:rank row) :total total
                       :top-pct (* 100.0 (/ (double (:rank row)) total))})))))))))))
