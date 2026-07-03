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
            [aimscope.coach.narrative :as nar]
            [aimscope.coach.profile :as profile]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.embabel.agent.core AgentPlatform ProcessContext ProcessOptions]))

;; Cadeia de fallback (NVIDIA build; ver openai-models.yml). Topo = z-ai/glm-5.2
;; (padrao pedido pelo JP 2026-07-02; sem tools/structured output, mas os
;; consumidores daqui sao texto puro com validacao propria). Atras dele, a
;; ordem por LATENCIA de 1o token medida ao vivo (streaming, free endpoint):
;;   minimax-m3 ~2s · qwen3.5-397b ~2.2s · kimi-k2.6 ~3.2s ·
;;   v4-flash ~7.5s · v4-pro timeout>90s (o mais forte, mas no fim por latencia)
;; Qualquer falha (timeout, jargao, estrutura) passa pro proximo; esgotar a
;; cadeia cai no texto deterministico.
(def model-chain
  ["z-ai/glm-5.2"
   "minimaxai/minimax-m3"
   "qwen/qwen3.5-397b-a17b"
   "moonshotai/kimi-k2.6"
   "deepseek-ai/deepseek-v4-flash"
   "deepseek-ai/deepseek-v4-pro"])

;; usado por narrate! como binding do blackboard e por chamadas single-model.
(def default-model (first model-chain))

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

(defn- read-json-raiz
  "JSON no diretório PAI do coach (%LOCALAPPDATA%/aimscope) — objective.json
  mora lá, junto do profile.json."
  [name*]
  (let [f (io/file (.getParentFile (coach-dir)) name*)]
    (when (.exists f) (json/parse-string (slurp f) true))))

(defn- resumo-dados []
  (let [d (read-json "diagnosis.json")
        p (read-json "plan.json")
        o (read-json "outcome.json")
        b (read-json "benchmarks.json")]
    {:diagnosis (when d (select-keys d [:skills/ranked :skills/sem-evidencia
                                        :gargalo-global :cenarios-subperformando
                                        :placement :n-scores :n-sessions]))
     :plan      (when p (select-keys p [:status :target :target-label :goal
                                        :steps :cost-min]))
     :outcome   (when o (select-keys o [:veredito-geral :por-skill :recomendacao]))
     ;; abertura pessoal do briefing (CONTEXT.md: Briefing)
     :profile   (select-keys (profile/load-profile) [:player/name])
     :objective (some-> (read-json-raiz "objective.json")
                        (select-keys [:resumo-humano]))
     :benchmarks (:benchmarks b)}))

;; Conteúdo determinístico + validação vivem em aimscope.coach.narrative
;; (ns puro, testável no deps.edn — este ns só faz a fiação GOAP/embabel).

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
  "LLM como POLIDOR do rascunho determinístico (nunca autor): recebe o texto
  pronto e só melhora a prosa. Percorre a CADEIA de modelos por latência
  (model-chain) até um produzir saída VÁLIDA (narrativa-valida?: jargão,
  seção faltando ou tamanho errado REPROVAM). Esgotar a cadeia aciona o polo
  de falha -> fluxo determinístico (QA 2026-07-02: anti-alucinação, e nunca
  refém de um provider). env AIMSCOPE_LLM força um único modelo (curto-circuita
  a cadeia — útil pra depurar)."
  [oc pc]
  (let [dados (g pc "coach/dados" {})
        forced (g pc "coach/model" nil)
        chain (if forced [forced] model-chain)
        rascunho (nar/narrativa-deterministica dados)
        prompt (str
                "Você é um coach de aim training experiente, direto e encorajador, "
                "escrevendo em português brasileiro para UM aluno leigo.\n\n"
                "Abaixo está o texto TÉCNICO CORRETO do diagnóstico dele. Reescreva "
                "APENAS a prosa para soar mais natural e motivadora.\n\n"
                "REGRAS INEGOCIÁVEIS:\n"
                "- Mantenha EXATAMENTE os mesmos títulos de seção (## ...).\n"
                "- Mantenha TODOS os números exatamente como estão; não invente nenhum.\n"
                "- Não use NENHUM identificador técnico (nada contendo '/'). Os nomes "
                "amigáveis já estão no texto — use só eles.\n"
                "- Não sugira mudar sensibilidade.\n"
                "- Não adicione nem remova cenários, minutos ou seções.\n"
                "- Máximo 400 palavras.\n\n"
                "TEXTO:\n\n" rascunho)
        [modelo texto]
        (some (fn [m]
                (let [out (some-> (ask oc m prompt) str/trim)]
                  (if (nar/narrativa-valida? out)
                    [m out]
                    (do (log! pc "modelo" m (if out "reprovou (jargão/estrutura)"
                                                "sem resposta") "— próximo da cadeia")
                        nil))))
              chain)]
    (if texto
      (do (log! pc "narrativa via" modelo)
          (s! pc "coach/narrativa" texto)
          (s! pc "coach/fonte" (str "llm-polido:" modelo))
          (c! pc {"narrativa/pronta?" true}))
      (do (log! pc "cadeia esgotada — usando determinístico")
          (c! pc {"narrativa/llm-falhou?" true})))))

(defn ^{:action/pre  ["dados/carregados?" "narrativa/llm-falhou?"]
        :action/post ["narrativa/pronta?"]
        :action/cost 5.0}
  narrar-fallback
  "Fluxo determinístico: o rascunho canônico VIRA a narrativa. Não é um
  'fallback pobre' — é o mesmo conteúdo que o LLM poliria."
  [pc]
  (s! pc "coach/narrativa" (nar/narrativa-deterministica (g pc "coach/dados" {})))
  (s! pc "coach/fonte" "deterministica")
  (c! pc {"narrativa/pronta?" true}))

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
          ;; :fonte = "llm-polido:<modelo>" ou "deterministica" — carrega o
          ;; modelo REAL que a cadeia usou (o default nao diz nada útil)
          (json/generate-string {:fonte (g pc "coach/fonte" "?")
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
  "Deploya e roda o agente UMA vez. Sem env AIMSCOPE_LLM, percorre a cadeia
  inteira por latência (model-chain). Com AIMSCOPE_LLM setado, força esse único
  modelo (curto-circuita a cadeia — depuração)."
  [^AgentPlatform platform]
  (let [ag (insight-agent)]
    (.deploy platform ag)
    (let [forced (System/getenv "AIMSCOPE_LLM")
          bindings (if forced {"coach/model" forced} {})
          proc (.runAgentFrom platform ag (ProcessOptions.) bindings)
          path (.get (.getBlackboard proc) "coach/narrativa-path")]
      (println (str "status=" (.getStatus proc) " narrativa=" path))
      path)))
