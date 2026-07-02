(ns aimscope.coach.main
  "CLI do coach: clojure -M:run <cmd>
  - ingest    varre --sessions e apenda fatos novos no user.db
  - diagnose  estima skills + resíduo -> diagnosis.{edn,json}
  - plan      GOAP p/ destravar o :player/target do perfil -> plan.{edn,json}
  - all       ingest + diagnose + plan
  Saídas em %LOCALAPPDATA%/aimscope/coach/ (EDN pra gente, JSON pra UI Rust)."
  (:require [aimscope.coach.catalog :as cat]
            [aimscope.coach.db :as db]
            [aimscope.coach.ingest :as ingest]
            [aimscope.coach.kovaaks-api :as api]
            [aimscope.coach.plan :as plan]
            [aimscope.coach.profile :as profile]
            [aimscope.coach.residual :as residual]
            [aimscope.coach.skills :as skills]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli])
  (:gen-class))

(def ^:private opts-spec
  [["-s" "--sessions DIR" "Diretório base das sessões"
    :default (str (io/file (System/getenv "LOCALAPPDATA") "aimscope" "sessions"))]
   ["-d" "--db PATH" "Caminho do user.db" :default (db/default-db-path)]
   [nil "--target ID" "Cenário alvo (ex.: com/bounce-180); default: perfil"]
   ["-h" "--help"]])

(defn- out-dir []
  (doto (io/file (System/getenv "LOCALAPPDATA") "aimscope" "coach") .mkdirs))

(defn- emit! [basename data]
  (let [d (out-dir)]
    (spit (io/file d (str basename ".edn")) (pr-str data))
    (spit (io/file d (str basename ".json")) (json/generate-string data {:pretty true}))
    (println (str "-> " (io/file d (str basename ".json"))))))

(defn- load-state [{:keys [db]}]
  (let [ds      (db/connect db)
        catalog (cat/load-catalog)
        scores  (db/facts ds :score)
        kins    (db/facts ds :kinematics)]
    {:ds ds :catalog catalog :scores scores :kins kins}))

(defn- skill-history
  "Série temporal das estimativas (fatos :skill-snapshot), p/ o gráfico da UI."
  [ds]
  (->> (db/facts ds :skill-snapshot)
       (map (fn [f]
              {:t (:fact/tx-time f)
               :skills (into {}
                             (keep (fn [[k v]] (when (:value v) [k (:value v)]))
                                   (:estimates f)))}))
       (take-last 40)
       vec))

(defn- latest-percentiles [ds]
  (->> (db/facts ds :percentile)
       (reduce (fn [acc p] (assoc acc (:scenario p) p)) {})
       vals
       (sort-by :top-pct)
       vec))

(defn- run-diagnose [opts]
  (let [{:keys [catalog scores kins ds]} (load-state opts)
        est   (skills/estimate kins scores catalog)
        dsdb  (db/->datascript scores est)
        res   (residual/analyze scores est catalog dsdb)
        diag  (assoc (residual/diagnosis est res)
                     :residuals res
                     :percentiles (latest-percentiles ds)
                     :n-scores (count scores) :n-sessions (count kins))]
    (db/append! ds :skill-snapshot nil {:estimates est})
    (emit! "diagnosis" (assoc diag :history (conj (skill-history ds)
                                                  {:t "agora" :skills
                                                   (into {} (keep (fn [[k v]] (when (:value v) [k (:value v)])) est))})))
    diag))

(def ^:private enrich-max-scenarios 6)

(defn- run-enrich
  "Percentil global (API kovaaks.com) para os cenários mais jogados.
  Educado: máx 6 cenários por execução, tudo cacheado 24h."
  [opts]
  (let [{:keys [scores ds]} (load-state opts)
        by-scen (->> (filter :catalog-id scores)
                     (group-by :scenario)
                     (sort-by (comp - count val))
                     (take enrich-max-scenarios))]
    (if (empty? by-scen)
      (println "nenhum score de cenário catalogado ainda")
      (doseq [[scen ss] by-scen]
        (let [best (apply max (map :score ss))]
          (if-let [{:keys [leaderboard-id]} (api/find-leaderboard scen)]
            (if-let [pct (api/score-percentile leaderboard-id best)]
              (do (db/append! ds :percentile (str scen "|" best)
                              (merge pct {:scenario scen
                                          :catalog-id (:catalog-id (first ss))
                                          :best-score best}))
                  (println (format "%s: top %.1f%% (rank %d de %d, score %.1f)"
                                   scen (:top-pct pct) (:rank pct) (:total pct)
                                   (double best))))
              (println (str scen ": leaderboard sem resposta (offline?)")))
            (println (str scen ": não achado no webapp (nome divergente?)"))))))))

(defn- resolve-target [catalog target-str prof]
  (let [tid (if target-str
              (keyword target-str)
              (get-in prof [:player/target :scenario]))]
    (or (first (filter #(= tid (:id %)) catalog))
        (throw (ex-info (str "cenário alvo não está no catálogo: " tid) {:target tid})))))

(defn- run-plan [opts]
  (let [{:keys [catalog scores kins ds]} (load-state opts)
        prof  (profile/load-profile)
        est   (skills/estimate kins scores catalog)
        entry (resolve-target catalog (:target opts) prof)
        p     (plan/build-plan catalog prof est entry)]
    ;; snapshot das skills NO MOMENTO do plano — é contra ele que o `outcome`
    ;; cobra a previsão (previsto × realizado, CONTEXT.md: Plano)
    (db/append! ds :plan nil {:plan p :profile prof :skills-at-plan est})
    (emit! "plan" p)
    p))

(def ^:private outcome-min-delta 3.0)

(defn- run-outcome [opts]
  (let [{:keys [catalog scores kins ds]} (load-state opts)
        plans (db/facts ds :plan)]
    (if (empty? plans)
      (println "nenhum plano registrado ainda — rode 'plan' primeiro")
      (let [{:keys [plan skills-at-plan] :as pf} (last plans)
            est (skills/estimate kins scores catalog)
            per-skill
            (vec (for [step (:steps plan)
                       :let [sk   (:skill step)
                             at   (get-in skills-at-plan [sk :value])
                             cur  (get-in est [sk :value])]
                       :when (and at cur)]
                   (let [delta (- cur at)]
                     {:skill sk :no-plano at :agora cur :delta delta
                      :veredito (cond (>= delta outcome-min-delta)     :progrediu
                                      (<= delta (- outcome-min-delta)) :regrediu
                                      :else                            :plato)})))
            plateaus (filterv #(#{:plato :regrediu} (:veredito %)) per-skill)
            out {:plano-de   (:fact/tx-time pf)
                 :alvo       (:target plan)
                 :por-skill  per-skill
                 :veredito-geral (cond
                                   (empty? per-skill) :sem-dados
                                   (empty? plateaus)  :progrediu
                                   :else              :plato-detectado)
                 ;; polo de falha do GOAP: platô ⇒ replaneje (mapas-prerequisito
                 ;; ou revisão das âncoras) — padrão skill/plateaued? do design
                 :recomendacao (if (seq plateaus)
                                 (str "platô em " (pr-str (mapv :skill plateaus))
                                      " — rode 'plan' de novo: o GOAP vai re-rotear")
                                 "previsão se confirmando — mantenha o plano")}]
        (db/append! ds :outcome nil out)
        (emit! "outcome" out)
        out))))

(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args opts-spec)
        cmd (first arguments)]
    (cond
      errors           (do (doseq [e errors] (println e)) (System/exit 2))
      (:help options)  (println (str "uso: <ingest|diagnose|plan|all> [opts]\n" summary))
      :else
      (case cmd
        "ingest"   (let [ds (db/connect (:db options))
                         r  (ingest/ingest! ds (:sessions options))]
                     (println (str (count r) " sessão(ões) com fatos novos"))
                     (doseq [x r] (println " " (:session x) "scores:" (:scores x) "kin:" (:kinematics x))))
        "diagnose" (do (run-diagnose options) nil)
        "plan"     (do (run-plan options) nil)
        "outcome"  (do (run-outcome options) nil)
        "enrich"   (do (run-enrich options) nil)
        "all"      (do (let [ds (db/connect (:db options))]
                         (ingest/ingest! ds (:sessions options)))
                       (run-diagnose options)
                       (run-plan options)
                       nil)
        (do (println (str "comando desconhecido: " cmd "\nuso: <ingest|diagnose|plan|outcome|all>"))
            (System/exit 2))))
    (shutdown-agents)))
