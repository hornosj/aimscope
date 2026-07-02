(ns aimscope.coach.insight-agent
  "Agente Embabel de NARRATIVA (GOAP + LLM via OpenRouter) — o padrão do
  email_hunter_v2, aplicado a coaching de mira:

    carregar-dados (det, barato)
        └─> narrar-llm (LLM, custo 1) ── falhou? seta narrativa/llm-falhou?
        └─> narrar-fallback (det, custo 5, gateado pelo polo de falha)
        └─> salvar (det) -> GOAL narrativa/salva?

  O A* escolhe o LLM primeiro (barato); se a chamada falha (sem chave, 429,
  free-tier caído), o corpo NÃO seta narrativa/pronta? e seta o polo
  narrativa/llm-falhou? -> replanejamento pega o fallback determinístico.
  LLM é ADITIVO, nunca ponto único de falha — degradação graciosa herdada.

  Regra de ouro (spec do beautiful-linkedin §3): nomes de condition/goal são
  STRINGS SEM SIGNIFICADO p/ o planner; polos POSITIVOS; '/' e '?', nunca ':'.

  Entrada: os JSONs que o coach determinístico (deps.edn) já emite em
  %LOCALAPPDATA%/aimscope/coach/. Saída: narrative.md no mesmo diretório."
  (:require [aimscope.coach.embabel :as a]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.embabel.agent.core AgentPlatform ProcessContext ProcessOptions]))

(def default-model "openai/gpt-oss-120b:free")

;; ---- blackboard helpers (mesmos contratos do beautiful-linkedin) -----------

(defn- bb [^ProcessContext pc] (.getBlackboard pc))
(defn- g  [pc k d] (let [v (.get (bb pc) k)] (if (or (nil? v) (= v :none)) d v)))
(defn- s! [pc k v] (.set (bb pc) k (if (nil? v) :none v)))
(defn- c! [pc m] (doseq [[k v] m] (.setCondition (bb pc) k (boolean v))))

(defn- log! [pc & xs]
  (.println System/err (str "[insight] " (apply print-str xs))))

;; ---- LLM: chamada + guarda defensiva ---------------------------------------

(defn- ask [oc model prompt]
  (try (-> (.ai oc) (.withLlm ^String model) (.generateText ^String prompt))
       (catch Throwable t
         (.println System/err (str "[insight] LLM falhou: " (.getMessage t)))
         nil)))

;; ---- dados ------------------------------------------------------------------

(defn- coach-dir ^java.io.File []
  (io/file (System/getenv "LOCALAPPDATA") "aimscope" "coach"))

(defn- read-json [name*]
  (let [f (io/file (coach-dir) name*)]
    (when (.exists f) (json/parse-string (slurp f) true))))

(defn- resumo-dados []
  (let [d (read-json "diagnosis.json")
        p (read-json "plan.json")
        o (read-json "outcome.json")]
    {:diagnosis (when d (select-keys d [:skills/ranked :skills/sem-evidencia
                                        :gargalo-global :cenarios-subperformando
                                        :n-scores :n-sessions]))
     :plan      (when p (select-keys p [:status :target :goal :steps :cost-min]))
     :outcome   (when o (select-keys o [:veredito-geral :por-skill :recomendacao]))}))

;; ===========================================================================
;; AÇÕES (tags lidas pelo build-agent)
;; ===========================================================================

(defn ^{:action/post ["dados/carregados?"]
        :action/cost 0.3}
  carregar-dados
  "Lê diagnosis/plan/outcome.json p/ o blackboard. Sem dados = ainda carrega
  (a narrativa dirá 'grave sessões primeiro' — nunca quebra)."
  [pc]
  (let [r (resumo-dados)]
    (log! pc "dados:" (boolean (:diagnosis r)) (boolean (:plan r)) (boolean (:outcome r)))
    (s! pc "coach/dados" r)
    (c! pc {"dados/carregados?" true})))

(defn ^{:action/pre  ["dados/carregados?"]
        :action/post ["narrativa/pronta?"]
        :action/cost 1.0
        :action/llm  true}
  narrar-llm
  "Narrativa de coach via OpenRouter. Falha -> polo narrativa/llm-falhou?
  (replanejamento pega o fallback). Sucesso -> narrativa/pronta?."
  [oc pc]
  (let [dados (g pc "coach/dados" {})
        model (g pc "coach/model" default-model)
        prompt (str
                "Você é um coach de aim training brutalmente honesto e técnico "
                "(estilo Voltaic), escrevendo em português brasileiro para UM aluno.\n"
                "Dados medidos do aluno (JSON; skills 0-100, 50=neutro; "
                "'gargalo-global' = pior skill; plano gerado por GOAP; "
                "'outcome' = previsto×realizado do plano anterior):\n\n"
                (json/generate-string dados {:pretty true})
                "\n\nEscreva em markdown, no MÁXIMO 350 palavras, com EXATAMENTE "
                "estas seções:\n"
                "## Diagnóstico\n(2-3 frases: o gargalo e o que os números dizem — "
                "cite os valores)\n"
                "## O plano e o porquê\n(explique POR QUE cada cenário do plano "
                "ataca o gargalo)\n"
                "## Como executar\n(dicas de execução concretas por drill: grip, "
                "velocidade, intenção)\n"
                "## Sinal de alerta\n(1 frase: o que indicaria que o plano não está "
                "funcionando)\n\n"
                "Regras: NÃO invente números que não estão nos dados; se um campo "
                "estiver vazio/null, diga 'sem dados ainda' em vez de inventar; "
                "NUNCA sugira mudar sensibilidade (o perfil do aluno decide isso, "
                "não você); skills marcadas 'sem-evidencia' não existem para você.")
        out (ask oc model prompt)]
    (if (and out (> (count (str/trim out)) 100))
      (do (s! pc "coach/narrativa" (str/trim out))
          (s! pc "coach/fonte" "llm")
          (c! pc {"narrativa/pronta?" true}))
      (do (log! pc "LLM sem resposta útil — acionando polo de falha")
          (c! pc {"narrativa/llm-falhou?" true})))))

(defn ^{:action/pre  ["dados/carregados?" "narrativa/llm-falhou?"]
        :action/post ["narrativa/pronta?"]
        :action/cost 5.0}
  narrar-fallback
  "Narrativa determinística (template) — LLM é aditivo, nunca obrigatório."
  [pc]
  (let [{:keys [diagnosis plan outcome]} (g pc "coach/dados" {})
        gargalo (get-in diagnosis [:gargalo-global :skill])
        valor   (get-in diagnosis [:gargalo-global :value])
        steps   (get plan :steps [])
        md (str "## Diagnóstico\n"
                (if gargalo
                  (format "Seu gargalo atual é **%s** (%.0f/100). As demais skills seguem no relatório.\n"
                          (str gargalo) (double (or valor 50.0)))
                  "Sem dados suficientes ainda — grave sessões de treino com a captura ligada.\n")
                "\n## O plano e o porquê\n"
                (if (seq steps)
                  (str/join "\n" (map #(format "- **%s min de %s** → treina %s (%s)"
                                               (str (:minutes %)) (str (:scenario %))
                                               (str (:skill %)) (str (:expected-delta %)))
                                      steps))
                  "Sem plano ativo — rode o coach após a próxima sessão.")
                "\n\n## Como executar\nPriorize precisão sobre velocidade; a velocidade vem da precisão consolidada.\n"
                "\n## Sinal de alerta\nSe após 2 semanas o `outcome` acusar platô, replaneje — o GOAP re-roteia sozinho.\n"
                (when outcome
                  (str "\n---\n_Previsto×realizado do último plano: "
                       (str (:veredito-geral outcome)) "_\n")))]
    (s! pc "coach/narrativa" md)
    (s! pc "coach/fonte" "fallback")
    (c! pc {"narrativa/pronta?" true})))

(defn ^{:action/pre  ["narrativa/pronta?"]
        :action/post ["narrativa/salva?"]
        :action/cost 0.3}
  salvar
  "Persiste narrative.md (+ .json com metadados) no diretório do coach."
  [pc]
  (let [md   (g pc "coach/narrativa" "")
        f    (io/file (coach-dir) "narrative.md")]
    (io/make-parents f)
    (spit f md)
    (spit (io/file (coach-dir) "narrative.json")
          (json/generate-string {:fonte (g pc "coach/fonte" "?")
                                 :model (g pc "coach/model" default-model)
                                 :chars (count md)}))
    (s! pc "coach/narrativa-path" (str f))
    (log! pc "salvo em" (str f) "| fonte:" (g pc "coach/fonte" "?"))
    (c! pc {"narrativa/salva?" true})))

;; ===========================================================================
;; agente + disparo
;; ===========================================================================

(defn insight-agent []
  (a/build-agent
   {:ns 'aimscope.coach.insight-agent
    :name "aimscope-insight"
    :provider "aimscope"
    :version "0.1.0"
    :description "Narrativa de coaching (GOAP+LLM) sobre diagnóstico/plano/outcome"
    :goals [{:name "narrativa-entregue"
             :description "narrative.md escrito no diretório do coach"
             :pre ["narrativa/salva?"]
             :value 1.0}]}))

(defn narrate!
  "Deploya e roda o agente UMA vez. Modelo via env AIMSCOPE_LLM (default free)."
  [^AgentPlatform platform]
  (let [ag (insight-agent)]
    (.deploy platform ag)
    (let [bindings {"coach/model" (or (System/getenv "AIMSCOPE_LLM") default-model)}
          proc (.runAgentFrom platform ag (ProcessOptions.) bindings)
          path (.get (.getBlackboard proc) "coach/narrativa-path")]
      (println (str "status=" (.getStatus proc) " narrativa=" path))
      path)))
