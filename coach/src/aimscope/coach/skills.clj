(ns aimscope.coach.skills
  "Estimador das 11 skills latentes (CONTEXT.md) a partir de duas correntes de
  evidência: cinemática (metrics.json do sensor) e scores (CSV × catálogo).

  Escala: 0–100, 50 = neutro. Âncoras absolutas PROVISÓRIAS e documentadas —
  recalibradas pelo loop previsto×realizado quando houver histórico.
  Fusão temporal: EWMA (alpha maior p/ evidência mais confiável).
  Skills sem evidência (reaction/* antes do DXGI) ficam com :confidence 0 e
  FORA do diagnóstico — nunca inventamos número."
  (:require [aimscope.coach.catalog :as cat]))

;; ---------------------------------------------------------------------------
;; âncoras: métrica cinemática -> score 0-100 (lerp em pontos de referência)
;; ---------------------------------------------------------------------------

(defn- lerp-scale
  "Interpola v numa tabela [[x score] ...] ordenada por x (clampa nas pontas)."
  [table v]
  (when (number? v)
    (let [[x0 s0] (first table) [xn sn] (last table)]
      (cond (<= v x0) s0
            (>= v xn) sn
            :else (some (fn [[[x1 s1] [x2 s2]]]
                          (when (<= x1 v x2)
                            (+ s1 (* (- s2 s1) (/ (- v x1) (- x2 x1))))))
                        (partition 2 1 table))))))

(def ^:private anchors
  ;; [métrica -> tabela] — pontos de: literatura (tremor), pilotos sintéticos e
  ;; bom senso de coaching. PROVISÓRIO por decisão (design-coach.md §2).
  {:corrections    [[0.0 90.0] [1.0 70.0] [2.0 45.0] [3.0 25.0] [5.0 10.0]]
   :overshoot      [[1.0 90.0] [1.05 70.0] [1.10 50.0] [1.20 25.0] [1.4 10.0]]
   :efficiency     [[0.6 10.0] [0.8 40.0] [0.9 65.0] [0.97 90.0] [1.0 95.0]]
   :tremor-ratio   [[0.05 90.0] [0.15 75.0] [0.25 60.0] [0.35 45.0] [0.5 25.0] [0.7 10.0]]
   :sparc          [[-3.5 10.0] [-2.5 35.0] [-1.9 60.0] [-1.5 80.0] [-1.2 92.0]]
   :homing-ms      [[80.0 90.0] [150.0 70.0] [250.0 50.0] [400.0 30.0] [600.0 15.0]]
   ;; RT visual mediano (ms): elite ~150-180, mediano ~230-280 (literatura de
   ;; simple RT + ajuste p/ display/jogo). PROVISORIO como os demais.
   :reaction-ms    [[150.0 95.0] [190.0 80.0] [240.0 60.0] [320.0 40.0] [450.0 15.0]]
   ;; % de spawns com 1º movimento na direção ERRADA (choice, v0.4)
   :wrong-dir      [[0.02 95.0] [0.08 80.0] [0.15 60.0] [0.30 35.0] [0.50 10.0]]
   ;; lag de realinhamento pós-inversão do alvo (ms) — 'perda de movimento'
   :realign-ms     [[120.0 95.0] [180.0 78.0] [250.0 60.0] [350.0 38.0] [500.0 15.0]]})

(defn- m [metrics & path] (get-in metrics path))

(defn- band-summary
  "bouts_by_amplitude do sensor: {\"small\" {\"sparc\" {\"median\" ...}}}."
  [metrics band key*]
  (m metrics :bouts_by_amplitude (keyword band) (keyword key*) :median))

(defn kinematic-evidence
  "metrics.json (parseado com keywords) -> {skill {:value v :confidence c}}.
  Só skills com evidência real na sessão."
  [metrics]
  (let [bs      (fn [k] (m metrics :bouts_summary k :median))
        n-bouts (or (m metrics :n_bouts) 0)
        conf    (min 1.0 (/ n-bouts 60.0))          ; 60+ bouts = confiança cheia
        avg     (fn [xs] (let [xs (remove nil? xs)]
                           (when (seq xs) (/ (reduce + xs) (count xs)))))
        ev      {:acquisition/ballistic
                 (avg [(lerp-scale (anchors :corrections) (bs :n_corrections))
                       (lerp-scale (anchors :overshoot)   (bs :overshoot_ratio))])
                 :precision/micro-adjust
                 (avg [(lerp-scale (anchors :homing-ms) (bs :time_peak_to_end_ms))
                       (lerp-scale (anchors :corrections)
                                   (or (band-summary metrics "micro" "n_corrections")
                                       (band-summary metrics "small" "n_corrections")))])
                 :tracking/smooth-wrist
                 (lerp-scale (anchors :sparc)
                             (or (band-summary metrics "small" "sparc")
                                 (band-summary metrics "micro" "sparc")))
                 :tracking/smooth-arm
                 (lerp-scale (anchors :sparc)
                             (or (band-summary metrics "large" "sparc")
                                 (band-summary metrics "medium" "sparc")))
                 :stability/tremor
                 (lerp-scale (anchors :tremor-ratio) (m metrics :tremor :band_power_ratio))
                 :orientation/spatial
                 (avg [(lerp-scale (anchors :efficiency)
                                   (band-summary metrics "large" "efficiency"))
                       (lerp-scale (anchors :overshoot)
                                   (band-summary metrics "large" "overshoot_ratio"))])
                 :consistency/endurance
                 (when-let [h (m metrics :halves)]
                   ;; degradação 1ª->2ª metade: eficiência caindo = endurance baixa
                   (let [e1 (m h :first :efficiency) e2 (m h :second :efficiency)]
                     (when (and e1 e2 (pos? e1))
                       (lerp-scale [[0.85 15.0] [0.95 45.0] [1.0 70.0] [1.05 90.0]]
                                   (/ e2 e1)))))}]
    (cond-> (into {} (keep (fn [[k v]] (when v [k {:value (double v) :confidence conf}])) ev))
      ;; reaction/simple (v0.3, screen.parquet) tem confiança própria: nº de
      ;; pares evento-visual->onset casados, não nº de bouts.
      (m metrics :reaction :rt_median_ms)
      (assoc :reaction/simple
             {:value      (double (lerp-scale (anchors :reaction-ms)
                                              (m metrics :reaction :rt_median_ms)))
              :confidence (min 1.0 (/ (or (m metrics :reaction :n_matched) 0) 30.0))})

      (m metrics :reaction_choice :wrong_direction_rate)
      (assoc :reaction/choice
             {:value      (double (lerp-scale (anchors :wrong-dir)
                                              (m metrics :reaction_choice :wrong_direction_rate)))
              :confidence (min 1.0 (/ (or (m metrics :reaction_choice :n) 0) 25.0))})

      (m metrics :pursuit :realign_median_ms)
      (assoc :tracking/reactive
             {:value      (double (lerp-scale (anchors :realign-ms)
                                              (m metrics :pursuit :realign_median_ms)))
              :confidence (min 1.0 (/ (or (m metrics :pursuit :n_matched) 0) 25.0))}))))

;; ---------------------------------------------------------------------------
;; evidência de score: z do próprio histórico distribui p/ skills via catálogo
;; ---------------------------------------------------------------------------

(defn- mean [xs] (/ (reduce + xs) (count xs)))
(defn- std [xs]
  (let [mu (mean xs)] (Math/sqrt (max 1e-9 (mean (map #(Math/pow (- % mu) 2) xs))))))

(defn score-evidence
  "Para cada cenário com ≥3 scores, z do último vs histórico -> 0-100 (sigmoide)
  distribuído pelas skills do catálogo com os pesos como confiança."
  [scores catalog]
  (let [by-scen (group-by :catalog-id (filter :catalog-id scores))]
    (reduce
     (fn [acc [cid ss]]
       (let [vals (mapv :score (sort-by :played-at ss))]
         (if (< (count vals) 3)
           acc
           (let [z     (/ (- (peek vals) (mean vals)) (std vals))
                 v     (* 100.0 (/ 1.0 (+ 1.0 (Math/exp (- (* 1.2 z)))))) ; sigmoide
                 entry (first (filter #(= cid (:id %)) catalog))]
             (reduce (fn [a [skill w]]
                       (update a skill (fnil conj [])
                               {:value v :confidence (* w (min 1.0 (/ (count vals) 8.0)))}))
                     acc (:skills entry))))))
     {} by-scen)))

;; ---------------------------------------------------------------------------
;; fusão EWMA
;; ---------------------------------------------------------------------------

(defn fuse
  "Sequência temporal de evidências {skill {:value :confidence}} -> estimativa
  final {skill {:value :confidence :n}}. EWMA com alpha = 0.4 × confidence."
  [evidence-seq]
  (reduce
   (fn [est ev]
     (reduce (fn [e [skill {:keys [value confidence]}]]
               (let [{v0 :value c0 :confidence n :n :or {v0 50.0 c0 0.0 n 0}} (e skill)
                     alpha (* 0.4 confidence)]
                 (assoc e skill {:value      (+ v0 (* alpha (- value v0)))
                                 :confidence (min 1.0 (+ c0 (* 0.3 confidence)))
                                 :n          (inc n)})))
             est ev))
   {} evidence-seq))

(defn estimate
  "Pipeline completo: fatos -> estimativas das 11 skills (sem inventar as sem
  evidência). kin-facts = fatos :kinematics; score-facts = fatos :score."
  [kin-facts score-facts catalog]
  (let [kin-evs  (map #(kinematic-evidence (:metrics %)) kin-facts)
        score-ev (score-evidence score-facts catalog)
        ;; score-ev vira UMA evidência agregada por skill (média ponderada)
        score-ev' (into {}
                        (map (fn [[skill obs]]
                               (let [wsum (reduce + (map :confidence obs))]
                                 [skill {:value (/ (reduce + (map #(* (:value %) (:confidence %)) obs))
                                                   (max wsum 1e-9))
                                         :confidence (min 1.0 wsum)}]))
                             score-ev))
        fused (fuse (concat kin-evs (when (seq score-ev') [score-ev'])))]
    (into {} (map (fn [s] [s (get fused s {:value nil :confidence 0.0 :n 0})])
                  (cat/all-skills)))))
