(ns aimscope.coach.narrative
  "Conteúdo da narrativa do coach — PURO (sem embabel/LLM), logo testável no
  ambiente deps.edn. O texto determinístico daqui é a fonte da verdade; o
  agente (insight_agent, fat-jar) usa o LLM só pra polir a prosa e valida a
  saída com narrativa-valida? antes de aceitar (QA 2026-07-02: linguagem
  natural, zero jargão interno, anti-alucinação por construção)."
  (:require [aimscope.coach.labels :as labels]
            [clojure.string :as str]))

(defn- fmt-skill [k] (str "**" (labels/skill-nome k) "**"))

(defn categoria-do-skill
  "Chave de skill -> categoria de dica de execução (labels/dicas-execucao).
  Taxonomia Viscose: tracking (control/reactive), flick = switching, clique."
  [k]
  (case (namespace (keyword k))
    ("control-tracking" "reactive-tracking") :tracking
    ("flick-tech")                           :switching
    ("click-timing")                         :clicking
    :geral))

(defn- secao-diagnostico [{:keys [gargalo-global n-scores] :as diagnosis}]
  (let [ranked (get diagnosis :skills/ranked)
        g (:skill gargalo-global)
        v (:value gargalo-global)
        fortes (->> ranked
                    (filter #(> (or (:value %) 0) 60))
                    (sort-by :value >)
                    (take 2))]
    (if (nil? g)
      "Ainda não medi o suficiente pra apontar um ponto fraco com confiança — jogue o teste inicial abaixo com a gravação ligada."
      (str "Seu ponto mais fraco agora é " (fmt-skill g)
           (when v (format " (%.0f de 100 — o neutro é 50)" (double v))) ". "
           "Em termos práticos: " (or (labels/skill-dica g) "essa habilidade") ". "
           (when (seq fortes)
             (str "Do outro lado, "
                  (str/join " e " (map #(str (fmt-skill (:skill %))
                                             (format " (%.0f)" (double (:value %))))
                                       fortes))
                  " já são pontos fortes — o treino não vai mexer neles. "))
           (when (and n-scores (pos? n-scores))
             (format "Isso sai de %d scores seus, não de achismo." n-scores))))))

(defn- secao-plano [{:keys [status target-label steps target]} gargalo]
  (case (str status)
    "ok"
    (str (when gargalo
           (str "Todo o plano existe pra destravar UMA coisa: subir seu "
                (fmt-skill gargalo) ".\n\n"))
         (str/join "\n"
                   (map (fn [{:keys [minutes scenario-label scenario skill expected-delta]}]
                          (str "- **" (or minutes 15) " min de "
                               (or scenario-label (labels/scenario-nome scenario)) "**"
                               " → treina " (fmt-skill skill)
                               (when expected-delta (str " (esperado: " expected-delta ")"))))
                        steps)))
    "ja-destravado"
    (str "Boa notícia: suas habilidades já batem o requisito de **"
         (or target-label (some-> target labels/scenario-nome) "seu cenário-alvo")
         "**. Não precisa de treino preparatório — jogue o cenário e registre scores.")
    "Sem plano ativo no momento — atualize o coach depois da próxima sessão."))

(defn- secao-execucao [steps]
  (let [cats (into #{} (map (comp categoria-do-skill :skill)) steps)]
    (str/join "\n"
              (concat
               (for [c (sort (disj cats :geral))]
                 (str "- " (labels/dicas-execucao c)))
               [(str "- " (labels/dicas-execucao :geral))]))))

(defn- secao-alerta [{:keys [veredito-geral recomendacao]}]
  (case (str veredito-geral)
    "plato-detectado" (str "O último plano estagnou (" recomendacao "). "
                           "Se o mesmo acontecer com este, atualize o coach: ele re-roteia sozinho.")
    "progrediu"       "O plano anterior está funcionando — mantenha. Se dois treinos seguidos não moverem o número do seu ponto fraco, atualize o coach."
    "Se depois de ~6 sessões o seu ponto fraco não subir, o plano não está transferindo — atualize o coach pra re-rotear."))

(defn- secao-placement [{:keys [completo? faltam itens]}]
  (when (and (some? completo?) (not completo?))
    (str "\n\n## Teste inicial (" faltam " de " (count itens) " faltando)\n"
         "Antes de confiar no diagnóstico completo, jogue 1x cada um destes — "
         "cada mapa mede um par de habilidades diferente:\n"
         (str/join "\n"
                   (for [{:keys [scenario jogado? mede-labels]} itens]
                     (str "- " (if jogado? "✅" "⬜") " **" scenario "** — mede "
                          (str/join " + " mede-labels)))))))

(defn narrativa-deterministica
  "Markdown completo SEM LLM: rótulos pt-BR, zero chave interna."
  [{:keys [diagnosis plan outcome]}]
  (let [gargalo (get-in diagnosis [:gargalo-global :skill])]
    (str "## Diagnóstico\n" (secao-diagnostico diagnosis) "\n"
         "\n## O plano e o porquê\n" (secao-plano plan gargalo) "\n"
         "\n## Como executar\n" (secao-execucao (:steps plan)) "\n"
         "\n## Sinal de alerta\n" (secao-alerta outcome) "\n"
         (secao-placement (:placement diagnosis)))))

(def padrao-jargao
  "Identificador interno vazando (reaction/choice, com/bounce-180…): letras,
  barra, letras — nunca deveria aparecer em texto pro usuário."
  #"[a-zA-Z]{2,}/[a-zA-Z][a-zA-Z0-9-]*")

(defn narrativa-valida?
  "Gate do LLM: mantém as 4 seções, não vaza jargão interno, tamanho são."
  [texto]
  (and (string? texto)
       (< 200 (count texto) 4000)
       (every? #(str/includes? texto %)
               ["## Diagnóstico" "## O plano" "## Como executar" "## Sinal de alerta"])
       (not (re-find padrao-jargao texto))))
