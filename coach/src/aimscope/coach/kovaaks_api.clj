(ns aimscope.coach.kovaaks-api
  "Cliente mínimo da API do webapp kovaaks.com (aberta, NÃO documentada —
  verificada em 2026-07-01). Regras da emenda ao grill #8:
  - cache local OBRIGATÓRIO (TTL 24h) — nunca martelar o backend deles
  - rate educado (1 req/s), timeout curto, falha = degrada silenciosamente
  Endpoints (via wrapper 333moxy/kovaaker): scenario/popular (+search),
  playlist/popular, leaderboard/scores/global."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant Duration]))

(def ^:private base "https://kovaaks.com/webapp-backend")
(def ^:private ttl-h 24)
(defonce ^:private last-req (atom 0))

(defn- cache-file [k]
  (io/file (System/getenv "LOCALAPPDATA") "aimscope" "cache"
           (str (Math/abs (hash k)) ".json")))

(defn- fresh? [f ttl-h*]
  (and (.exists f)
       (< (- (System/currentTimeMillis) (.lastModified f))
          (* ttl-h* 3600 1000))))

(defn- polite-get [url params]
  (let [since (- (System/currentTimeMillis) @last-req)]
    (when (< since 1000) (Thread/sleep (- 1000 since))))
  (reset! last-req (System/currentTimeMillis))
  (-> (http/get url {:query-params params :timeout 15000
                     :headers {"user-agent" "aimscope/0.1 (analise pessoal de treino)"}})
      :body (json/parse-string true)))

(defn fetch
  "GET com cache. Devolve corpo parseado ou nil (offline/erro = degrada).
  ttl opcional em horas (default 24; pontuações de benchmark usam 1)."
  ([path params] (fetch path params ttl-h))
  ([path params ttl]
   (let [k (str path "?" (pr-str (sort params)))
         f (cache-file k)]
     (if (fresh? f ttl)
       (json/parse-string (slurp f) true)
       (try
         (let [body (polite-get (str base path) params)]
           (io/make-parents f)
           (spit f (json/generate-string body))
           body)
         (catch Exception e
           (.println System/err (str "[kovaaks-api] falha (seguindo sem): " (.getMessage e)))
           (when (.exists f) (json/parse-string (slurp f) true))))))))

(defn steam-id-for
  "username do webapp -> steamId (perfil oficial; fallback: busca por nome,
  match case-insensitive exato). nil se não achar/offline."
  [username]
  (or (:steamId (fetch "/user/profile/by-username" {"username" username}))
      (some (fn [{:keys [steamId] u :username}]
              (when (and u (.equalsIgnoreCase ^String u ^String username)) steamId))
            (fetch "/user/search" {"username" username}))))

(defn- steam-vanity->id
  "Vanity da Steam -> steamID64 via o XML público do steamcommunity (sem API
  key). nil se não existir/offline."
  [vanity]
  (try
    (let [body (:body (http/get (str "https://steamcommunity.com/id/" vanity "/?xml=1")
                                {:timeout 15000
                                 :headers {"user-agent" "aimscope/0.1 (analise pessoal de treino)"}}))]
      (second (re-find #"<steamID64>(\d{17})</steamID64>" (str body))))
    (catch Exception _ nil)))

(defn resolve-steam-id
  "Entrada do usuário -> steamID64. Aceita, nesta ordem:
  - steamID64 cru (17 dígitos) ou URL steamcommunity.com/profiles/<id>
  - URL steamcommunity.com/id/<vanity> ou o vanity puro (resolvido via Steam)
  - por último, username do webapp kovaaks.com (quem já conhece o site).
  A Steam é a porta de entrada (QA do JP): nem todo mundo sabe que o
  kovaaks.com existe."
  [input]
  (let [s (str/trim (str input))]
    (when (seq s)
      (or (re-find #"^\d{17}$" s)
          (second (re-find #"steamcommunity\.com/profiles/(\d{17})" s))
          (when-let [v (second (re-find #"steamcommunity\.com/id/([^/?\s]+)" s))]
            (steam-vanity->id v))
          (when-not (str/includes? s "/") (steam-vanity->id s))
          (when-not (str/includes? s "/") (steam-id-for s))))))

(defn benchmark-progress
  "Progresso do jogador num benchmark: categorias -> cenários com score (×100),
  scenario_rank (tier 0-N) e rank_maxes (a régua oficial do bench). TTL 1h —
  o jogador joga e quer ver a pontuação nova sem esperar um dia."
  [benchmark-id steam-id]
  (fetch "/benchmarks/player-progress-rank-benchmark"
         {"benchmarkId" benchmark-id "steamId" steam-id}
         1))

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
