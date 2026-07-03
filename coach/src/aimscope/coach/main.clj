(ns aimscope.coach.main
  "CLI do coach: clojure -M:run <cmd>
  - ingest    varre --sessions e apenda fatos novos no user.db
  - diagnose  estima skills + resíduo -> diagnosis.{edn,json}
  - plan      GOAP p/ destravar o :player/target do perfil -> plan.{edn,json}
  - all       ingest + diagnose + plan
  Saídas em %LOCALAPPDATA%/aimscope/coach/ (EDN pra gente, JSON pra UI Rust)."
  (:require [aimscope.coach.bench :as bench]
            [aimscope.coach.catalog :as cat]
            [aimscope.coach.db :as db]
            [aimscope.coach.ingest :as ingest]
            [aimscope.coach.kovaaks-api :as api]
            [aimscope.coach.labels :as labels]
            [aimscope.coach.placement :as placement]
            [aimscope.coach.plan :as plan]
            [aimscope.coach.profile :as profile]
            [aimscope.coach.residual :as residual]
            [aimscope.coach.skills :as skills]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
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
        thresholds (cat/load-thresholds)
        scores  (db/facts ds :score)
        kins    (db/facts ds :kinematics)]
    {:ds ds :catalog catalog :thresholds thresholds :scores scores :kins kins}))

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

(defn- skills-por-categoria
  "As 14 skills na ordem do bench Viscose, agrupadas nas 4 categorias — o
  espelho da planilha que a UI renderiza (categoria -> subskills). Skill sem
  evidência vai com :value nil: a UI mostra a linha apagada, nunca some."
  [est]
  (->> (cat/all-skills)
       (partition-by namespace)
       (mapv (fn [ks]
               {:categoria (namespace (first ks))
                :label     (labels/categoria-nome (first ks))
                :skills    (mapv (fn [k]
                                   (let [{:keys [value confidence]} (get est k)]
                                     {:skill k
                                      :label (labels/skill-nome k)
                                      :hint  (labels/skill-dica k)
                                      :value value
                                      :confidence confidence}))
                                 ks)}))))

(defn- labelize-diag
  "Acrescenta rótulos pt-BR aos campos voltados à UI — chave interna nunca
  vira texto de usuário (labels.clj é o único tradutor)."
  [diag]
  (cond-> diag
    (:skills/ranked diag)
    (update :skills/ranked
            (fn [xs] (mapv #(assoc % :label (labels/skill-nome (:skill %))
                                   :hint (labels/skill-dica (:skill %))) xs)))
    (:gargalo-global diag)
    (update :gargalo-global
            #(assoc % :label (labels/skill-nome (:skill %))
                    :hint (labels/skill-dica (:skill %))))
    (:skills/sem-evidencia diag)
    (assoc :skills/sem-evidencia-labels
           (mapv labels/skill-nome (:skills/sem-evidencia diag)))))

(defn- run-diagnose [opts]
  (let [{:keys [catalog thresholds scores kins ds]} (load-state opts)
        est   (skills/estimate kins scores catalog thresholds)
        dsdb  (db/->datascript scores est)
        res   (residual/analyze scores est catalog thresholds dsdb)
        diag  (assoc (residual/diagnosis est res)
                     :residuals res
                     :skills/por-categoria (skills-por-categoria est)
                     ;; Tendência (ADR 0004): direção vs. o próprio histórico,
                     ;; NUNCA nível — a UI desenha ↑↓ ao lado da barra absoluta
                     :skills/tendencia
                     (->> (skills/trend scores catalog)
                          (mapv (fn [[k {:keys [z dir]}]]
                                  {:skill k :label (labels/skill-nome k)
                                   :z z :dir (name dir)})))
                     :percentiles (latest-percentiles ds)
                     :placement (placement/status scores thresholds)
                     :n-scores (count scores) :n-sessions (count kins))]
    (db/append! ds :skill-snapshot nil {:estimates est})
    ;; alvos possíveis pro perfil: a UI mostra o rótulo, salva o id — o
    ;; usuário nunca digita chave interna (QA 2026-07-02)
    (emit! "targets"
           (->> catalog
                (map (fn [{:keys [id category benchmark]}]
                       {:id (str (namespace id) "/" (name id))
                        :label (labels/scenario-nome id)
                        :category (some-> category name)
                        :benchmark (some-> benchmark name)}))
                (sort-by (juxt :category :label))
                vec))
    (emit! "diagnosis" (labelize-diag
                        (assoc diag :history
                               (conj (skill-history ds)
                                     {:t "agora" :skills
                                      (into {} (keep (fn [[k v]] (when (:value v) [k (:value v)])) est))}))))
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

(defn- auto-target
  "Sem alvo explícito, o plano mira o drill da skill mais fraca COM evidência —
  o 'quero destravar' manual saiu da UI (QA 2026-07-02): o escopo do app é
  melhorar a mira, não escolher mapa."
  [catalog est]
  (let [com-ev (filter (comp :value val) est)]
    (when (seq com-ev)
      (let [[skill _] (apply min-key (comp :value val) com-ev)]
        (first (cat/drills-for catalog skill))))))

(defn- resolve-target [catalog target-str prof est]
  (or (when target-str
        (first (filter #(= (keyword target-str) (:id %)) catalog)))
      (auto-target catalog est)
      ;; fallback: alvo legado do perfil, se ainda existir e for válido
      (let [tid (get-in prof [:player/target :scenario])]
        (first (filter #(= tid (:id %)) catalog)))
      (throw (ex-info "sem alvo: nenhuma skill com evidência e nenhum --target"
                      {:target target-str}))))

(defn- run-plan [opts]
  (let [{:keys [catalog thresholds scores kins ds]} (load-state opts)
        prof  (profile/load-profile)
        est   (skills/estimate kins scores catalog thresholds)
        entry (resolve-target catalog (:target opts) prof est)
        p     (plan/build-plan catalog prof est entry)]
    ;; snapshot das skills NO MOMENTO do plano — é contra ele que o `outcome`
    ;; cobra a previsão (previsto × realizado, CONTEXT.md: Plano)
    (db/append! ds :plan nil {:plan p :profile prof :skills-at-plan est})
    (emit! "plan"
           (cond-> (assoc p :target-label (labels/scenario-nome (:target p)))
             (:steps p)
             (update :steps
                     (fn [steps]
                       (mapv #(assoc % :scenario-label (labels/scenario-nome (:scenario %))
                                     :skill-label (labels/skill-nome (:skill %)))
                             steps)))))
    p))

(defn- run-bench
  "Pontuações OFICIAIS do jogador nos benchmarks (API kovaaks.com) ->
  benchmarks.json pra aba Benchmarks da UI. A conta vem do perfil:
  :player/steam (link/ID64/vanity da Steam; legado :player/kovaaks-username
  ainda funciona). Sem conta, emite instrução em vez de dado."
  [_opts]
  (let [catalog (cat/load-catalog)
        prof    (profile/load-profile)
        conta   (or (not-empty (str (:player/steam prof)))
                    (not-empty (str (:player/kovaaks-username prof))))]
    (if conta
      (let [r (bench/fetch-all catalog conta)]
        (emit! "benchmarks" r)
        (when (:erro r) (println (:erro r)))
        r)
      (do (emit! "benchmarks"
                 {:erro "cole o link do seu perfil Steam no campo da aba Benchmarks"})
          nil))))

(def ^:private outcome-min-delta 3.0)

(defn- run-outcome [opts]
  (let [{:keys [catalog thresholds scores kins ds]} (load-state opts)
        plans (db/facts ds :plan)]
    (if (empty? plans)
      (println "nenhum plano registrado ainda — rode 'plan' primeiro")
      (let [{:keys [plan skills-at-plan] :as pf} (last plans)
            est (skills/estimate kins scores catalog thresholds)
            per-skill
            (vec (for [step (:steps plan)
                       :let [sk   (:skill step)
                             at   (get-in skills-at-plan [sk :value])
                             cur  (get-in est [sk :value])]
                       :when (and at cur)]
                   (let [delta (- cur at)]
                     {:skill sk :label (labels/skill-nome sk)
                      :no-plano at :agora cur :delta delta
                      :veredito (cond (>= delta outcome-min-delta)     :progrediu
                                      (<= delta (- outcome-min-delta)) :regrediu
                                      :else                            :plato)})))
            plateaus (filterv #(#{:plato :regrediu} (:veredito %)) per-skill)
            out {:plano-de   (:fact/tx-time pf)
                 :alvo       (:target plan)
                 :alvo-label (labels/scenario-nome (:target plan))
                 :por-skill  per-skill
                 :veredito-geral (cond
                                   (empty? per-skill) :sem-dados
                                   (empty? plateaus)  :progrediu
                                   :else              :plato-detectado)
                 ;; polo de falha do GOAP: platô ⇒ replaneje (mapas-prerequisito
                 ;; ou revisão das âncoras) — padrão skill/plateaued? do design
                 :recomendacao (if (seq plateaus)
                                 (str (str/join ", " (map :label plateaus))
                                      " estagnou — replaneje: o coach vai re-rotear o treino")
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
        "bench"    (do (run-bench options) nil)
        "all"      (do (let [ds (db/connect (:db options))]
                         (ingest/ingest! ds (:sessions options)))
                       (run-diagnose options)
                       (run-plan options)
                       ;; previsto×realizado embutido: a UI não precisa (nem
                       ;; tem mais) botão separado de "conferir previsão"
                       (run-outcome options)
                       ;; benchmarks oficiais: best-effort (rede) — nunca
                       ;; derruba o all; a aba tem botão próprio de atualizar
                       (try (run-bench options)
                            (catch Exception e
                              (.println System/err (str "[bench] " (.getMessage e)))))
                       nil)
        (do (println (str "comando desconhecido: " cmd "\nuso: <ingest|diagnose|plan|outcome|bench|all>"))
            (System/exit 2))))
    (shutdown-agents)))
