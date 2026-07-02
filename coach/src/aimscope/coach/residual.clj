(ns aimscope.coach.residual
  "Análise de resíduo: onde o jogador performa ABAIXO do que o próprio perfil
  de skills prevê — e qual skill explica (gargalo COM evidência).

  previsto(cenário) = Σ peso_skill × skill (só skills com evidência)
  atual(cenário)    = percentil do último score vs histórico próprio (0-100)
  resíduo           = atual - previsto  (negativo = subperformando)
  gargalo           = argmax peso × déficit, déficit = previsto_teto - skill"
  (:require [datascript.core :as d]
            [aimscope.coach.catalog :as cat]))

;; ---------------------------------------------------------------------------
;; régua: interpola um score em pontos [[nível score]...] (nível = energia VT
;; 0-1200 OU percentil 0-100, conforme :scale da entrada de thresholds).
;; ---------------------------------------------------------------------------

(defn level-of
  "Score -> nível por interpolação linear entre `points` [[nível score]...].
  Abaixo do 1º ponto: linear de 0 ao 1º nível. Acima do último: clampa no
  último nível (a régua daquele cenário específico esgotou). nil se sem pontos."
  [points score]
  (let [pts (->> points
                 (filter (fn [[l s]] (and (number? l) (number? s))))
                 (sort-by second) vec)]
    (when (and (seq pts) (number? score))
      (let [[l0 s0] (first pts)
            [ln _]  (peek pts)]
        (cond
          (<= score s0) (* l0 (/ score (max s0 1e-9)))
          (>= score (second (peek pts))) ln
          :else
          (loop [i 0]
            (let [[la sa] (nth pts i) [lb sb] (nth pts (inc i))]
              (if (<= score sb)
                (+ la (* (- lb la) (/ (- score sa) (max (- sb sa) 1e-9))))
                (recur (inc i))))))))))

(defn scaled-actual
  "Nível -> desempenho 0-100. :energy (VT 0-1200) normaliza ÷12; :percentile
  (0-100) passa direto. nil se o cenário não tem régua semeada."
  [spec score]
  (when-let [lvl (level-of (:points spec) score)]
    (case (:scale spec)
      :energy     (/ lvl 12.0)
      :percentile lvl
      lvl)))

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
  "scores (fatos :score com :catalog-id), skills estimadas, catálogo, índice de
  thresholds (por nome exato) -> [{:scenario :catalog-id :actual :predicted
  :residual :bottleneck}] ordenado do pior resíduo pro melhor. Exige ≥3 plays no
  cenário (percentil honesto). Usa datascript p/ o join score×catálogo (ADR 0001)."
  [scores skills catalog thresholds dsdb]
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
                         plays  (sort-by :played-at (by-id cid))
                         scen   (:scenario (last plays))
                         vals   (mapv :score plays)
                         ;; régua por nome exato (energia VT ou percentil, -> 0-100)
                         ;; sobre o último score; sem régua: percentil do histórico
                         actual (or (some-> (cat/threshold-for thresholds scen)
                                            (scaled-actual (peek vals)))
                                    (percentile-of-last vals))
                         pred   (predicted skills entry)]
                     (when pred
                       {:scenario   scen
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
