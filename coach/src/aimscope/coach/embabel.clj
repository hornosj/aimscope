(ns aimscope.coach.embabel
  "Adapter Clojure -> Embabel (GOAP 0.4.0). CÓPIA do beautiful-linkedin
  (agents/embabel.clj), decisão #10 do grill: os repos evoluem livres até o
  shape estar provado nos dois domínios. O interop de BAIXO NÍVEL (proxy
  AbstractAction/Goal) mora AQUI, escondido do código de domínio.

  `build-agent` é o LEITOR DE TAGS: varre as vars de um namespace, lê a
  metadata (:action/pre :action/post :action/cost :action/rerun :action/llm)
  e monta o Agent. O domínio (insight_agent) vira só `defn` + tags, sem proxy."
  (:import [com.embabel.agent.core.support AbstractAction]
           [com.embabel.agent.core ProcessContext ActionStatus ActionStatusCode
            ActionQos Goal Export Agent]
           [com.embabel.agent.api.common TransformationActionContext]
           [java.time Duration]))

;; O Embabel/Kotlin espera Function1<WorldState,Double> p/ cost e value.
(defn- ^kotlin.jvm.functions.Function1 const-fn [v]
  (reify kotlin.jvm.functions.Function1
    (invoke [_ _world-state] (Double/valueOf (double v)))))

(defn- default-qos ^ActionQos []
  ;; (retries, retryIntervalMs, backoffMultiplier, maxIntervalMs, jitter)
  (ActionQos. (int 5) (long 10000) (double 5.0) (long 60000) false))

(defn- goal ^Goal [name description pre value]
  (Goal. name description (set pre) #{} nil (const-fn value) #{} #{}
         (Export. nil false true #{})))

;; ===========================================================================
;; REGISTRAR — o "AgentMetadataReader" do lado Clojure (lê as TAGS das vars)
;; ===========================================================================

(defn- ->action
  "Constrói uma AbstractAction a partir de um mapa-spec. `body` recebe [oc pc]
  se :llm?, senão [pc]. `:after` (opcional) roda depois do corpo."
  ^AbstractAction [{:keys [name pre post cost rerun? llm? body after]}]
  (proxy [AbstractAction]
         [name name (vec pre) (vec post)
          (const-fn (double cost)) (const-fn 0.0)
          #{} #{} #{} (boolean rerun?) false false (default-qos)]
    (execute [^ProcessContext pc]
      (if llm?
        (body (TransformationActionContext. nil pc this Object Object) pc)
        (body pc))
      (when after (after pc))
      (ActionStatus. (Duration/ofMillis 0) ActionStatusCode/SUCCEEDED))
    (referencedInputProperties [_] #{})))

(defn- action-var? [v] (some #(= "action" (namespace %)) (keys (meta v))))

(defn- tagged-action ^AbstractAction [v after]
  (let [m (meta v)]
    (->action {:name   (clojure.core/name (:name m))
               :pre    (vec (:action/pre m []))
               :post   (vec (:action/post m []))
               :cost   (double (:action/cost m 1.0))
               :rerun? (boolean (:action/rerun m false))
               :llm?   (boolean (:action/llm m false))
               :body   @v
               :after  after})))

(defn build-agent
  "Monta o com.embabel.agent.core.Agent lendo as TAGS das vars de `:ns`: cada
  var com metadata :action/* vira uma ação (nome = nome da var). `:after` roda
  após CADA ação. `:goals` são dados puros [{:name :pre :value :description}].
  Ordem determinística (por nome); o A* é order-independent p/ o plano ótimo.
  opts: {:ns :name :provider :version :description :after :goals}."
  ^Agent [{:keys [ns name provider version description after goals]}]
  (let [actions (->> (ns-interns ns) vals
                     (filter action-var?)
                     (sort-by (comp str :name meta))
                     (mapv #(tagged-action % after)))
        gs (mapv (fn [g] (goal (:name g) (:description g) (vec (:pre g)) (double (:value g)))) goals)]
    (Agent. name provider version description (set gs) (vec actions))))
