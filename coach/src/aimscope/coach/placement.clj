(ns aimscope.coach.placement
  "Balanceamento: sequência curta e FIXA de cenários que varre o espaço das 11
  skills pra dar evidência inicial ao coach (pedido do JP, QA 2026-07-02).
  Todos têm régua semeada no catálogo (VT S4 Novice — acessível a qualquer
  rank) e regex de skills, então cada score já vira evidência calibrada.
  Determinístico: a lista é curada aqui, não gerada por LLM."
  (:require [aimscope.coach.catalog :as cat]
            [aimscope.coach.labels :as labels]))

(def sequencia
  [{:scenario "VT Pasu Rasp Novice"      :mede [:flick-tech/stability :click-timing/reading]}
   {:scenario "VT 1w6ts Rasp Novice"     :mede [:flick-tech/micro :flick-tech/post-flick]}
   {:scenario "VT Smoothbot Novice"      :mede [:control-tracking/arm :control-tracking/blending]}
   {:scenario "VT PreciseOrb Novice"     :mede [:control-tracking/wrist :control-tracking/fingertip]}
   {:scenario "VT Air Novice"            :mede [:reactive-tracking/reading :control-tracking/blending]}
   {:scenario "VT skyTS Novice"          :mede [:reactive-tracking/control :reactive-tracking/speed]}
   {:scenario "VT psalmTS Novice"        :mede [:click-timing/precision :click-timing/stability]}])

(defn status
  "Cruza a sequência com os scores já ingeridos -> pronto pra UI:
  cada item com :jogado? e rótulos; :completo? quando tudo foi jogado 1x."
  [scores]
  (let [jogados (into #{} (map (comp cat/normalize-name :scenario)) scores)
        itens   (mapv (fn [{:keys [scenario mede]}]
                        {:scenario scenario
                         :jogado?  (contains? jogados (cat/normalize-name scenario))
                         :mede     mede
                         :mede-labels (mapv labels/skill-nome mede)})
                      sequencia)]
    {:itens itens
     :completo? (every? :jogado? itens)
     :faltam (count (remove :jogado? itens))}))
