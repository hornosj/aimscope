(ns aimscope.coach.skills
  "Estimador das 14 skills latentes (taxonomia Viscose S2, CONTEXT.md) a partir
  de duas correntes de evidência: cinemática (metrics.json do sensor) e scores
  (CSV × catálogo). Skills sem canal cinemático (flick-tech/speed,
  reactive-tracking/speed, click-timing/stability) são score-driven até o
  sensor ganhar o canal correspondente.

  ADR 0004: o canal de score é sempre ABSOLUTO — melhor score na régua de
  tiers do benchmark (a mesma da energia) -> 0-100. O z relativo ao próprio
  histórico NÃO é nível: é a TENDÊNCIA (fn `trend`), indicador direcional
  exibido separado e nunca fundido.

  Escala: 0–100, 50 = neutro. Âncoras: carrega anchors.edn CALIBRADO pelo lab
  (docs/design-vod-lab.md §9, ADR 0003) quando existir no classpath; fallback
  pro prior provisório hardcoded — o coach offline nunca depende do lab.
  Âncora calibrada com n baixo fica marcada no :meta e pesa menos na evidência
  (nunca silenciada). Fusão temporal: EWMA (alpha maior p/ evidência mais
  confiável). Skills sem evidência (reaction/* antes do DXGI) ficam com
  :confidence 0 e FORA do diagnóstico — nunca inventamos número."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aimscope.coach.catalog :as cat]
            [aimscope.coach.residual :as residual]))

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

(def provisional-anchors
  ;; [métrica -> tabela] — pontos de: literatura (tremor), pilotos sintéticos e
  ;; bom senso de coaching. PROVISÓRIO por decisão (design-coach.md §2);
  ;; substituído chave a chave pelo anchors.edn do lab quando existir.
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
   :realign-ms     [[120.0 95.0] [180.0 78.0] [250.0 60.0] [350.0 38.0] [500.0 15.0]]
   ;; endurance: eficiência 2ª metade / 1ª metade (razão adimensional)
   :endurance-eff  [[0.85 15.0] [0.95 45.0] [1.0 70.0] [1.05 90.0]]})

(defn read-anchors-file
  "anchors.edn emitido pelo lab (coach/catalog no classpath, como os demais
  EDN). nil se ausente ou corrompido — o coach nunca quebra por causa do lab."
  []
  (when-let [res (io/resource "anchors.edn")]
    (try (edn/read-string (slurp res))
         (catch Exception _ nil))))

(defn merge-calibrated
  "Fallback por CHAVE: métrica calibrada pelo lab substitui a provisória;
  o resto continua no prior (o lab só emite o que passou no gate — ADR 0003)."
  [provisional file-map]
  (merge provisional (:anchors file-map)))

(def ^:private calibrated (delay (read-anchors-file)))
(def ^:private anchors* (delay (merge-calibrated provisional-anchors @calibrated)))

(defn- anchors [k] (get @anchors* k))

(defn anchor-confidence
  "Peso de confiança da âncora k dado o :meta do anchors.edn: calibrada com n
  baixo pesa menos (design §6 — marcada, não silenciada). Provisória ou sem
  meta = 1.0 (o prior já assume a própria incerteza)."
  [meta k]
  (if-let [n (get-in meta [k :n])]
    (-> (/ n 40.0) (min 1.0) (max 0.3) double)
    1.0))

(def ^:private skill->anchor-keys
  ;; quais âncoras sustentam a evidência cinemática de cada skill — usado pra
  ;; propagar a confiança da âncora calibrada pra confiança da evidência.
  ;; Skills sem entrada aqui são score-driven (sem evidência cinemática ainda).
  {:flick-tech/stability       [:overshoot]
   :flick-tech/post-flick      [:corrections :homing-ms]
   :flick-tech/micro           [:corrections :homing-ms]
   :control-tracking/wrist     [:sparc]
   :control-tracking/arm       [:sparc]
   :control-tracking/fingertip [:tremor-ratio :sparc]
   :control-tracking/blending  [:endurance-eff]
   :click-timing/precision     [:efficiency]
   :click-timing/reading       [:reaction-ms]
   :reactive-tracking/reading  [:wrong-dir]
   :reactive-tracking/control  [:realign-ms]})

(defn- apply-anchor-confidence [ev meta]
  (into {}
        (map (fn [[skill e]]
               (let [ks (skill->anchor-keys skill)
                     c  (if (seq ks)
                          (apply min (map #(anchor-confidence meta %) ks))
                          1.0)]
                 [skill (update e :confidence * c)])))
        ev))

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
        ev      {;; ---- Flick Tech: o arremesso e sua correção -------------
                 :flick-tech/stability
                 (lerp-scale (anchors :overshoot) (bs :overshoot_ratio))
                 :flick-tech/post-flick
                 (avg [(lerp-scale (anchors :corrections) (bs :n_corrections))
                       (lerp-scale (anchors :homing-ms) (bs :time_peak_to_end_ms))])
                 :flick-tech/micro
                 (avg [(lerp-scale (anchors :homing-ms) (bs :time_peak_to_end_ms))
                       (lerp-scale (anchors :corrections)
                                   (or (band-summary metrics "micro" "n_corrections")
                                       (band-summary metrics "small" "n_corrections")))])
                 ;; ---- Control Tracking: suavidade por grupo muscular -----
                 :control-tracking/wrist
                 (lerp-scale (anchors :sparc)
                             (or (band-summary metrics "small" "sparc")
                                 (band-summary metrics "micro" "sparc")))
                 :control-tracking/arm
                 (lerp-scale (anchors :sparc)
                             (or (band-summary metrics "large" "sparc")
                                 (band-summary metrics "medium" "sparc")))
                 :control-tracking/fingertip
                 (avg [(lerp-scale (anchors :tremor-ratio) (m metrics :tremor :band_power_ratio))
                       (lerp-scale (anchors :sparc)
                                   (or (band-summary metrics "micro" "sparc")
                                       (band-summary metrics "small" "sparc")))])
                 :control-tracking/blending
                 ;; sustentar o controle: degradação 1ª->2ª metade da sessão
                 (when-let [h (m metrics :halves)]
                   (let [e1 (m h :first :efficiency) e2 (m h :second :efficiency)]
                     (when (and e1 e2 (pos? e1))
                       (lerp-scale (anchors :endurance-eff) (/ e2 e1)))))
                 ;; ---- Click Timing: alinhamento fino ----------------------
                 :click-timing/precision
                 (lerp-scale (anchors :efficiency)
                             (or (band-summary metrics "small" "efficiency")
                                 (band-summary metrics "large" "efficiency")
                                 (bs :efficiency)))}]
    (-> (cond-> (into {} (keep (fn [[k v]] (when v [k {:value (double v) :confidence conf}])) ev))
      ;; leitura de clique (v0.3, screen.parquet) tem confiança própria: nº de
      ;; pares evento-visual->onset casados, não nº de bouts.
      (m metrics :reaction :rt_median_ms)
      (assoc :click-timing/reading
             {:value      (double (lerp-scale (anchors :reaction-ms)
                                              (m metrics :reaction :rt_median_ms)))
              :confidence (min 1.0 (/ (or (m metrics :reaction :n_matched) 0) 30.0))})

      (m metrics :reaction_choice :wrong_direction_rate)
      (assoc :reactive-tracking/reading
             {:value      (double (lerp-scale (anchors :wrong-dir)
                                              (m metrics :reaction_choice :wrong_direction_rate)))
              :confidence (min 1.0 (/ (or (m metrics :reaction_choice :n) 0) 25.0))})

      (m metrics :pursuit :realign_median_ms)
      (assoc :reactive-tracking/control
             {:value      (double (lerp-scale (anchors :realign-ms)
                                              (m metrics :pursuit :realign_median_ms)))
              :confidence (min 1.0 (/ (or (m metrics :pursuit :n_matched) 0) 25.0))}))
        (apply-anchor-confidence (:meta @calibrated)))))

;; ---------------------------------------------------------------------------
;; evidência de score: nível ABSOLUTO pela régua de tiers (ADR 0004)
;; ---------------------------------------------------------------------------

(defn- mean [xs] (/ (reduce + xs) (count xs)))
(defn- std [xs]
  (let [mu (mean xs)] (Math/sqrt (max 1e-9 (mean (map #(Math/pow (- % mu) 2) xs))))))

(defn score-evidence
  "Nível ABSOLUTO por cenário: melhor score na régua de tiers (a mesma da
  energia) -> 0-100, distribuído pelas skills do catálogo com o peso como
  confiança. UMA run já é evidência (a confiança cresce com o nº de plays —
  é o que torna o placement de 1 run útil). Cenário sem régua semeada não
  gera nível: nível nunca vem de z relativo (ADR 0004)."
  [scores catalog thresholds]
  (let [by-scen (group-by :catalog-id (filter :catalog-id scores))
        cat-by  (into {} (map (juxt :id identity)) catalog)]
    (reduce
     (fn [acc [cid ss]]
       (let [scen (:scenario (last (sort-by :played-at ss)))
             best (apply max (map :score ss))
             v    (some-> (cat/threshold-for thresholds scen)
                          (residual/scaled-actual best))]
         (if-not v
           acc
           (let [n-conf (min 1.0 (/ (count ss) 4.0))]
             (reduce (fn [a [skill w]]
                       (update a skill (fnil conj [])
                               {:value (double v) :confidence (* w n-conf)}))
                     acc (:skills (cat-by cid)))))))
     {} by-scen)))

(defn trend
  "TENDÊNCIA (CONTEXT.md, ADR 0004): z do último score vs a média do PRÓPRIO
  jogador, agregado por skill (pesos do catálogo como peso da média). É um
  indicador direcional — nunca nível, nunca entra na fusão. Exige ≥3 plays
  no cenário. -> {skill {:z x :dir :up|:flat|:down}}"
  [scores catalog]
  (let [by-scen (group-by :catalog-id (filter :catalog-id scores))
        cat-by  (into {} (map (juxt :id identity)) catalog)
        zs (reduce
            (fn [acc [cid ss]]
              (let [vals (mapv :score (sort-by :played-at ss))]
                (if (< (count vals) 3)
                  acc
                  (let [z (/ (- (peek vals) (mean vals)) (std vals))]
                    (reduce (fn [a [skill w]]
                              (update a skill (fnil conj []) [z w]))
                            acc (:skills (cat-by cid)))))))
            {} by-scen)]
    (into {}
          (map (fn [[skill obs]]
                 (let [wsum (reduce + (map second obs))
                       z    (/ (reduce + (map (fn [[z w]] (* z w)) obs))
                               (max wsum 1e-9))]
                   [skill {:z z :dir (cond (> z 0.35)    :up
                                           (< z -0.35)   :down
                                           :else         :flat)}])))
          zs)))

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
  "Pipeline completo: fatos -> estimativas das 14 skills (sem inventar as sem
  evidência). kin-facts = fatos :kinematics; score-facts = fatos :score;
  thresholds = índice de régua por nome (cat/load-thresholds)."
  [kin-facts score-facts catalog thresholds]
  (let [kin-evs  (map #(kinematic-evidence (:metrics %)) kin-facts)
        score-ev (score-evidence score-facts catalog thresholds)
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
