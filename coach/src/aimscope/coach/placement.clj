(ns aimscope.coach.placement
  "Placement (calibração do perfil, CONTEXT.md): sequência curada e ADAPTATIVA
  em 2 estágios que varre o espaço das skills pra dar evidência inicial.

  Estágio 1 = triagem: os 7 degraus Novice, 1 run cada (com a régua ABSOLUTA
  do ADR 0004, 1 run já é evidência). Estágio 2 = a categoria que BATE NO TETO
  da régua escala pro degrau mais difícil (Intermediate; Advanced se capar de
  novo), 2 runs por degrau — sem isso o perfil inicial de um jogador forte
  fica achatado no teto Novice (nível máx. 400/1200 ≈ 33).

  Cenários reativos exigem captura de tela ligada (:captura-de-tela?): é o
  canal cinemático da leitura (wrong-direction) — sem ele a leitura fica só
  no score. Determinístico: a lista é curada aqui, não gerada por LLM."
  (:require [aimscope.coach.catalog :as cat]
            [aimscope.coach.labels :as labels]))

(def sequencia
  [{:degraus ["VT Pasu Rasp Novice" "VT Pasu Rasp Intermediate" "VT Pasu Rasp Advanced"]
    :mede [:flick-tech/stability :click-timing/reading]}
   ;; a escada VT muda a contagem de alvos por tier (1w6ts -> 1w5ts -> 1w3ts)
   {:degraus ["VT 1w6ts Rasp Novice" "VT 1w5ts Rasp Intermediate" "VT 1w3ts Rasp Advanced"]
    :mede [:flick-tech/micro :flick-tech/post-flick]}
   {:degraus ["VT Smoothbot Novice" "VT Smoothbot Intermediate" "VT Smoothbot Advanced"]
    :mede [:control-tracking/arm :control-tracking/blending]}
   {:degraus ["VT PreciseOrb Novice" "VT PreciseOrb Intermediate" "VT PreciseOrb Advanced"]
    :mede [:control-tracking/wrist :control-tracking/fingertip]}
   {:degraus ["VT Air Novice" "VT Air Intermediate" "VT Air Advanced"]
    :mede [:reactive-tracking/reading :control-tracking/blending]
    :captura-de-tela? true}
   {:degraus ["VT skyTS Novice" "VT skyTS Intermediate" "VT skyTS Advanced"]
    :mede [:reactive-tracking/control :reactive-tracking/speed]
    :captura-de-tela? true}
   {:degraus ["VT psalmTS Novice" "VT psalmTS Intermediate" "VT psalmTS Advanced"]
    :mede [:click-timing/precision :click-timing/stability]}])

(def ^:private runs-por-degrau [1 2 2]) ; triagem 1x; degraus escalados 2x

(defn- teto?
  "Bateu no teto da régua do degrau: melhor score >= último ponto semeado
  (level-of clampa ali — a régua esgotou, não discrimina acima disso)."
  [thresholds scenario best]
  (when-let [{:keys [points]} (cat/threshold-for thresholds scenario)]
    (>= best (apply max (map second points)))))

(defn- item-status
  "Anda a escada de degraus: capou um degrau (e existe mais difícil) -> o
  item escala; senão o degrau atual é o alvo do jogador."
  [thresholds by-name {:keys [degraus mede captura-de-tela?]}]
  (loop [i 0]
    (let [scen    (nth degraus i)
          plays   (get by-name (cat/normalize-name scen) [])
          n       (count plays)
          need    (nth runs-por-degrau i (peek runs-por-degrau))
          best    (when (seq plays) (apply max (map :score plays)))
          capped  (boolean (and best (teto? thresholds scen best)))
          ultimo? (= i (dec (count degraus)))]
      (if (and capped (not ultimo?))
        (recur (inc i))
        {:scenario scen
         :estagio (inc i)
         :runs n
         :runs-alvo need
         :jogado? (or capped (>= n need))
         :teto? capped
         :mede mede
         :mede-labels (mapv labels/skill-nome mede)
         :captura-de-tela? (boolean captura-de-tela?)}))))

(defn status
  "Cruza a sequência com os scores já ingeridos -> pronto pra UI/narrativa:
  cada item com o CENÁRIO ATUAL da escada (:scenario), :estagio, :jogado?,
  runs feitas/alvo e a exigência de captura de tela. :completo? quando todo
  item está satisfeito no seu degrau atual."
  [scores thresholds]
  (let [by-name (group-by (comp cat/normalize-name :scenario) scores)
        itens   (mapv #(item-status thresholds by-name %) sequencia)]
    {:itens itens
     :estagio (apply max (map :estagio itens))
     :completo? (every? :jogado? itens)
     :faltam (count (remove :jogado? itens))
     :faltam-runs (reduce + (map #(if (:jogado? %) 0 (max 0 (- (:runs-alvo %) (:runs %))))
                                 itens))}))
