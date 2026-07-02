(ns aimscope.coach.residual
  "Análise de resíduo: onde o jogador performa ABAIXO do que o próprio perfil
  de skills prevê — e qual skill explica (gargalo COM evidência).

  previsto(cenário) = Σ peso_skill × skill (só skills com evidência)
  atual(cenário)    = percentil do último score vs histórico próprio (0-100)
  resíduo           = atual - previsto  (negativo = subperformando)
  gargalo           = argmax peso × déficit, déficit = previsto_teto - skill"
  (:require [datascript.core :as d]))

;; ---------------------------------------------------------------------------
;; energia (régua Voltaic): thresholds rank->score, energia 100..1200 linear
;; ---------------------------------------------------------------------------

(defn energy
  "Score -> energia VT (0-1200) por interpolação linear entre thresholds.
  `thresholds` = mapa rank->score (qualquer subconjunto ordenável). nil se
  não houver thresholds (catálogo :pending)."
  [thresholds score]
  (let [ts (->> thresholds (filter (comp number? val)) (sort-by val) vec)]
    (when (and (seq ts) (number? score))
      (let [[_ t0] (first ts)]
        (cond
          (<= score t0) (* 100.0 (/ score (max t0 1e-9)))
          :else
          (loop [i 0]
            (if (>= i (dec (count ts)))
              1200.0 ; acima do último threshold: teto
              (let [[_ ta] (nth ts i) [_ tb] (nth ts (inc i))]
                (if (<= score tb)
                  (+ (* 100.0 (inc i))
                     (* 100.0 (/ (- score ta) (max (- tb ta) 1e-9))))
                  (recur (inc i)))))))))))

(defn- percentile-of-last [vals]
  (let [n (count vals) last* (peek vals)]
    (* 100.0 (/ (count (filter #(<= % last*) (butlast vals)))
                (max 1 (dec n))))))

(defn- predicted [skills entry]
  (let [ws (keep (fn [[skill w]]
                   (when-let [v (get-in skills [skill :value])]
                     [w v]))
                 (:skills entry))]
    (when (seq ws)
      (let [wsum (reduce + (map first ws))]
        (/ (reduce + (map (fn [[w v]] (* w v)) ws)) (max wsum 1e-9))))))

(defn- bottleneck [skills entry]
  (->> (:skills entry)
       (keep (fn [[skill w]]
               (when-let [v (get-in skills [skill :value])]
                 {:skill skill :weight w :value v
                  :impact (* w (- 100.0 v))})))
       (sort-by :impact >)
       first))

(defn analyze
  "scores (fatos :score com :catalog-id), skills estimadas, catálogo ->
  [{:scenario :catalog-id :actual :predicted :residual :bottleneck}] ordenado
  do pior resíduo pro melhor. Exige ≥3 plays no cenário (percentil honesto).
  Usa datascript p/ o join score×catálogo (decisão ADR 0001)."
  [scores skills catalog dsdb]
  (let [;; datalog: cenários (catalog-id) com contagem de plays
        played (d/q '[:find ?cid (count ?e)
                      :where [?e :score/catalog-id ?cid]
                             [(not= ?cid :unknown)]]
                    dsdb)
        by-id  (group-by :catalog-id scores)
        cat-by (into {} (map (juxt :id identity)) catalog)]
    (->> played
         (keep (fn [[cid n]]
                 (when (>= n 3)
                   (let [entry  (cat-by cid)
                         vals   (mapv :score (sort-by :played-at (by-id cid)))
                         ;; com thresholds do catálogo: energia (0-1200 -> 0-100);
                         ;; sem (:pending): percentil do próprio histórico
                         actual (or (some-> (energy (:rank-thresholds entry) (peek vals))
                                            (/ 12.0))
                                    (percentile-of-last vals))
                         pred   (predicted skills entry)]
                     (when pred
                       {:scenario   (:scenario (last (sort-by :played-at (by-id cid))))
                        :catalog-id cid
                        :n-plays    n
                        :actual     actual
                        :predicted  pred
                        :residual   (- actual pred)
                        :bottleneck (bottleneck skills entry)})))))
         (sort-by :residual)
         vec)))

(defn diagnosis
  "Resumo executivo: skills ordenadas por déficit (só com evidência),
  gargalo global e cenários subperformando."
  [skills residuals]
  (let [with-ev (->> skills
                     (keep (fn [[k {:keys [value confidence n]}]]
                             (when value
                               {:skill k :value value :confidence confidence :n n})))
                     (sort-by :value))
        no-ev   (keep (fn [[k {:keys [value]}]] (when-not value k)) skills)]
    {:skills/ranked        (vec with-ev)
     :skills/sem-evidencia (vec no-ev)          ; reaction/* até o DXGI
     :gargalo-global       (first with-ev)
     :cenarios-subperformando (vec (take 3 (filter #(neg? (:residual %)) residuals)))}))
