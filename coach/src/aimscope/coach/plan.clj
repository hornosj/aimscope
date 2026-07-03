(ns aimscope.coach.plan
  "Domínio GOAP do plano de treino. Padrão herdado do email_hunter_v2: as
  CONDIÇÕES são strings positivas ('skill/<nome>/ok?'), as ações têm
  :action/pre :action/post :action/cost, e o A* (goap.clj) acha o caminho
  mais barato até o goal. Aqui as ações são GERADAS DO CATÁLOGO (dados),
  não vars — mesmo contrato, origem diferente.

  Perfil de objetivo (CONTEXT.md, ADR 0005 — eixos ortogonais) filtra as ações:
  - política de sens :fixed  → ações de mudança de sens NÃO EXISTEM no espaço
  - política :range | :search → mudar sens é ação com custo alto (re-adaptação)
  - alvo :transfer → custos reponderados pelo prior de transferência (baixa
    confiança, documentado)
  Perfis legados (:player/goal) são normalizados na entrada."
  (:require [aimscope.coach.goap :as goap]
            [aimscope.coach.profile :as profile]))

(def ^:private skill-gate 60.0)   ; skill "ok" p/ fins de goal/pre
(def ^:private session-min 15)    ; bloco padrão de grind por cenário

(defn- ok-cond [skill] (str "skill/" (namespace skill) "-" (name skill) "/ok?"))

(def ^:private transfer-prior
  ;; alvo :transfer (Valorant) — PRIOR DE BAIXA CONFIANÇA (grill #9): multiplica
  ;; o custo (menor = mais prioritário). Micro-flicks/clique >> tracking puro
  ;; (TTK baixo do Valorant premia o primeiro tiro, não o acompanhamento).
  {:flick-tech/micro 0.7 :flick-tech/post-flick 0.75 :flick-tech/stability 0.8
   :click-timing/precision 0.8 :click-timing/reading 0.85 :click-timing/stability 0.85
   :reactive-tracking/control 0.85 :reactive-tracking/reading 0.9
   :flick-tech/speed 0.85 :reactive-tracking/speed 0.95
   :control-tracking/fingertip 0.9 :control-tracking/wrist 1.1
   :control-tracking/arm 1.2 :control-tracking/blending 1.1})

(defn- grind-actions
  "Uma ação por (cenário-drill × skill que ele treina). :post = a skill fica ok.
  Custo = minutos × resistência (skills mais 'lentas' de treinar custam mais)
  × prior de transferência quando :game-transfer."
  [catalog profile skills]
  (for [entry catalog
        skill (or (:drill/for entry)
                  ;; sem drill/for explícito: cenário treina sua skill dominante
                  [(key (apply max-key val (:skills entry)))])
        :let [w (get (:skills entry) skill 0.3)
              resist ({:control-tracking/arm 1.4 :control-tracking/blending 1.5
                       :control-tracking/fingertip 1.3} skill 1.0)
              transfer (if (= :transfer (:player/game-target profile))
                         (transfer-prior skill 1.0) 1.0)
              ;; objetivo em linguagem natural (CONTEXT.md): categorias de foco
              ;; ficam mais baratas — o A* prefere drills alinhados ao objetivo
              foco (if (some #{(namespace skill)}
                             (map str (:player/focus-categories profile)))
                     0.8 1.0)
              cur (get-in skills [skill :value])]
        ;; não prescreve grind de skill já ok nem sem evidência (honestidade)
        :when (and cur (< cur skill-gate) (>= w 0.3))]
    {:name (str "grind:" (name (:id entry)) "->" (name skill))
     :pre  #{}
     :post #{(ok-cond skill)}
     :cost (* session-min resist transfer foco (/ 1.0 w))
     :meta {:scenario (:id entry) :skill skill :minutes session-min
            :expected-delta (format "%.0f->%.0f (proj. 2 semanas)" cur (min 100.0 (+ cur 8)))}}))

(defn- sens-actions
  "Só nas políticas :range e :search: mudar sens é ação cara (dias de
  re-adaptação) que destrava skills de precisão OU de amplitude, conforme a
  direção. Política :fixed = a ação nem existe (ADR 0005)."
  [profile]
  (when (#{:range :search} (:player/sens-policy profile))
    [{:name "sens:diminuir-10pct" :pre #{}
      :post #{(ok-cond :flick-tech/micro) (ok-cond :control-tracking/fingertip)}
      :cost 120.0
      :meta {:tipo :sens-change :direcao :down :nota "re-adaptação ~1 semana; dentro do range do perfil"}}
     {:name "sens:aumentar-10pct" :pre #{}
      :post #{(ok-cond :reactive-tracking/speed)}
      :cost 120.0
      :meta {:tipo :sens-change :direcao :up :nota "re-adaptação ~1 semana; dentro do range do perfil"}}]))

(defn goal-for-scenario
  "Goal GOAP: todas as skills relevantes do cenário-alvo 'ok'. Só skills COM
  evidência e abaixo do gate entram no goal (as demais já satisfeitas)."
  [entry skills]
  {:name (str "destravar:" (name (:id entry)))
   :pre  (set (keep (fn [[skill w]]
                      (when (and (>= w 0.2)
                                 (some-> (get-in skills [skill :value]) (< skill-gate)))
                        (ok-cond skill)))
                    (:skills entry)))})

(defn build-plan
  "Monta o plano p/ destravar `target-entry` dado o estado de skills e perfil.
  Devolve {:status :ok :steps [{:scenario :skill :minutes :expected-delta}] ...}
  ou {:status :ja-destravado} / {:status :no-plan}."
  [catalog profile skills target-entry]
  (let [profile (profile/normalize profile)
        state0  (set (keep (fn [[skill {v :value}]]
                             (when (and v (>= v skill-gate)) (ok-cond skill)))
                           skills))
        goal    (goal-for-scenario target-entry skills)
        actions (vec (concat (grind-actions catalog profile skills)
                             (sens-actions profile)))]
    (if (empty? (:pre goal))
      {:status :ja-destravado :target (:id target-entry)}
      (let [r (goap/plan actions state0 goal)]
        (if (= :ok (:status r))
          {:status :ok
           :target (:id target-entry)
           :goal   (vec (:pre goal))
           :cost-min (:cost r)
           :steps  (mapv :meta (:steps r))
           :previsao {:gerada-em (str (java.time.Instant/now))
                      :horizonte "2 semanas"
                      :criterio  (str "residual do alvo deve subir; conferir com 'outcome'")}}
          {:status :no-plan :target (:id target-entry) :detalhe r})))))
