(ns aimscope.coach.objective
  "Objetivo em linguagem natural -> mapa estruturado (CONTEXT.md: Objetivo).
  Ns PURO (testável no deps.edn): validação do que o LLM devolve e o parser
  determinístico por regras — o LLM é intérprete com gate, nunca autoridade.
  O agente embabel (objective_agent) só faz a fiação GOAP."
  (:require [aimscope.coach.labels :as labels]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def modos #{:fixed-sens :sens-range :game-transfer})
(def jogos #{:valorant :cs2 :overwatch :apex :geral})
(def categorias #{"control-tracking" "reactive-tracking" "flick-tech" "click-timing"})

(defn valida
  "Sanitiza o mapa vindo do LLM (strings soltas). nil se inaproveitável —
  o chamador cai no parser determinístico."
  [m]
  (when (map? m)
    (let [modo  (keyword (or (:goal-mode m) (get m "goal-mode")))
          jogo  (keyword (or (:game m) (get m "game") "geral"))
          foco  (->> (or (:focus m) (get m "focus") [])
                     (map str)
                     (filter categorias)
                     distinct vec)
          resumo (str (or (:resumo m) (get m "resumo") ""))]
      (when (contains? modos modo)
        {:goal-mode modo
         :game (if (contains? jogos jogo) jogo :geral)
         :focus foco
         :resumo (subs resumo 0 (min 200 (count resumo)))}))))

(defn parse-deterministico
  "Regras sobre o texto cru — o fallback que SEMPRE produz um mapa válido."
  [texto]
  (let [t (str/lower-case (str texto))
        jogo (cond (re-find #"valorant|valo\b" t)      :valorant
                   (re-find #"\bcs2?\b|counter.?strike" t) :cs2
                   (re-find #"overwatch|\bow\b" t)     :overwatch
                   (re-find #"apex" t)                 :apex
                   :else                               :geral)
        modo (cond (re-find #"sem (trocar|mudar).{0,12}sens|manter.{0,8}sens|minha sens" t) :fixed-sens
                   (re-find #"(trocar|mudar|testar).{0,12}sens|sens livre" t)               :sens-range
                   (not= jogo :geral)                                                       :game-transfer
                   :else                                                                    :fixed-sens)
        foco (cond-> []
               (re-find #"flick|spray|estalo" t)               (conj "flick-tech")
               (re-find #"track|acompanha|seguir" t)           (conj "control-tracking")
               (re-find #"reativ|imprevis|strafe" t)           (conj "reactive-tracking")
               (re-find #"cli(c|que)|timing|tiro|acert" t)     (conj "click-timing"))]
    {:goal-mode modo
     :game jogo
     :focus (vec (distinct foco))
     :resumo (subs (str texto) 0 (min 200 (count (str texto))))}))

(defn resumo-humano
  "Frase curta pro usuário conferir a interpretação (labels, zero jargão)."
  [{:keys [goal-mode game focus]}]
  (str (case goal-mode
         :fixed-sens    "Dominar a sua sens atual"
         :sens-range    "Melhorar overall (sens livre)"
         :game-transfer "Transferir o treino pro jogo"
         "Objetivo")
       (when (and game (not= game :geral)) (str " · jogo: " (name game)))
       (if (seq focus)
         (str " · foco: " (str/join ", " (map #(labels/categoria-labels % %) focus)))
         " · foco: geral")))

(defn objective->profile-patch
  "O que o objetivo interpretado sobrepõe no perfil (CONTEXT.md)."
  [{:keys [goal-mode focus game]}]
  {:player/goal goal-mode
   :player/focus-categories focus
   :player/game game})

;; ---------------------------------------------------------------------------
;; persistência (mesmo diretório do profile.json)
;; ---------------------------------------------------------------------------

(defn json-path [] (io/file (System/getenv "LOCALAPPDATA") "aimscope" "objective.json"))

(defn save!
  [texto mapa fonte]
  (let [f (json-path)]
    (io/make-parents f)
    (spit f (json/generate-string
             {:texto texto
              :mapa (update mapa :goal-mode name)
              :resumo-humano (resumo-humano mapa)
              :fonte fonte}
             {:pretty true}))
    (str f)))

(defn load-objective
  "Mapa validado do objective.json, ou nil se ausente/corrompido."
  []
  (let [f (json-path)]
    (when (.exists f)
      (try (valida (:mapa (json/parse-string (slurp f) true)))
           (catch Exception _ nil)))))
