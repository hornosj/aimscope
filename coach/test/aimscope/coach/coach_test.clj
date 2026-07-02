(ns aimscope.coach.coach-test
  "Testes de domínio: o exemplo canônico do produto (Bounce 180 travado por
  smoothness de braço) precisa sair EXATAMENTE como especificado no design."
  (:require [clojure.test :refer [deftest is testing]]
            [aimscope.coach.catalog :as cat]
            [aimscope.coach.csvstats :as csv]
            [aimscope.coach.plan :as plan]
            [aimscope.coach.residual :as residual]
            [aimscope.coach.skills :as skills])
  (:import [java.io File]))

(def catalog (cat/load-catalog))

(deftest catalogo-carrega-e-casa-nomes
  (is (> (count catalog) 30))
  (is (= :vt/pasu (:id (cat/match-scenario catalog "VT Pasu Rasp S5 Intermediate"))))
  (is (= :com/bounce-180 (:id (cat/match-scenario catalog "Bounce 180 Tracking"))))
  (is (nil? (cat/match-scenario catalog "mapa desconhecido qualquer"))))

(deftest viscose-vence-vt-quando-nome-tem-viscose
  ;; ordem de match: viscose (específico) antes do VT (genérico)
  (is (= :vs/controlsphere (:id (cat/match-scenario catalog "VT Controlsphere Viscose Hard"))))
  (is (= :vs/pasu          (:id (cat/match-scenario catalog "VT Pasu Viscose Advanced S5"))))
  (is (= :vs/smoothsphere  (:id (cat/match-scenario catalog "Smoothsphere Viscose Easier"))))
  ;; sem "viscose" no nome, cai nas entradas genéricas
  (is (= :vt/controlsphere (:id (cat/match-scenario catalog "VT Controlsphere Intermediate S5"))))
  (is (= :com/smoothsphere (:id (cat/match-scenario catalog "Smoothsphere")))))

(deftest csv-parse
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "aimscope-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (clojure.java.io/file dir "x - Challenge - 2026.07.01-13.31.04 Stats.csv")]
    (spit f "Kill #,Timestamp,Bot\n1,13:30:00.100,bot\n\nScore:,123.5\nScenario:,Bounce 180\nHits:,40\nShots:,80\nSens Scale:,Valorant\nHoriz Sens:,0.4\n")
    (let [r (csv/parse-file f)]
      (is (= "Bounce 180" (:scenario r)))
      (is (= 123.5 (:score r)))
      (is (= 0.5 (double (:accuracy r))))
      (is (clojure.string/starts-with? (:played-at r) "2026-07-01T13:31:04")))))

(def skills-braço-fraco
  ;; punho ótimo, braço péssimo — o cenário do exemplo do JP
  {:tracking/smooth-wrist  {:value 78.0 :confidence 0.8 :n 5}
   :tracking/smooth-arm    {:value 31.0 :confidence 0.7 :n 5}
   :precision/micro-adjust {:value 74.0 :confidence 0.8 :n 5}
   :click/timing           {:value 70.0 :confidence 0.6 :n 5}
   :orientation/spatial    {:value 65.0 :confidence 0.6 :n 5}
   :acquisition/ballistic  {:value 72.0 :confidence 0.8 :n 5}
   :stability/tremor       {:value 68.0 :confidence 0.7 :n 5}
   :tracking/reactive      {:value 60.0 :confidence 0.5 :n 3}
   :consistency/endurance  {:value 62.0 :confidence 0.4 :n 3}
   :reaction/simple        {:value nil :confidence 0.0 :n 0}
   :reaction/choice        {:value nil :confidence 0.0 :n 0}})

(deftest exemplo-canonico-bounce-180
  (testing "braço fraco ⇒ plano p/ Bounce 180 prescreve drill de smooth-arm"
    (let [target (first (filter #(= :com/bounce-180 (:id %)) catalog))
          p (plan/build-plan catalog {:player/goal :fixed-sens} skills-braço-fraco target)]
      (is (= :ok (:status p)))
      (let [scens (set (map :scenario (:steps p)))]
        (is (some #{:com/smoothsphere :com/whisphere :com/smoothbot} scens)
            (str "esperava drill de smooth-arm, veio: " scens)))
      (testing "fixed-sens: NUNCA prescreve mudança de sens"
        (is (not-any? #(= :sens-change (:tipo %)) (:steps p)))))))

(deftest sens-range-permite-acao-de-sens
  (testing "no modo sens-range a mudança de sens EXISTE no espaço de busca"
    (let [skills (assoc-in skills-braço-fraco [:precision/micro-adjust :value] 30.0)
          target (first (filter #(= :vt/onewall-ts (:id %)) catalog))
          p-fixed (plan/build-plan catalog {:player/goal :fixed-sens} skills target)
          p-range (plan/build-plan catalog {:player/goal :sens-range} skills target)]
      (is (= :ok (:status p-fixed)))
      (is (not-any? #(= :sens-change (:tipo %)) (:steps p-fixed)))
      ;; range: a ação existe; com custo 120 ela só entra se for o caminho
      ;; barato — aqui só validamos que o plano continua ok
      (is (= :ok (:status p-range))))))

(deftest energia-vt-interpola-e-clampa
  (let [th {:iron 500 :bronze 600 :silver 700 :gold 800}]
    (is (= 50.0  (double (residual/energy th 250))))   ; abaixo do iron: 0->100
    (is (= 100.0 (double (residual/energy th 500))))   ; exatamente iron
    (is (= 150.0 (double (residual/energy th 550))))   ; meio iron->bronze
    (is (= 400.0 (double (residual/energy th 800))))   ; gold
    (is (= 1200.0 (double (residual/energy th 9999)))) ; teto
    (is (nil? (residual/energy {} 500)))               ; :pending -> nil
    (is (nil? (residual/energy nil 500)))))

(deftest skills-sem-evidencia-ficam-fora
  (let [est (skills/estimate [] [] catalog)]
    (is (nil? (get-in est [:reaction/simple :value])))
    (is (zero? (get-in est [:reaction/simple :confidence])))))

(deftest reaction-simple-entra-com-screen-data
  (let [ev (skills/kinematic-evidence
            {:n_bouts 80
             :bouts_summary {:n_corrections {:median 1.0}}
             :reaction {:rt_median_ms 190.0 :n_matched 45}})]
    (is (some? (get-in ev [:reaction/simple :value])))
    (is (> (get-in ev [:reaction/simple :value]) 70.0))     ; 190ms = bom
    (is (= 1.0 (get-in ev [:reaction/simple :confidence]))) ; 45/30 clampado
    ;; RT lento pontua baixo
    (let [lento (skills/kinematic-evidence
                 {:n_bouts 80 :bouts_summary {}
                  :reaction {:rt_median_ms 420.0 :n_matched 45}})]
      (is (< (get-in lento [:reaction/simple :value]) 30.0)))))

(deftest choice-e-pursuit-viram-evidencia
  (let [ev (skills/kinematic-evidence
            {:n_bouts 80 :bouts_summary {}
             :reaction_choice {:wrong_direction_rate 0.05 :n 30}
             :pursuit {:realign_median_ms 160.0 :n_matched 30}})]
    (is (> (get-in ev [:reaction/choice :value]) 80.0))
    (is (> (get-in ev [:tracking/reactive :value]) 80.0))
    (let [ruim (skills/kinematic-evidence
                {:n_bouts 80 :bouts_summary {}
                 :reaction_choice {:wrong_direction_rate 0.45 :n 30}
                 :pursuit {:realign_median_ms 480.0 :n_matched 30}})]
      (is (< (get-in ruim [:reaction/choice :value]) 25.0))
      (is (< (get-in ruim [:tracking/reactive :value]) 25.0)))))

(deftest evidencia-cinematica-mapeia-direcao-certa
  (let [bom  (skills/kinematic-evidence
              {:n_bouts 80
               :bouts_summary {:n_corrections {:median 0.0} :overshoot_ratio {:median 1.01}
                               :time_peak_to_end_ms {:median 90.0}}
               :tremor {:band_power_ratio 0.08}})
        ruim (skills/kinematic-evidence
              {:n_bouts 80
               :bouts_summary {:n_corrections {:median 3.0} :overshoot_ratio {:median 1.25}
                               :time_peak_to_end_ms {:median 500.0}}
               :tremor {:band_power_ratio 0.55}})]
    (is (> (get-in bom  [:acquisition/ballistic :value])
           (get-in ruim [:acquisition/ballistic :value])))
    (is (> (get-in bom  [:stability/tremor :value])
           (get-in ruim [:stability/tremor :value])))))
