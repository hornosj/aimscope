(ns aimscope.coach.bench
  "Integração DIRETA com os benchmarks do webapp kovaaks.com (pedido do JP,
  2026-07-02): puxa as pontuações oficiais do jogador por benchmark, avalia a
  skill dominante de cada mapa via catálogo e monta o plano-guia — quais
  skills estão fracas e quais mapas atacam cada uma.

  A régua aqui é a OFICIAL do bench (rank_maxes/scenario_rank da API), não a
  nossa semeada — a API é a fonte quando o jogador tem conta; os thresholds
  do catálogo seguem sendo o fallback offline do resto do coach."
  (:require [aimscope.coach.catalog :as cat]
            [aimscope.coach.kovaaks-api :as api]
            [aimscope.coach.labels :as labels]))

;; ---------------------------------------------------------------------------
;; benchmarks rastreados (IDs do webapp, verificados ao vivo 2026-07-02)
;; ---------------------------------------------------------------------------

(def tracked
  [{:benchmark-id 2335 :bench :viscose-s2 :nome "Viscose S2 — Easier"}
   {:benchmark-id 2336 :bench :viscose-s2 :nome "Viscose S2 — Medium"}
   {:benchmark-id 2337 :bench :viscose-s2 :nome "Viscose S2 — Hard"}
   {:benchmark-id 432  :bench :voltaic-s5 :nome "Voltaic S5 — Novice"}
   {:benchmark-id 431  :bench :voltaic-s5 :nome "Voltaic S5 — Intermediate"}
   {:benchmark-id 427  :bench :voltaic-s5 :nome "Voltaic S5 — Advanced"}])

;; ---------------------------------------------------------------------------
;; shaping: resposta da API -> linhas prontas pra UI
;; ---------------------------------------------------------------------------

(defn- dominant-skill
  "Skill de maior peso do cenário no catálogo; nil se o nome não casar."
  [catalog scenario-name]
  (when-let [entry (cat/match-scenario catalog scenario-name)]
    (key (apply max-key val (:skills entry)))))

(defn- scenario-row
  [catalog [scen-key {:keys [score scenario_rank rank_maxes leaderboard_id]}]]
  (let [nome  (name scen-key)
        skill (dominant-skill catalog nome)
        tiers (count rank_maxes)
        tier  (or scenario_rank 0)]
    {:scenario nome
     :score (when (and score (pos? score)) (/ score 100.0))
     :tier tier
     :tiers tiers
     :thresholds (vec rank_maxes)
     :next-threshold (when (< tier tiers) (nth rank_maxes tier))
     :leaderboard-id leaderboard_id
     :skill (some-> skill (as-> k (str (namespace k) "/" (name k))))
     :skill-label (some-> skill labels/skill-nome)
     :skill-hint (some-> skill labels/skill-dica)}))

(defn- shape-benchmark
  "player-progress-rank-benchmark -> {:nome ... :overall-rank ... :rank-names
  ... :categories [{:nome :rank :scenarios [row]}]} com os rows já rotulados.
  ranks[0] é 'No Rank'; o resto alinha 1:1 com os rank_maxes dos cenários —
  são as COLUNAS da planilha do bench (Wool/Rayon/... na Viscose,
  Iron/Bronze/... na Voltaic)."
  [catalog {:keys [nome bench]} body]
  (when (:categories body)
    {:nome nome
     :bench (name bench)
     :overall-rank (:overall_rank body)
     :overall-rank-name (get-in body [:ranks (:overall_rank body) :name])
     :rank-names  (mapv :name (rest (:ranks body)))
     :rank-colors (mapv :color (rest (:ranks body)))
     :categories
     (vec (for [[cat-key {:keys [category_rank scenarios]}] (:categories body)]
            {:nome (name cat-key)
             :rank category_rank
             :scenarios (mapv #(scenario-row catalog %) scenarios)}))}))

;; ---------------------------------------------------------------------------
;; fraquezas + plano-guia
;; ---------------------------------------------------------------------------

(defn- mark-weak
  "Fraco = tier abaixo da mediana dos cenários JOGADOS do benchmark (a régua é
  o próprio jogador: o bench é balanceado, ficar pra trás numa coluna é sinal).
  Não jogado nunca é 'fraco' — é pendente."
  [bench-map]
  (let [tiers (->> (:categories bench-map)
                   (mapcat :scenarios)
                   (filter :score)
                   (map :tier)
                   sort)]
    (if (empty? tiers)
      bench-map
      (let [mediana (nth tiers (quot (count tiers) 2))]
        (update bench-map :categories
                (fn [cats]
                  (mapv (fn [c]
                          (update c :scenarios
                                  (fn [rows]
                                    (mapv #(assoc % :weak?
                                                  (boolean (and (:score %)
                                                                (< (:tier %) mediana))))
                                          rows))))
                        cats)))))))

(defn guide
  "Plano-guia: nível médio (tier) por skill dominante nos cenários jogados,
  ordenado do mais fraco; cada skill fraca leva os mapas que a treinam — os
  do próprio bench (nome jogável real) primeiro, drills do catálogo depois."
  [catalog benchmarks]
  (let [rows (->> benchmarks
                  (mapcat :categories)
                  (mapcat :scenarios)
                  (filter #(and (:score %) (:skill %))))
        by-skill (group-by :skill rows)]
    (->> by-skill
         (map (fn [[skill rs]]
                (let [k (keyword skill)]
                  {:skill skill
                   :label (labels/skill-nome k)
                   :hint (labels/skill-dica k)
                   :nivel-medio (/ (reduce + (map :tier rs)) (double (count rs)))
                   :mapas (->> rs (sort-by :tier) (map :scenario) distinct (take 3) vec)
                   :drills (->> (cat/drills-for catalog k)
                                (map (comp labels/scenario-nome :id))
                                (take 3) vec)})))
         (sort-by :nivel-medio)
         vec)))

(defn fetch-all
  "conta (Steam: link/ID64/vanity; ou username do kovaaks.com) ->
  {:conta :steam-id :benchmarks [...] :guia [...]} ou {:erro ...}.
  Best-effort: benchmark sem resposta é omitido, nunca quebra."
  [catalog conta]
  (if-let [steam-id (api/resolve-steam-id conta)]
    (let [benches (->> tracked
                       (keep (fn [t]
                               (some->> (api/benchmark-progress (:benchmark-id t) steam-id)
                                        (shape-benchmark catalog t)
                                        mark-weak)))
                       vec)]
      {:conta conta
       :steam-id steam-id
       :benchmarks benches
       :guia (guide catalog benches)})
    {:erro (str "conta '" conta "' não encontrada — cole o link do seu perfil "
                "Steam (steamcommunity.com/...), seu SteamID64 ou seu vanity name")}))
