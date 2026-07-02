# 0004 — Régua absoluta (tiers) para skills score-driven; tendência é outra coisa

Data: 2026-07-02 · Status: aceito

## Contexto

O estimador funde duas correntes de evidência por skill: âncoras cinemáticas
(absolutas, 0–100) e um canal de score que calculava um z-score **relativo ao
próprio histórico do jogador** (`skills.clj`). Skills sem canal cinemático
(reactive-reading, flick-speed, reactive-speed, click-stability) ficavam 100%
nesse z relativo — e a UI exibia os dois na mesma barra 0–100. Resultado real:
"Reativo: leitura 23" ao lado de "Reativo: controle 91" num jogador top 3% do
mundo, porque 23 significava "seus scores recentes caíram vs. a sua média",
não "você é ruim de leitura". Duas réguas incompatíveis no mesmo eixo.

## Decisão

1. **Nível score-driven é sempre ABSOLUTO.** O canal de score converte o score
   pela régua de tiers do benchmark (a mesma da energia: thresholds do catálogo
   ou régua oficial da API) em 0–100. Uma run já é evidência válida — o
   requisito de ≥3 plays era artefato do z.
2. **O z relativo não morre: vira Tendência.** Variação recente vs. a própria
   média é um indicador direcional (↑↓) exibido separado, nunca fundido nem
   exibido como nível de skill.
3. **Consequência direta no placement:** com nível absoluto por run única, o
   placement pode ser adaptativo em 2 estágios (triagem em Novice ×1 run;
   categoria que bate no teto da régua escala pra dificuldade maior ×2 runs).

## Justificativa

Tendência-vs-si-mesmo responde "estou melhorando?"; nível absoluto responde
"onde estou?". O diagnóstico, o gargalo e o plano precisam da segunda pergunta;
misturar as duas numa barra só produz números que parecem alucinação e
contaminam o resíduo. A alternativa de esconder skills score-driven ("sem
medida ainda") foi rejeitada: a régua de tiers existe, é oficial e já é usada
pra energia — não usá-la é jogar informação fora.
