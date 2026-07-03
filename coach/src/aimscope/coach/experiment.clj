(ns aimscope.coach.experiment
  "Experimento de sens (CONTEXT.md, ADR 0005): período declarado de runs numa
  sens diferente da habitual, em cenários que refletem skills distintas.

  - Runs são atribuídas ao experimento AUTOMATICAMENTE pela sens registrada
    no CSV de cada run (cm/360 dentro da tolerância do alvo, depois do início).
  - Veredito só após um mínimo de runs por cenário, e as primeiras runs de
    cada cenário são DESCONTADAS (queda por falta de adaptação é esperada).
  - Veredito é POR SKILL (melhorou/piorou/neutro), comparando NÍVEL ABSOLUTO
    (régua de tiers, ADR 0004) contra o baseline na sens habitual — nunca um
    'aprovada/reprovada' global.
  - Só existe sob política :range ou :search; :fixed nunca propõe (guardrail).

  Declaração vive em experiments.json (%LOCALAPPDATA%/aimscope, a UI grava);
  este ns é puro exceto load/save."
  (:require [aimscope.coach.catalog :as cat]
            [aimscope.coach.labels :as labels]
            [aimscope.coach.residual :as residual]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; cm/360 — espelho de crates/session/src/lib.rs (deg_per_count + cm_per_360)
;; ---------------------------------------------------------------------------

(defn deg-per-count
  "Graus por count de mouse. Escala desconhecida -> nil (nunca chuta)."
  [scale sens]
  (when-let [base (case (some-> scale str str/lower-case)
                    "valorant" 0.07
                    ("cs" "csgo" "cs2" "quakecs" "quake" "apex" "source") 0.022
                    ("overwatch" "ow") 0.0066
                    nil)]
    (when (number? sens) (* base sens))))

(defn cm360
  "cm de mousepad pra girar 360°. nil se escala/sens/dpi indisponíveis."
  [scale sens dpi]
  (when-let [dpc (deg-per-count scale sens)]
    (when (and (number? dpi) (pos? dpi) (pos? dpc))
      (* (/ (/ 360.0 dpc) dpi) 2.54))))

(defn cm360-do-perfil
  "cm/360 da sens habitual do perfil ({:value :scale :dpi})."
  [{:keys [value scale dpi]}]
  (cm360 (some-> scale name) value dpi))

(defn- cm360-da-run
  "cm/360 registrado na run (CSV traz escala+sens; DPI vem do perfil — a
  premissa declarada é DPI constante)."
  [run dpi]
  (cm360 (:sens-scale run) (:horiz-sens run) dpi))

;; ---------------------------------------------------------------------------
;; declaração (experiments.json — a UI grava, o coach lê)
;; ---------------------------------------------------------------------------

(defn json-path []
  (io/file (System/getenv "LOCALAPPDATA") "aimscope" "experiments.json"))

(defn load-declaracao
  "{:sens-alvo-cm360 n :tolerancia-cm n :scenarios [nomes] :criado-em iso
  :status \"ativo\"} ou nil (sem experimento ativo/arquivo)."
  []
  (let [f (json-path)]
    (when (.exists f)
      (try (let [d (json/parse-string (str/replace (slurp f) "﻿" "") true)]
             (when (= "ativo" (:status d)) d))
           (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; atribuição de runs + veredito
;; ---------------------------------------------------------------------------

(def min-runs 3)       ; por cenário, na sens do experimento, pra emitir veredito
(def desconto-runs 1)  ; primeiras N runs por cenário = adaptação (descontadas)
(def ^:private delta-neutro 3.0) ; |delta de nível| <= isto = :neutro

(defn- perto? [cm alvo tol] (and cm alvo (<= (Math/abs (- cm alvo)) tol)))

(defn- nivel
  "Melhor score do grupo -> nível absoluto 0-100 pela régua (ADR 0004)."
  [thresholds scen runs]
  (when (seq runs)
    (some-> (cat/threshold-for thresholds scen)
            (residual/scaled-actual (apply max (map :score runs))))))

(defn- runs-do-cenario
  "Separa as runs de UM cenário em experimento (na sens alvo, pós-início,
  já descontando adaptação) e baseline (na sens habitual, qualquer época)."
  [runs {:keys [sens-alvo-cm360 tolerancia-cm criado-em]} base-cm dpi]
  (let [tol (or tolerancia-cm 2.0)
        ordenadas (sort-by :played-at runs)
        exp (->> ordenadas
                 (filter #(and (perto? (cm360-da-run % dpi) sens-alvo-cm360 tol)
                               (or (nil? criado-em)
                                   (neg? (compare (str criado-em) (str (:played-at %)))))))
                 vec)
        base (->> ordenadas
                  (filter #(if base-cm
                             (perto? (cm360-da-run % dpi) base-cm tol)
                             (not (perto? (cm360-da-run % dpi) sens-alvo-cm360 tol))))
                  vec)]
    {:exp-todas exp
     :exp (vec (drop desconto-runs exp))
     :descontadas (min desconto-runs (count exp))
     :base base}))

(defn status
  "Cruza a declaração com os scores -> estado do experimento pra UI/briefing.
  scores = fatos :score (com :catalog-id); profile dá a sens habitual e o DPI."
  [decl scores catalog thresholds profile]
  (when decl
    (let [dpi     (get-in profile [:player/sens :dpi])
          base-cm (cm360-do-perfil (:player/sens profile))
          cat-by  (into {} (map (juxt :id identity)) catalog)
          by-scen (group-by (comp cat/normalize-name :scenario) scores)
          nomes   (:scenarios decl)
          por-cen
          (vec (for [scen nomes]
                 (let [runs (get by-scen (cat/normalize-name scen) [])
                       {:keys [exp exp-todas descontadas base]}
                       (runs-do-cenario runs decl base-cm dpi)
                       cid  (:catalog-id (first runs))
                       d-exp  (nivel thresholds scen exp)
                       d-base (nivel thresholds scen base)]
                   {:scenario scen
                    :catalog-id cid
                    :runs-validas (count exp)
                    :runs-total (count exp-todas)
                    :descontadas descontadas
                    :runs-necessarias min-runs
                    :nivel-exp d-exp
                    :nivel-base d-base
                    :delta (when (and d-exp d-base) (- d-exp d-base))})))
          pronto? (and (seq por-cen)
                       (every? #(>= (:runs-validas %) min-runs) por-cen)
                       (some :delta por-cen))
          ;; delta por skill: média dos deltas dos cenários, ponderada pelo
          ;; peso da skill no catálogo — sens diferentes favorecem skills
          ;; diferentes, e é ESSA a lição do experimento
          veredito
          (when pronto?
            (->> por-cen
                 (filter :delta)
                 (mapcat (fn [{:keys [catalog-id delta]}]
                           (for [[skill w] (:skills (cat-by catalog-id))]
                             [skill [delta w]])))
                 (reduce (fn [acc [skill dw]]
                           (update acc skill (fnil conj []) dw))
                         {})
                 (mapv (fn [[skill dws]]
                         (let [wsum (reduce + (map second dws))
                               d    (/ (reduce + (map (fn [[d w]] (* d w)) dws))
                                       (max wsum 1e-9))]
                           {:skill skill
                            :label (labels/skill-nome skill)
                            :delta d
                            :veredito (cond (> d delta-neutro)     :melhorou
                                            (< d (- delta-neutro)) :piorou
                                            :else                  :neutro)})))
                 (sort-by :delta >)
                 vec))]
      {:ativo? true
       :sens-alvo-cm360 (:sens-alvo-cm360 decl)
       :sens-base-cm360 base-cm
       :criado-em (:criado-em decl)
       :cenarios por-cen
       :pronto? (boolean pronto?)
       :veredito veredito})))
