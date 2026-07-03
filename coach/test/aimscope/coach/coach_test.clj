(ns aimscope.coach.coach-test
  "Testes de domínio: o exemplo canônico do produto (Bounce 180 travado por
  smoothness de braço) precisa sair EXATAMENTE como especificado no design."
  (:require [clojure.test :refer [deftest is testing]]
            [aimscope.coach.catalog :as cat]
            [aimscope.coach.csvstats :as csv]
            [aimscope.coach.labels :as labels]
            [aimscope.coach.narrative :as nar]
            [aimscope.coach.objective :as obj]
            [aimscope.coach.placement :as placement]
            [aimscope.coach.plan :as plan]
            [aimscope.coach.profile :as profile]
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
  ;; ordem de match: viscose (subskills da taxonomia) antes do VT (genérico)
  (is (= :vs/ct-wrist (:id (cat/match-scenario catalog "VT Controlsphere Viscose Hard"))))
  (is (= :vs/ct-arm   (:id (cat/match-scenario catalog "Smoothsphere Viscose Easier"))))
  (is (= :vs/clk-reading (:id (cat/match-scenario catalog "Pasu Voltaic Reload Easy"))))
  (is (= :vs/ft-post-flick (:id (cat/match-scenario catalog "B180T Voltaic Easy"))))
  ;; nome fora da planilha viscose cai nas entradas genéricas
  (is (= :vt/pasu (:id (cat/match-scenario catalog "VT Pasu Viscose Advanced S5"))))
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
  ;; punho ótimo, braço péssimo — o cenário do exemplo do JP (taxonomia Viscose)
  {:control-tracking/wrist     {:value 78.0 :confidence 0.8 :n 5}
   :control-tracking/arm       {:value 31.0 :confidence 0.7 :n 5}
   :control-tracking/fingertip {:value 68.0 :confidence 0.7 :n 5}
   :control-tracking/blending  {:value 62.0 :confidence 0.4 :n 3}
   :reactive-tracking/control  {:value 60.0 :confidence 0.5 :n 3}
   :reactive-tracking/speed    {:value 61.0 :confidence 0.4 :n 3}
   :reactive-tracking/reading  {:value 65.0 :confidence 0.6 :n 5}
   :flick-tech/speed           {:value 70.0 :confidence 0.5 :n 3}
   :flick-tech/stability       {:value 72.0 :confidence 0.8 :n 5}
   :flick-tech/micro           {:value 74.0 :confidence 0.8 :n 5}
   :flick-tech/post-flick      {:value 69.0 :confidence 0.6 :n 4}
   :click-timing/reading       {:value nil :confidence 0.0 :n 0}
   :click-timing/precision     {:value 70.0 :confidence 0.6 :n 5}
   :click-timing/stability     {:value nil :confidence 0.0 :n 0}})

(deftest exemplo-canonico-bounce-180
  (testing "braço fraco ⇒ plano p/ Bounce 180 prescreve drill de smooth-arm"
    (let [target (first (filter #(= :com/bounce-180 (:id %)) catalog))
          p (plan/build-plan catalog {:player/goal :fixed-sens} skills-braço-fraco target)]
      (is (= :ok (:status p)))
      (let [scens (set (map :scenario (:steps p)))]
        ;; o drill canônico de braço agora é a família ct-arm da Viscose
        ;; (Smoothsphere/Whisphere/SmoothBot Perfected); os da comunidade
        ;; continuam válidos como alternativa
        (is (some #{:vs/ct-arm :com/smoothsphere :com/whisphere :com/smoothbot} scens)
            (str "esperava drill de tracking de braço, veio: " scens)))
      (testing "fixed-sens: NUNCA prescreve mudança de sens"
        (is (not-any? #(= :sens-change (:tipo %)) (:steps p)))))))

(deftest sens-range-permite-acao-de-sens
  (testing "políticas :range/:search têm mudança de sens no espaço de busca"
    (let [skills (assoc-in skills-braço-fraco [:flick-tech/micro :value] 30.0)
          target (first (filter #(= :vt/onewall-ts (:id %)) catalog))
          p-fixed  (plan/build-plan catalog {:player/sens-policy :fixed} skills target)
          p-range  (plan/build-plan catalog {:player/sens-policy :range} skills target)
          p-search (plan/build-plan catalog {:player/sens-policy :search} skills target)
          ;; legado pré-eixos ainda funciona (normalize na entrada)
          p-legacy (plan/build-plan catalog {:player/goal :sens-range} skills target)]
      (is (= :ok (:status p-fixed)))
      (is (not-any? #(= :sens-change (:tipo %)) (:steps p-fixed)))
      ;; range/search: a ação existe; com custo 120 ela só entra se for o
      ;; caminho barato — aqui só validamos que o plano continua ok
      (is (= :ok (:status p-range)))
      (is (= :ok (:status p-search)))
      (is (= :ok (:status p-legacy))))))

(deftest eixos-ortogonais-valorant-com-sens-fixa
  (testing "o caso que o campo único não representava (ADR 0005)"
    (let [prof {:player/sens-policy :fixed :player/game-target :transfer
                :player/game :valorant}
          target (first (filter #(= :com/bounce-180 (:id %)) catalog))
          p (plan/build-plan catalog prof skills-braço-fraco target)]
      (is (= :ok (:status p)))
      (is (not-any? #(= :sens-change (:tipo %)) (:steps p))
          "transferência NÃO abre ação de sens — eixos independentes"))))

(deftest perfil-legado-migra-para-eixos
  (let [p (profile/normalize {:player/goal :game-transfer})]
    (is (= :fixed (:player/sens-policy p)))
    (is (= :transfer (:player/game-target p)))
    (is (nil? (:player/goal p)) "campo único morre na normalização"))
  (let [p (profile/normalize {:player/goal :sens-range})]
    (is (= :range (:player/sens-policy p)))
    (is (= :kovaaks (:player/game-target p))))
  (testing "eixos explícitos vencem o legado"
    (let [p (profile/normalize
             {:player/goal :fixed-sens :player/sens-policy :search})]
      (is (= :search (:player/sens-policy p))))))

(deftest level-of-interpola-e-clampa
  (let [pts [[100 500] [200 600] [300 700] [400 800]]] ; [energia score]
    (is (= 50.0  (double (residual/level-of pts 250))))  ; abaixo do 1º: 0->100
    (is (= 100.0 (double (residual/level-of pts 500))))  ; exatamente 1º ponto
    (is (= 150.0 (double (residual/level-of pts 550))))  ; meio 100->200
    (is (= 400.0 (double (residual/level-of pts 800))))  ; último ponto
    (is (= 400.0 (double (residual/level-of pts 9999)))) ; acima: clampa no último
    (is (nil? (residual/level-of [] 500)))
    (is (nil? (residual/level-of nil 500)))))

(deftest scaled-actual-normaliza-por-escala
  ;; energia VT 0-1200 -> 0-100 (÷12); percentil passa direto
  (is (= (/ 400.0 12.0)
         (double (residual/scaled-actual {:scale :energy :points [[100 500] [400 800]]} 800))))
  (is (= 62.5
         (double (residual/scaled-actual {:scale :percentile :points [[1 5500] [62.5 13600]]} 13600))))
  (is (nil? (residual/scaled-actual {:scale :energy :points []} 800))))

(deftest thresholds-seed-carrega-e-pontua
  ;; validade: valores conhecidos das sheets têm que sair exatos
  (let [th (cat/load-thresholds)]
    (let [pasu (cat/threshold-for th "VT Pasu Rasp Novice")]
      (is (= :energy (:scale pasu)))
      (is (= 400.0 (double (residual/level-of (:points pasu) 850))))   ; gold novice
      (is (= (/ 400.0 12.0) (double (residual/scaled-actual pasu 850)))))
    (let [adv (cat/threshold-for th "VT Smoothbot Advanced")]
      (is (= 1200.0 (double (residual/level-of (:points adv) 4300))))) ; celestial
    (let [ss (cat/threshold-for th "Smoothsphere Viscose Easier")]
      (is (= :percentile (:scale ss)))
      (is (= 62.5 (double (residual/scaled-actual ss 13600)))))        ; Seal 37.5%
    ;; nome normaliza (caixa/espaços) e cenário sem régua -> nil
    (is (some? (cat/threshold-for th "  vt   pasu rasp novice ")))
    (is (nil? (cat/threshold-for th "cenário inexistente xyz")))))

;; ---------------------------------------------------------------------------
;; apresentação: rótulos, placement e narrativa (QA 2026-07-02 — zero jargão)
;; ---------------------------------------------------------------------------

(deftest labels-traduzem-todas-as-skills
  (doseq [s (cat/all-skills)]
    (is (not= (str s) (labels/skill-nome s)) (str "skill sem rótulo: " s))
    (is (some? (labels/skill-dica s)) (str "skill sem dica: " s)))
  ;; chave vinda de JSON (string) e desconhecida
  (is (= "Flick: micro" (labels/skill-nome "flick-tech/micro")))
  (is (= ":x/y" (labels/skill-nome :x/y)) "desconhecida não some, degrada visível"))

(deftest labels-de-cenario-derivam-do-id
  (is (= "Bounce 180" (labels/scenario-nome :com/bounce-180)))
  (is (= "Bounce 180" (labels/scenario-nome "com/bounce-180")))
  (is (= "Smoothsphere" (labels/scenario-nome :vs/smoothsphere))))

(deftest placement-cobre-o-espaco-de-skills-com-reguas
  (let [th (cat/load-thresholds)
        catalog (cat/load-catalog)]
    ;; TODOS os degraus da escada (Novice/Intermediate/Advanced) precisam de
    ;; régua semeada e match no catálogo — é o que torna a escalada honesta
    (doseq [{:keys [degraus]} placement/sequencia
            scenario degraus]
      (is (some? (cat/threshold-for th scenario))
          (str "degrau do placement sem régua semeada: " scenario))
      (is (some? (cat/match-scenario catalog scenario))
          (str "degrau do placement não casa no catálogo: " scenario)))
    ;; as skills aparecem em pelo menos um item? (cobertura declarada)
    (let [medidas (set (mapcat :mede placement/sequencia))]
      (is (>= (count medidas) 10)
          (str "placement mede só " (count medidas) " skills")))
    ;; cenários reativos exigem captura de tela (canal da leitura)
    (is (some :captura-de-tela? placement/sequencia))))

(def ^:private th-placement (cat/load-thresholds))

(deftest placement-status-marca-jogados
  (let [st (placement/status [{:scenario "vt pasu rasp novice" :score 700}]
                             th-placement)]
    (is (false? (:completo? st)))
    (is (= 1 (:estagio st)))
    (is (= (dec (count placement/sequencia)) (:faltam st)))
    (is (true? (:jogado? (first (filter #(= "VT Pasu Rasp Novice" (:scenario %))
                                        (:itens st))))))))

(deftest placement-escala-quando-bate-no-teto
  (testing "score no teto Novice (850 = nível 400) escala pro Intermediate ×2 runs"
    (let [st (placement/status [{:scenario "VT Pasu Rasp Novice" :score 850}]
                               th-placement)
          item (first (filter #(clojure.string/starts-with? (:scenario %) "VT Pasu")
                              (:itens st)))]
      (is (= "VT Pasu Rasp Intermediate" (:scenario item)))
      (is (= 2 (:estagio item)))
      (is (= 2 (:runs-alvo item)))
      (is (false? (:jogado? item)))
      (is (= 2 (:estagio st)))))
  (testing "capando o Intermediate, sobe pro Advanced"
    (let [st (placement/status [{:scenario "VT Pasu Rasp Novice" :score 850}
                                {:scenario "VT Pasu Rasp Intermediate" :score 1050}]
                               th-placement)
          item (first (filter #(clojure.string/starts-with? (:scenario %) "VT Pasu")
                              (:itens st)))]
      (is (= "VT Pasu Rasp Advanced" (:scenario item)))
      (is (= 3 (:estagio item)))))
  (testing "capando o Advanced, a escada esgotou: satisfeito com teto marcado"
    (let [st (placement/status [{:scenario "VT Pasu Rasp Novice" :score 850}
                                {:scenario "VT Pasu Rasp Intermediate" :score 1050}
                                {:scenario "VT Pasu Rasp Advanced" :score 1270}]
                               th-placement)
          item (first (filter #(clojure.string/starts-with? (:scenario %) "VT Pasu")
                              (:itens st)))]
      (is (true? (:jogado? item)))
      (is (true? (:teto? item)))))
  (testing "abaixo do teto: 1 run basta no estágio 1 (nível absoluto, ADR 0004)"
    (let [st (placement/status [{:scenario "VT Pasu Rasp Novice" :score 700}]
                               th-placement)
          item (first (filter #(clojure.string/starts-with? (:scenario %) "VT Pasu")
                              (:itens st)))]
      (is (= 1 (:estagio item)))
      (is (true? (:jogado? item)))
      (is (false? (:teto? item))))))

(def ^:private dados-narrativa
  {:diagnosis {:gargalo-global {:skill "reactive-tracking/reading" :value 33.4}
               :skills/ranked [{:skill "flick-tech/micro" :value 87.4}
                               {:skill "control-tracking/arm" :value 77.0}
                               {:skill "reactive-tracking/reading" :value 33.4}]
               :n-scores 42
               :placement {:completo? false :faltam 2
                           :itens [{:scenario "VT skyTS Novice" :jogado? false
                                    :mede-labels ["Reativo: controle" "Reativo: velocidade"]}
                                   {:scenario "VT Pasu Rasp Novice" :jogado? true
                                    :mede-labels ["Flick: estabilidade"]}]}}
   :plan {:status "ok" :target "com/bounce-180" :target-label "Bounce 180"
          :steps [{:minutes 15 :scenario "com/bounce-180" :scenario-label "Bounce 180"
                   :skill "reactive-tracking/reading" :expected-delta "+4"}]}
   :outcome {:veredito-geral "progrediu" :recomendacao "previsão se confirmando"}})

(deftest narrativa-deterministica-fala-lingua-de-gente
  (let [md (nar/narrativa-deterministica dados-narrativa)]
    (is (nar/narrativa-valida? md) "o próprio rascunho tem que passar no gate")
    (is (clojure.string/includes? md "Reativo: leitura"))
    (is (clojure.string/includes? md "Bounce 180"))
    (is (clojure.string/includes? md "Teste inicial"))
    (is (not (re-find nar/padrao-jargao md)) "zero chave interna no texto")))

(deftest narrativa-valida-reprova-jargao-e-estrutura
  (let [ok (nar/narrativa-deterministica dados-narrativa)]
    (is (not (nar/narrativa-valida? (str ok "\nfoque em reactive-tracking/reading!")))
        "jargão interno reprova")
    (is (not (nar/narrativa-valida? "## Diagnóstico\ncurto demais")))
    (is (not (nar/narrativa-valida? nil)))))

(deftest narrativa-sem-dados-nao-quebra
  (let [md (nar/narrativa-deterministica {})]
    (is (string? md))
    (is (clojure.string/includes? md "## Briefing"))
    (is (clojure.string/includes? md "## Diagnóstico"))
    (is (not (re-find nar/padrao-jargao md)))))

(deftest briefing-abre-pessoal-com-dados-reais
  (let [md (nar/narrativa-deterministica
            (assoc dados-narrativa
                   :profile {:player/name "João"}
                   :objective {:resumo-humano "Dominar a sua sens atual · foco: Técnica de flick"}
                   :benchmarks [{:nome "Voltaic S5 — Intermediate"
                                 :overall-rank 3 :overall-rank-name "Gold"
                                 :categories [{:scenarios [{:score 800.0 :tier 3}]}]}]))]
    (is (clojure.string/includes? md "## Briefing"))
    (is (clojure.string/includes? md "Olá, João!"))
    (is (clojure.string/includes? md "Gold") "nível = tier OFICIAL do benchmark")
    (is (clojure.string/includes? md "Dominar a sua sens atual"))
    (is (nar/narrativa-valida? md)))
  (testing "sem conta/nome: saudação genérica, nível não inventado"
    (let [md (nar/narrativa-deterministica dados-narrativa)]
      (is (clojure.string/includes? md "Olá!"))
      (is (clojure.string/includes? md "não vi seu rank oficial")))))

(deftest nivel-oficial-escolhe-bench-mais-jogado
  (is (nil? (nar/nivel-oficial nil)))
  (is (nil? (nar/nivel-oficial [{:nome "X" :overall-rank 0 :overall-rank-name "No Rank"
                                 :categories []}]))
      "rank 0 = sem rank: nunca vira nível")
  (let [n (nar/nivel-oficial
           [{:nome "Viscose S2 — Medium" :overall-rank 2 :overall-rank-name "Rayon"
             :categories [{:scenarios [{:score 1.0 :tier 2}]}]}
            {:nome "Voltaic S5 — Intermediate" :overall-rank 3 :overall-rank-name "Gold"
             :categories [{:scenarios [{:score 1.0 :tier 3} {:score 2.0 :tier 3}]}]}])]
    (is (= "Gold" (:rank n)) "o benchmark com mais cenários jogados dá o nível")))

;; ---------------------------------------------------------------------------
;; objetivo em linguagem natural (CONTEXT.md: Objetivo)
;; ---------------------------------------------------------------------------

(deftest objetivo-parser-deterministico
  (let [m (obj/parse-deterministico
           "quero melhorar minha mira no Valorant sem trocar de sens, focando em flicks")]
    (is (= :fixed (:sens-policy m)) "'sem trocar sens' NÃO conflita com o jogo")
    (is (= :transfer (:game-target m)) "eixos ortogonais: fixed + transfer coexistem")
    (is (= :valorant (:game m)))
    (is (some #{"flick-tech"} (:focus m))))
  (let [m (obj/parse-deterministico "quero subir no valorant, aceito testar outra sens")]
    (is (= :range (:sens-policy m)))
    (is (= :valorant (:game m))))
  (let [m (obj/parse-deterministico "procuro a melhor sens possivel pra mim")]
    (is (= :search (:sens-policy m))))
  (let [m (obj/parse-deterministico "melhorar tracking e cliques no cs2")]
    (is (= :fixed (:sens-policy m)) "sem menção a sens: política default")
    (is (= :transfer (:game-target m)))
    (is (= :cs2 (:game m)))
    (is (= #{"control-tracking" "click-timing"} (set (:focus m)))))
  ;; texto vazio nunca quebra
  (is (some? (obj/parse-deterministico ""))))

(deftest objetivo-valida-gate-do-llm
  (is (nil? (obj/valida nil)))
  (is (nil? (obj/valida {:sens-policy "politica-inventada"}))
      "política fora do vocabulário reprova")
  (let [v (obj/valida {"sens-policy" "fixed" "game" "valorant"
                       "focus" ["flick-tech" "categoria-inventada"]
                       "resumo" "foco em flicks pro Valorant"})]
    (is (= :fixed (:sens-policy v)))
    (is (= :transfer (:game-target v)))
    (is (= ["flick-tech"] (:focus v)) "categoria inventada é filtrada, não aceita")
    (is (= :valorant (:game v))))
  ;; goal-mode legado (LLM/arquivo antigos) ainda é aceito, mapeado pra eixos
  (is (= :range (:sens-policy (obj/valida {"goal-mode" "sens-range"}))))
  ;; jogo desconhecido degrada pra :geral em vez de reprovar tudo
  (is (= :geral (:game (obj/valida {"sens-policy" "fixed" "game" "fortnite"})))))

(deftest objetivo-vira-patch-de-perfil
  (let [patch (obj/objective->profile-patch
               {:sens-policy :fixed :game-target :transfer :game :valorant
                :focus ["flick-tech"]})]
    (is (= :fixed (:player/sens-policy patch)))
    (is (= :transfer (:player/game-target patch)))
    (is (= ["flick-tech"] (:player/focus-categories patch))))
  (is (clojure.string/includes?
       (obj/resumo-humano {:sens-policy :fixed :game :geral :game-target :kovaaks
                           :focus ["flick-tech"]})
       "Técnica de flick")))

(deftest skills-sem-evidencia-ficam-fora
  (let [est (skills/estimate [] [] catalog (cat/load-thresholds))]
    (is (nil? (get-in est [:click-timing/reading :value])))
    (is (zero? (get-in est [:click-timing/reading :confidence])))))

;; ---------------------------------------------------------------------------
;; canal de score ABSOLUTO por tiers + tendência separada (ADR 0004)
;; ---------------------------------------------------------------------------

(deftest score-evidencia-e-absoluta-pela-regua
  (let [th (cat/load-thresholds)]
    (testing "1 run já gera nível absoluto (régua de energia: 850 = 400/12)"
      (let [ev (skills/score-evidence
                [{:scenario "VT Pasu Rasp Novice" :catalog-id :vt4/pasu
                  :score 850 :played-at "2026-07-01T10:00:00"}]
                catalog th)]
        (is (seq ev))
        (let [{:keys [value confidence]} (first (get ev :flick-tech/stability))]
          (is (= (/ 400.0 12.0) (double value)) "nível = energia/12, absoluto")
          (is (< 0.0 confidence 0.5) "1 play: evidência válida, confiança reduzida"))))
    (testing "caso do item 1: score alto em cenário de LEITURA -> nível alto"
      ;; top ~37.5% no Air Pure (940 = nível 62.5 na régua percentil) tem que
      ;; sair ~62, nunca 23 — o z relativo não é mais nível
      (let [ev (skills/score-evidence
                [{:scenario "Air Pure Easier No UFO" :catalog-id :vs/rt-reading
                  :score 940 :played-at "2026-07-01T10:00:00"}]
                catalog th)
            v (:value (first (get ev :reactive-tracking/reading)))]
        (is (> v 60.0) (str "leitura com score alto saiu " v))))
    (testing "queda recente NÃO derruba o nível (best score na régua)"
      (let [runs (map-indexed (fn [i s] {:scenario "Air Pure Easier No UFO"
                                         :catalog-id :vs/rt-reading :score s
                                         :played-at (str "2026-07-0" (inc i) "T10:00:00")})
                              [940 920 900])   ; caindo vs. si mesmo
            ev (skills/score-evidence (vec runs) catalog th)
            v (:value (first (get ev :reactive-tracking/reading)))]
        (is (> v 60.0) "tendência de queda é assunto do trend, não do nível")))
    (testing "cenário sem régua semeada não gera nível (nunca z relativo)"
      (is (empty? (skills/score-evidence
                   [{:scenario "Bounce 180" :catalog-id :com/bounce-180
                     :score 999 :played-at "2026-07-01T10:00:00"}]
                   catalog th))))))

(deftest tendencia-e-direcional-e-separada
  (let [runs (fn [scores]
               (vec (map-indexed
                     (fn [i s] {:scenario "Air Pure Easier No UFO"
                                :catalog-id :vs/rt-reading :score s
                                :played-at (str "2026-07-0" (inc i) "T10:00:00")})
                     scores)))]
    (is (= :up   (get-in (skills/trend (runs [900 910 940]) catalog)
                         [:reactive-tracking/reading :dir])))
    (is (= :down (get-in (skills/trend (runs [940 930 890]) catalog)
                         [:reactive-tracking/reading :dir])))
    (testing "menos de 3 plays: sem tendência (nunca inventamos direção)"
      (is (empty? (skills/trend (runs [900 940]) catalog))))))

(deftest reaction-simple-entra-com-screen-data
  (let [ev (skills/kinematic-evidence
            {:n_bouts 80
             :bouts_summary {:n_corrections {:median 1.0}}
             :reaction {:rt_median_ms 190.0 :n_matched 45}})]
    (is (some? (get-in ev [:click-timing/reading :value])))
    (is (> (get-in ev [:click-timing/reading :value]) 70.0))     ; 190ms = bom
    (is (= 1.0 (get-in ev [:click-timing/reading :confidence]))) ; 45/30 clampado
    ;; RT lento pontua baixo
    (let [lento (skills/kinematic-evidence
                 {:n_bouts 80 :bouts_summary {}
                  :reaction {:rt_median_ms 420.0 :n_matched 45}})]
      (is (< (get-in lento [:click-timing/reading :value]) 30.0)))))

(deftest choice-e-pursuit-viram-evidencia
  (let [ev (skills/kinematic-evidence
            {:n_bouts 80 :bouts_summary {}
             :reaction_choice {:wrong_direction_rate 0.05 :n 30}
             :pursuit {:realign_median_ms 160.0 :n_matched 30}})]
    (is (> (get-in ev [:reactive-tracking/reading :value]) 80.0))
    (is (> (get-in ev [:reactive-tracking/control :value]) 80.0))
    (let [ruim (skills/kinematic-evidence
                {:n_bouts 80 :bouts_summary {}
                 :reaction_choice {:wrong_direction_rate 0.45 :n 30}
                 :pursuit {:realign_median_ms 480.0 :n_matched 30}})]
      (is (< (get-in ruim [:reactive-tracking/reading :value]) 25.0))
      (is (< (get-in ruim [:reactive-tracking/control :value]) 25.0)))))

;; ---------------------------------------------------------------------------
;; âncoras calibradas pelo lab (anchors.edn) — fallback e confiança (ADR 0003)
;; ---------------------------------------------------------------------------

(deftest anchors-fallback-pro-provisorio
  ;; sem anchors.edn no classpath (estado atual do repo), o merge devolve o
  ;; provisório intacto — coach offline nunca depende do lab
  (is (= skills/provisional-anchors
         (skills/merge-calibrated skills/provisional-anchors nil)))
  (is (= skills/provisional-anchors
         (skills/merge-calibrated skills/provisional-anchors {}))))

(deftest anchors-calibrado-substitui-por-chave
  (let [file-map {:anchors/source "population.db 2026-07-02 (3 vods)"
                  :anchors {:sparc [[-4.0 5.0] [-1.0 95.0]]}
                  :meta {:sparc {:n 240 :low-n false}}}
        merged (skills/merge-calibrated skills/provisional-anchors file-map)]
    (is (= [[-4.0 5.0] [-1.0 95.0]] (:sparc merged)) "chave calibrada entra")
    (is (= (:overshoot skills/provisional-anchors) (:overshoot merged))
        "chave que não passou no gate continua provisória")))

(deftest anchor-confidence-marca-amostra-pobre
  ;; provisória / sem meta: confiança cheia (o prior já assume incerteza)
  (is (= 1.0 (skills/anchor-confidence nil :sparc)))
  (is (= 1.0 (skills/anchor-confidence {} :sparc)))
  ;; calibrada com n alto: cheia; n baixo: reduzida mas NUNCA zerada
  (is (= 1.0 (skills/anchor-confidence {:sparc {:n 240}} :sparc)))
  (is (= 0.5 (skills/anchor-confidence {:sparc {:n 20}} :sparc)))
  (is (= 0.3 (skills/anchor-confidence {:sparc {:n 2}} :sparc))))

(deftest endurance-usa-ancora-nomeada
  ;; a tabela inline virou :endurance-eff (calibrável pelo lab — Tier 1)
  (let [ev (skills/kinematic-evidence
            {:n_bouts 80 :bouts_summary {}
             :halves {:first {:efficiency 0.9} :second {:efficiency 0.9}}})]
    (is (some? (get-in ev [:control-tracking/blending :value])))
    (is (= 70.0 (double (get-in ev [:control-tracking/blending :value])))
        "razão 1.0 cai no ponto [1.0 70.0] da âncora"))
  (let [degradou (skills/kinematic-evidence
                  {:n_bouts 80 :bouts_summary {}
                   :halves {:first {:efficiency 0.95} :second {:efficiency 0.80}}})]
    (is (< (get-in degradou [:control-tracking/blending :value]) 40.0))))

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
    (is (> (get-in bom  [:flick-tech/stability :value])
           (get-in ruim [:flick-tech/stability :value])))
    (is (> (get-in bom  [:control-tracking/fingertip :value])
           (get-in ruim [:control-tracking/fingertip :value])))))
