(ns aimscope.coach.objective
  "Objetivo em linguagem natural -> mapa estruturado (CONTEXT.md: Objetivo).
  Ns PURO (testável no deps.edn): validação do que o LLM devolve e o parser
  determinístico por regras — o LLM é intérprete com gate, nunca autoridade.
  O agente embabel (objective_agent) só faz a fiação GOAP."
  (:require [aimscope.coach.labels :as labels]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ADR 0005: o objetivo é interpretado em três eixos ORTOGONAIS.
(def sens-policies #{:fixed :range :search})
(def jogos #{:valorant :cs2 :overwatch :apex :geral})
(def categorias #{"control-tracking" "reactive-tracking" "flick-tech" "click-timing"})

(def ^:private legacy-mode->policy
  ;; aceita saída de LLM/arquivo antigos (campo goal-mode) sem reprovar
  {:fixed-sens :fixed :sens-range :range :game-transfer :fixed})

(defn valida
  "Sanitiza o mapa vindo do LLM (strings soltas). nil se inaproveitável —
  o chamador cai no parser determinístico. Devolve os três eixos."
  [m]
  (when (map? m)
    (let [policy* (keyword (or (:sens-policy m) (get m "sens-policy")))
          legado  (some-> (or (:goal-mode m) (get m "goal-mode"))
                          keyword legacy-mode->policy)
          policy  (if (contains? sens-policies policy*) policy* legado)
          jogo    (keyword (or (:game m) (get m "game") "geral"))
          jogo    (if (contains? jogos jogo) jogo :geral)
          foco    (->> (or (:focus m) (get m "focus") [])
                       (map str)
                       (filter categorias)
                       distinct vec)
          resumo  (str (or (:resumo m) (get m "resumo") ""))]
      (when policy
        {:sens-policy policy
         :game jogo
         :game-target (if (= :geral jogo) :kovaaks :transfer)
         :focus foco
         :resumo (subs resumo 0 (min 200 (count resumo)))}))))

(defn parse-deterministico
  "Regras sobre o texto cru — o fallback que SEMPRE produz um mapa válido.
  Eixos ortogonais: 'Valorant sem trocar de sens' = :fixed + :transfer."
  [texto]
  (let [t (str/lower-case (str texto))
        jogo (cond (re-find #"valorant|valo\b" t)      :valorant
                   (re-find #"\bcs2?\b|counter.?strike" t) :cs2
                   (re-find #"overwatch|\bow\b" t)     :overwatch
                   (re-find #"apex" t)                 :apex
                   :else                               :geral)
        policy (cond (re-find #"(melhor|ideal|perfeita) sens|sens (ideal|perfeita)|(procur|achar|encontrar|calibrar).{0,12}sens" t) :search
                     (re-find #"sem (trocar|mudar).{0,12}sens|manter.{0,8}sens|minha sens" t) :fixed
                     (re-find #"(trocar|mudar|testar|experimentar).{0,12}sens|sens livre|range de sens" t) :range
                     :else :fixed)
        foco (cond-> []
               (re-find #"flick|spray|estalo" t)               (conj "flick-tech")
               (re-find #"track|acompanha|seguir" t)           (conj "control-tracking")
               (re-find #"reativ|imprevis|strafe" t)           (conj "reactive-tracking")
               (re-find #"cli(c|que)|timing|tiro|acert" t)     (conj "click-timing"))]
    {:sens-policy policy
     :game jogo
     :game-target (if (= :geral jogo) :kovaaks :transfer)
     :focus (vec (distinct foco))
     :resumo (subs (str texto) 0 (min 200 (count (str texto))))}))

(defn resumo-humano
  "Frase curta pro usuário conferir a interpretação (labels, zero jargão)."
  [{:keys [sens-policy game game-target focus]}]
  (str (case sens-policy
         :fixed  "Dominar a sua sens atual"
         :range  "Sens dentro do seu range"
         :search "Procurando a sua melhor sens"
         "Objetivo")
       (when (= :transfer game-target)
         (str " · transferir pro " (if (and game (not= game :geral))
                                     (name game) "jogo")))
       (if (seq focus)
         (str " · foco: " (str/join ", " (map #(labels/categoria-labels % %) focus)))
         " · foco: geral")))

(defn objective->profile-patch
  "O que o objetivo interpretado sobrepõe no perfil (os três eixos, ADR 0005)."
  [{:keys [sens-policy game game-target focus]}]
  {:player/sens-policy sens-policy
   :player/game-target game-target
   :player/game game
   :player/focus-categories focus})

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
              :mapa (-> mapa
                        (update :sens-policy name)
                        (update :game-target name)
                        (update :game name))
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
