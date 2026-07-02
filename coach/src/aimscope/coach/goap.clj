(ns aimscope.coach.goap
  "Planner GOAP em Clojure puro — CONTRATO-COMPATIVEL com o adapter embabel do
  beautiful-linkedin (agents/embabel.clj): acoes sao vars com metadata
  :action/pre :action/post :action/cost, goals sao dados puros
  [{:name :pre :value}], e `build-agent` varre um namespace pelas tags.

  Diferenca deliberada (ADR 0002, adendo): o ENGINE aqui e um A* proprio sem
  Spring/AgentPlatform — o coach roda em batch, sem LLM, e nao paga o boot do
  Spring Boot. Se um dia quisermos o embabel real, o dominio nao muda: troca-se
  este ns pelo adapter e adiciona-se o pom.

  Semantica:
  - Estado do mundo = mapa de condicao->boolean (polos POSITIVOS, nomes com '/'
    e '?', nunca ':' — regra de ouro herdada do spec do beautiful-linkedin).
  - :action/pre  = condicoes que precisam estar true para a acao ser aplicavel.
  - :action/post = condicoes que ficam true apos a acao (efeito declarado).
  - :action/cost = custo (double) — o A* minimiza custo total.
  - Goal :pre    = condicoes que definem o goal satisfeito.
  - `body` NAO roda durante o planejamento: o plano e uma SEQUENCIA DE NOMES.
    Quem executa (ou apresenta ao usuario) decide quando rodar os bodies.
  Fila de prioridade via sorted-set de [f g state path] — sem dependencia extra.")

;; ---------------------------------------------------------------------------
;; leitura de tags (espelho do build-agent do embabel.clj)
;; ---------------------------------------------------------------------------

(defn action-var? [v]
  (some #(= "action" (namespace %)) (keys (meta v))))

(defn var->action
  "Extrai o spec de acao da metadata de uma var (mesmas tags do embabel.clj)."
  [v]
  (let [m (meta v)]
    {:name  (name (:name m))
     :pre   (set (:action/pre m []))
     :post  (set (:action/post m []))
     :cost  (double (:action/cost m 1.0))
     :body  @v
     :doc   (:doc m)}))

(defn scan-actions
  "Varre as vars de um namespace e devolve as acoes tagueadas, ordenadas por
  nome (determinismo; o A* e order-independent p/ o plano otimo)."
  [ns-sym]
  (require ns-sym)
  (->> (ns-interns ns-sym) vals
       (filter action-var?)
       (map var->action)
       (sort-by :name)
       vec))

;; ---------------------------------------------------------------------------
;; A* sobre estados-conjunto de condicoes
;; ---------------------------------------------------------------------------

(defn- applicable? [state action]
  (every? #(contains? state %) (:pre action)))

(defn- apply-action [state action]
  (into state (:post action)))

(defn- satisfied? [state goal]
  (every? #(contains? state %) (:pre goal)))

(defn- heuristic
  "Admissivel: nº de condicoes do goal ainda ausentes × custo minimo de acao."
  [state goal min-cost]
  (* min-cost (count (remove #(contains? state %) (:pre goal)))))

(defn plan
  "A* do estado inicial ate o goal. `state0` = set/coll de condicoes true.
  Devolve {:status :ok :plan [<nomes>] :cost x :steps [<actions>]} ou
  {:status :no-plan}. `opts`: {:max-expansions n (default 50000)}."
  ([actions state0 goal] (plan actions state0 goal {}))
  ([actions state0 goal {:keys [max-expansions] :or {max-expansions 50000}}]
   (let [state0   (set state0)
         goal     {:pre (set (:pre goal)) :name (:name goal "goal")}
         min-cost (transduce (map :cost) min Double/MAX_VALUE actions)
         min-cost (if (= min-cost Double/MAX_VALUE) 1.0 (max min-cost 0.001))]
     (if (satisfied? state0 goal)
       {:status :ok :plan [] :cost 0.0 :steps []}
       ;; nós = [f g id state path]; o id monotônico desempata ANTES de o
       ;; sorted-set tentar comparar `state` (sets não são Comparable)
       (loop [open    (sorted-set [(heuristic state0 goal min-cost) 0.0 0 state0 []])
              seen    {state0 0.0}
              next-id 1
              expans  0]
         (cond
           (empty? open) {:status :no-plan :expansions expans}
           (> expans max-expansions) {:status :no-plan :reason :budget :expansions expans}
           :else
           (let [[_f g _id state path :as node] (first open)
                 open (disj open node)]
             (if (satisfied? state goal)
               {:status :ok
                :plan   (mapv :name path)
                :steps  (vec path)
                :cost   g
                :expansions expans}
               (let [succs (vec (for [a actions
                                      :when (applicable? state a)
                                      :let  [s' (apply-action state a)
                                             g' (+ g (:cost a))]
                                      ;; poda: efeito nulo ou caminho pior conhecido
                                      :when (and (not= s' state)
                                                 (< g' (get seen s' Double/MAX_VALUE)))]
                                  [s' g' a]))]
                 (recur (into open (map-indexed
                                    (fn [i [s' g' a]]
                                      [(+ g' (heuristic s' goal min-cost)) g'
                                       (+ next-id i) s' (conj path a)])
                                    succs))
                        (into seen (map (fn [[s' g' _]] [s' g']) succs))
                        (+ next-id (count succs))
                        (inc expans)))))))))))

(defn build-agent
  "Espelho do build-agent do embabel.clj, sem JVM interop: le as tags do ns e
  devolve {:actions [...] :goals [...]} pronto p/ `plan`.
  opts: {:ns <sym> :goals [{:name :pre :value :description}]}."
  [{:keys [ns goals]}]
  {:actions (scan-actions ns)
   :goals   (mapv #(update % :pre set) goals)})
