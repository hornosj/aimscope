(ns aimscope.coach.labels
  "Camada de APRESENTAÇÃO: chave interna -> nome que um jogador entende.
  As chaves de skill (:flick-tech/micro) e ids de cenário (:vs/click-reading)
  são vocabulário de MÁQUINA (CONTEXT.md); nenhum texto voltado ao usuário
  — UI ou narrativa — pode vazá-las. Este ns é o único tradutor.

  Vocabulário 2026-07-02: taxonomia do benchmark Viscose S2 (QA do JP) —
  4 categorias × subcategorias = 14 skills folha."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; categorias (namespaces das skills)
;; ---------------------------------------------------------------------------

(def categoria-labels
  {"control-tracking"  "Tracking de controle"
   "reactive-tracking" "Tracking reativo"
   "flick-tech"        "Técnica de flick"
   "click-timing"      "Timing de clique"})

(defn categoria-nome [k]
  (get categoria-labels (namespace (keyword k)) (str (namespace (keyword k)))))

;; ---------------------------------------------------------------------------
;; skills: nome curto + explicação de 1 linha (tooltip/narrativa)
;; ---------------------------------------------------------------------------

(def skill-labels
  {;; -------- Control Tracking (suavidade sustentada) --------
   :control-tracking/arm       {:nome "Tracking de braço"
                                :dica "acompanhar alvos amplos e lentos com o braço, suave e constante"}
   :control-tracking/wrist     {:nome "Tracking de pulso"
                                :dica "controle suave em movimentos médios, dominado pelo pulso"}
   :control-tracking/fingertip {:nome "Tracking de dedos"
                                :dica "micro-correções finas de dedos pra ficar colado no alvo"}
   :control-tracking/blending  {:nome "Tracking combinado"
                                :dica "misturar braço, pulso e dedos sem quebrar a suavidade — e sustentar isso"}
   ;; -------- Reactive Tracking (alvo imprevisível) --------
   :reactive-tracking/control  {:nome "Reativo: controle"
                                :dica "segurar a mira em alvos que mudam de direção sem aviso"}
   :reactive-tracking/speed    {:nome "Reativo: velocidade"
                                :dica "recolar no alvo imediatamente quando ele dispara"}
   :reactive-tracking/reading  {:nome "Reativo: leitura"
                                :dica "ler o padrão do alvo e antecipar, em vez de só correr atrás"}
   ;; -------- Flick Tech (o arremesso da mira) --------
   :flick-tech/speed           {:nome "Flick: velocidade"
                                :dica "chegar rápido no alvo com um único arremesso de mira"}
   :flick-tech/stability       {:nome "Flick: estabilidade"
                                :dica "flicks que param no alvo, sem passar do ponto"}
   :flick-tech/micro           {:nome "Flick: micro"
                                :dica "flicks curtinhos e cirúrgicos em alvos próximos"}
   :flick-tech/post-flick      {:nome "Pós-flick"
                                :dica "a correção imediata depois do flick, até cravar o alvo"}
   ;; -------- Click Timing (o instante do disparo) --------
   :click-timing/reading       {:nome "Clique: leitura"
                                :dica "ler o movimento do alvo e clicar na hora exata"}
   :click-timing/precision     {:nome "Clique: precisão"
                                :dica "alinhar fino antes do clique, sem atropelar"}
   :click-timing/stability     {:nome "Clique: consistência"
                                :dica "manter a taxa de acerto estável, clique após clique"}})

(defn skill-nome
  "Nome amigável de uma skill; a própria chave se desconhecida (nunca esconde)."
  [k]
  (get-in skill-labels [(keyword k) :nome] (str k)))

(defn skill-dica [k]
  (get-in skill-labels [(keyword k) :dica]))

;; ---------------------------------------------------------------------------
;; cenários: id do catálogo -> nome jogável
;; ---------------------------------------------------------------------------

(defn scenario-nome
  "Id de cenário (:com/bounce-180, \"vs/click-reading\") -> nome legível
  (\"Bounce 180\"). Deriva do próprio id: o catálogo não guarda display name."
  [id]
  (let [raw (name (keyword id))]
    (->> (str/split raw #"-")
         (map (fn [w] (if (re-matches #"\d+.*" w) w (str/capitalize w))))
         (str/join " "))))

;; ---------------------------------------------------------------------------
;; dicas de execução por categoria de treino (narrativa determinística)
;; ---------------------------------------------------------------------------

(def dicas-execucao
  {:clicking "mire primeiro, clique depois: velocidade sem acerto não pontua. Comece 10% mais lento que o seu normal e acelere só quando a taxa de acerto passar de 80%."
   :tracking "cole no alvo e NÃO solte: errar acompanhando vale mais que acertar chicoteando. Braço relaxado, pulso firme."
   :switching "olhe o próximo alvo ANTES de mover a mira — o olho lidera, a mão segue."
   :geral "pausa de 30s a cada 3 minutos; treino cansado consolida vício, não skill."})
