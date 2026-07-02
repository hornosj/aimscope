(ns aimscope.coach.objective-agent
  "Agente Embabel do OBJETIVO (CONTEXT.md): texto livre do jogador -> mapa
  estruturado {goal-mode, game, focus, resumo}. Mesmo padrão do insight-agent:
  LLM tenta primeiro (cadeia por latência), validação dura (objective/valida),
  polo de falha cai no parser determinístico por regras — que SEMPRE produz um
  mapa válido. O texto do usuário nunca é executado, só interpretado."
  (:require [aimscope.coach.embabel :as a]
            [aimscope.coach.insight-agent :as ia]
            [aimscope.coach.objective :as obj]
            [aimscope.coach.profile :as profile]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [com.embabel.agent.core AgentPlatform ProcessOptions]))

(defn- bb [pc] (.getBlackboard pc))
(defn- g  [pc k d] (let [v (.get (bb pc) k)] (if (or (nil? v) (= v :none)) d v)))
(defn- s! [pc k v] (.set (bb pc) k (if (nil? v) :none v)))
(defn- c! [pc m] (doseq [[k v] m] (.setCondition (bb pc) k (boolean v))))
(defn- log! [pc & xs] (.println System/err (str "[objetivo] " (apply print-str xs))))

(defn- ask [oc model prompt]
  (try (-> (.ai oc) (.withLlm ^String model) (.generateText ^String prompt))
       (catch Throwable t
         (.println System/err (str "[objetivo] LLM falhou: " (.getMessage t)))
         nil)))

(defn- extrai-json
  "Modelos adoram cercar JSON com prosa/markdown; pega o primeiro objeto."
  [s]
  (when s
    (when-let [m (re-find #"(?s)\{.*\}" s)]
      (try (json/parse-string m true) (catch Exception _ nil)))))

;; ===========================================================================
;; ações
;; ===========================================================================

(defn ^{:action/post ["objetivo/texto-carregado?"]
        :action/cost 0.3}
  carregar-texto
  "Texto do objetivo: campo player/objective-text do profile.json (a UI grava lá)."
  [pc]
  (let [texto (:player/objective-text (profile/load-profile))]
    (log! pc "texto:" (boolean (seq (str texto))))
    (s! pc "objetivo/texto" (or texto ""))
    (c! pc {"objetivo/texto-carregado?" true})))

(defn ^{:action/pre  ["objetivo/texto-carregado?"]
        :action/post ["objetivo/interpretado?"]
        :action/cost 1.0
        :action/llm  true}
  interpretar-llm
  "LLM converte o texto em JSON estrito; objective/valida é o gate. Percorre a
  mesma cadeia de modelos por latência do insight-agent."
  [oc pc]
  (let [texto (g pc "objetivo/texto" "")
        forced (g pc "coach/model" nil)
        chain (if forced [forced] ia/model-chain)
        prompt (str
                "Converta o objetivo de treino de mira abaixo em JSON ESTRITO, "
                "sem nenhum texto fora do JSON.\n"
                "Campos:\n"
                "- \"goal-mode\": \"fixed-sens\" (não quer mexer na sensibilidade), "
                "\"sens-range\" (aceita trocar sens) ou \"game-transfer\" "
                "(o fim é melhorar num jogo específico).\n"
                "- \"game\": \"valorant\", \"cs2\", \"overwatch\", \"apex\" ou \"geral\".\n"
                "- \"focus\": lista com zero ou mais de: \"control-tracking\", "
                "\"reactive-tracking\", \"flick-tech\", \"click-timing\".\n"
                "- \"resumo\": 1 frase em pt-BR resumindo o objetivo.\n\n"
                "OBJETIVO DO JOGADOR:\n" texto)
        mapa (some (fn [m]
                     (if-let [v (obj/valida (extrai-json (ask oc m prompt)))]
                       (do (log! pc "interpretado via" m) v)
                       (do (log! pc "modelo" m "não produziu mapa válido — próximo") nil)))
                   chain)]
    (if mapa
      (do (s! pc "objetivo/mapa" mapa)
          (s! pc "objetivo/fonte" "llm")
          (c! pc {"objetivo/interpretado?" true}))
      (c! pc {"objetivo/llm-falhou?" true}))))

(defn ^{:action/pre  ["objetivo/texto-carregado?" "objetivo/llm-falhou?"]
        :action/post ["objetivo/interpretado?"]
        :action/cost 5.0}
  interpretar-fallback
  "Parser determinístico por regras — sempre produz um mapa válido."
  [pc]
  (s! pc "objetivo/mapa" (obj/parse-deterministico (g pc "objetivo/texto" "")))
  (s! pc "objetivo/fonte" "deterministico")
  (c! pc {"objetivo/interpretado?" true}))

(defn ^{:action/pre  ["objetivo/interpretado?"]
        :action/post ["objetivo/salvo?"]
        :action/cost 0.3}
  salvar
  "objective.json + patch no profile.json (modo/foco/jogo passam a valer)."
  [pc]
  (let [mapa (g pc "objetivo/mapa" nil)
        texto (g pc "objetivo/texto" "")
        path (obj/save! texto mapa (g pc "objetivo/fonte" "?"))]
    (profile/save! (merge (profile/load-profile)
                          (obj/objective->profile-patch mapa)))
    (s! pc "objetivo/path" path)
    (log! pc "salvo em" path "| " (obj/resumo-humano mapa))
    (c! pc {"objetivo/salvo?" true})))

;; ===========================================================================
;; agente + disparo
;; ===========================================================================

(defn objective-agent []
  (a/build-agent
   {:ns 'aimscope.coach.objective-agent
    :name "aimscope-objective"
    :provider "aimscope"
    :version "0.1.0"
    :description "Interpreta o objetivo em linguagem natural do jogador"
    :goals [{:name "objetivo-entregue"
             :description "objective.json escrito e perfil atualizado"
             :pre ["objetivo/salvo?"]
             :value 1.0}]}))

(defn interpret!
  "Deploya e roda o agente UMA vez (mesma semântica do insight-agent/narrate!)."
  [^AgentPlatform platform]
  (let [ag (objective-agent)]
    (.deploy platform ag)
    (let [forced (System/getenv "AIMSCOPE_LLM")
          bindings (if forced {"coach/model" forced} {})
          proc (.runAgentFrom platform ag (ProcessOptions.) bindings)
          path (.get (.getBlackboard proc) "objetivo/path")]
      (println (str "status=" (.getStatus proc) " objetivo=" path))
      path)))
