(ns aimscope.coach.catalog
  "Carrega e indexa o catálogo de cenários (EDN curado, no classpath via
  :paths [\"catalog\"]). Ver CONTEXT.md: Catálogo/Cenário/Benchmark."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ORDEM IMPORTA no match: viscose primeiro (regexes exigem "viscose" no nome,
;; mais específicos), depois VT S4 (âncoras específicas, pasu/onewall com
;; lookahead p/ não roubar nomes S5), depois S5 genérico, depois community.
(def ^:private files ["viscose-s2.edn" "voltaic-s4.edn" "voltaic-s5.edn" "community.edn"])

(defn normalize-name
  "Chave canônica de nome de cenário: minúsculas, espaços colapsados, sem borda.
  Usada pra casar o nome do CSV com o índice de thresholds (por nome exato)."
  [s]
  (-> s str str/trim str/lower-case (str/replace #"\s+" " ")))

(defn load-catalog
  "Devolve todos os cenários de todos os arquivos, cada um com :benchmark."
  []
  (vec (mapcat (fn [f]
                 (let [res (io/resource f)]
                   (when-not res (throw (ex-info (str "catálogo ausente: " f) {})))
                   (let [{:keys [benchmark scenarios]} (edn/read-string (slurp res))]
                     (map #(assoc % :benchmark benchmark) scenarios))))
               files)))

(defn load-thresholds
  "Índice de RÉGUA por nome exato de cenário: {nome-normalizado {:scale kw
  :points [[nível score]...]}}. Junta o :thresholds de todos os arquivos
  (só VT S4 e Viscose têm; os demais omitem). Colisão: primeiro arquivo vence."
  []
  (reduce (fn [acc f]
            (let [res (io/resource f)
                  {:keys [thresholds]} (edn/read-string (slurp res))]
              (reduce-kv (fn [m nome spec]
                           (let [k (normalize-name nome)]
                             (if (contains? m k) m (assoc m k spec))))
                         acc thresholds)))
          {} files))

(defn threshold-for
  "Régua de um cenário pelo nome (do CSV). nil se não semeado."
  [thresholds scenario-name]
  (get thresholds (normalize-name scenario-name)))

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
  "Vocabulário fechado de 14 skills — taxonomia do bench Viscose S2
  (4 categorias × subcategorias; CONTEXT.md, remodelagem 2026-07-02)."
  []
  [:control-tracking/arm :control-tracking/wrist
   :control-tracking/fingertip :control-tracking/blending
   :reactive-tracking/control :reactive-tracking/speed :reactive-tracking/reading
   :flick-tech/speed :flick-tech/stability :flick-tech/micro :flick-tech/post-flick
   :click-timing/reading :click-timing/precision :click-timing/stability])
