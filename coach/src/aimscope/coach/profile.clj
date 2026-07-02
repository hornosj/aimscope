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
  {:player/goal :fixed-sens              ; :fixed-sens | :sens-range | :game-transfer
   :player/sens {:value 0.4 :scale :valorant :dpi 800}
   :player/sens-range nil                ; [cm360-min cm360-max] se :sens-range
   :player/target {:benchmark :voltaic-s5 :scenario :com/bounce-180}
   :player/time-budget-min 45})

(defn json-path [] (io/file (System/getenv "LOCALAPPDATA") "aimscope" "profile.json"))
(defn edn-path  [] (io/file (System/getenv "LOCALAPPDATA") "aimscope" "profile.edn"))

(defn- coerce
  "JSON traz strings onde o domínio usa keywords; (keyword kw) é identidade,
  então a coerção é segura vindo de qualquer fonte."
  [p]
  (cond-> p
    (:player/goal p)   (update :player/goal keyword)
    (get-in p [:player/sens :scale])       (update-in [:player/sens :scale] keyword)
    (get-in p [:player/target :benchmark]) (update-in [:player/target :benchmark] keyword)
    (get-in p [:player/target :scenario])  (update-in [:player/target :scenario] keyword)))

(defn save! [profile]
  (let [f (json-path)]
    (io/make-parents f)
    (spit f (json/generate-string profile {:pretty true}))))

(defn load-profile
  "profile.json > profile.edn (legado, migrado) > default (criado)."
  []
  (let [jf (json-path) ef (edn-path)]
    (cond
      (.exists jf)
      (merge default-profile (coerce (json/parse-string (slurp-sem-bom jf) true)))

      (.exists ef)
      (let [p (merge default-profile (coerce (edn/read-string (slurp ef))))]
        (save! p)
        (.println System/err (str "[coach] profile.edn migrado para " jf))
        p)

      :else
      (do (save! default-profile)
          (.println System/err (str "[coach] perfil default criado em " jf
                                    " — edite pela UI ou à mão"))
          default-profile))))
