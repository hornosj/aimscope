(ns aimscope.coach.profile
  "Perfil de objetivo do jogador (CONTEXT.md). Fonte única: profile.json em
  %LOCALAPPDATA%/aimscope — JSON para que a UI Rust edite o MESMO arquivo.
  profile.edn legado é migrado na primeira leitura. Toda prescrição passa pelo
  filtro do perfil — regra dura do grill #9."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- slurp-sem-bom
  "Ferramenta Windows adora gravar BOM (0xFEFF) em UTF-8; o Jackson engasga.
  Qualquer leitura de JSON editável por fora passa por aqui."
  [f]
  (str/replace (slurp f) "﻿" ""))

(def default-profile
  ;; ADR 0005: três eixos ORTOGONAIS, não um modo único.
  {:player/sens-policy :fixed            ; :fixed | :range | :search
   :player/sens-range nil                ; [cm360-min cm360-max] se :range
   :player/game-target :kovaaks          ; :kovaaks | :transfer (jogo em :player/game)
   :player/game :geral                   ; :valorant | :cs2 | ... | :geral
   :player/focus-categories []           ; eixo 3: categorias de foco
   :player/name nil                      ; saudação do briefing (opcional)
   :player/sens {:value 0.4 :scale :valorant :dpi 800}
   :player/target {:benchmark :voltaic-s5 :scenario :com/bounce-180}
   :player/time-budget-min 45})

(def ^:private legacy-goal->axes
  ;; migração do campo único :player/goal (pré-ADR 0005). :game-transfer nunca
  ;; permitiu ação de sens, logo migra pra política :fixed + alvo :transfer.
  {:fixed-sens    {:player/sens-policy :fixed  :player/game-target :kovaaks}
   :sens-range    {:player/sens-policy :range  :player/game-target :kovaaks}
   :game-transfer {:player/sens-policy :fixed  :player/game-target :transfer}})

(defn normalize
  "Perfil em QUALQUER formato (legado com :player/goal ou já em eixos) ->
  eixos garantidos. Pura — plan.clj chama isto antes de filtrar ações, então
  perfis antigos em testes/arquivos continuam válidos."
  [p]
  (let [legado (some-> (:player/goal p) keyword legacy-goal->axes)]
    (-> (merge (select-keys default-profile
                            [:player/sens-policy :player/game-target :player/game])
               legado
               (into {} (filter (comp some? val)
                                (select-keys p [:player/sens-policy
                                                :player/game-target
                                                :player/game]))))
        (->> (merge p))
        (dissoc :player/goal))))

(defn json-path [] (io/file (System/getenv "LOCALAPPDATA") "aimscope" "profile.json"))
(defn edn-path  [] (io/file (System/getenv "LOCALAPPDATA") "aimscope" "profile.edn"))

(defn- coerce
  "JSON traz strings onde o domínio usa keywords; (keyword kw) é identidade,
  então a coerção é segura vindo de qualquer fonte."
  [p]
  (cond-> p
    (:player/goal p)        (update :player/goal keyword)
    (:player/sens-policy p) (update :player/sens-policy keyword)
    (:player/game-target p) (update :player/game-target keyword)
    (:player/game p)        (update :player/game keyword)
    (get-in p [:player/sens :scale])       (update-in [:player/sens :scale] keyword)
    (get-in p [:player/target :benchmark]) (update-in [:player/target :benchmark] keyword)
    (get-in p [:player/target :scenario])  (update-in [:player/target :scenario] keyword)))

(defn save! [profile]
  (let [f (json-path)]
    (io/make-parents f)
    (spit f (json/generate-string profile {:pretty true}))))

(defn load-profile
  "profile.json > profile.edn (legado, migrado) > default (criado).
  Perfil com :player/goal (pré-eixos) é migrado e persistido em eixos."
  []
  (let [jf (json-path) ef (edn-path)]
    (cond
      (.exists jf)
      (let [raw (coerce (json/parse-string (slurp-sem-bom jf) true))
            p   (normalize (merge default-profile raw))]
        (when (:player/goal raw)
          (save! p)
          (.println System/err "[coach] perfil migrado p/ eixos (ADR 0005)"))
        p)

      (.exists ef)
      (let [p (normalize (merge default-profile (coerce (edn/read-string (slurp ef)))))]
        (save! p)
        (.println System/err (str "[coach] profile.edn migrado para " jf))
        p)

      :else
      (do (save! default-profile)
          (.println System/err (str "[coach] perfil default criado em " jf
                                    " — edite pela UI ou à mão"))
          default-profile))))
