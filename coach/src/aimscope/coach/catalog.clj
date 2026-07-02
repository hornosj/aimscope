(ns aimscope.coach.catalog
  "Carrega e indexa o catálogo de cenários (EDN curado, no classpath via
  :paths [\"catalog\"]). Ver CONTEXT.md: Catálogo/Cenário/Benchmark."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ORDEM IMPORTA no match: viscose primeiro (regexes exigem "viscose" no nome,
;; mais específicos), depois VT genérico, depois community.
(def ^:private files ["viscose-s2.edn" "voltaic-s5.edn" "community.edn"])

(defn load-catalog
  "Devolve todos os cenários de todos os arquivos, cada um com :benchmark."
  []
  (vec (mapcat (fn [f]
                 (let [res (io/resource f)]
                   (when-not res (throw (ex-info (str "catálogo ausente: " f) {})))
                   (let [{:keys [benchmark scenarios]} (edn/read-string (slurp res))]
                     (map #(assoc % :benchmark benchmark) scenarios))))
               files)))

(defn match-scenario
  "Casa um nome de cenário vindo do CSV com uma entrada do catálogo (regex
  :match, case-insensitive). nil se desconhecido — score ainda é ingerido,
  só não alimenta skills via prior."
  [catalog scenario-name]
  (when (seq scenario-name)
    (first (filter #(re-find (re-pattern (:match %)) scenario-name) catalog))))

(defn drills-for
  "Cenários marcados como drill para uma skill (alvos de prescrição)."
  [catalog skill]
  (filterv #(some #{skill} (:drill/for %)) catalog))

(defn all-skills
  "Vocabulário fechado de 11 skills (CONTEXT.md)."
  []
  [:reaction/simple :reaction/choice :acquisition/ballistic
   :precision/micro-adjust :click/timing :tracking/smooth-wrist
   :tracking/smooth-arm :tracking/reactive :stability/tremor
   :orientation/spatial :consistency/endurance])
