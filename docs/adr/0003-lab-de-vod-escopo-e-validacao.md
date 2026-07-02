# 0003 — Lab de VOD: escopo das âncoras e validação por ground-truth

Data: 2026-07-02 · Status: aceito (design; implementação delegada)

## Contexto

As âncoras do coach (métrica cinemática → skill 0–100, em `skills.clj`) são um
prior provisório hardcoded. A ideia de calibrá-las com vídeos de runs de rank
conhecido ("treinar o app com VODs") é atraente, mas vídeo de terceiros não tem
o mouse real nem o FOV do jogador. Precisávamos fixar até onde o vídeo pode ir
com honestidade, antes de qualquer código. Design completo em
`docs/design-vod-lab.md`; termos em `CONTEXT.md`.

## Decisão

1. **Só métricas adimensionais.** Sem o FOV do VOD, o movimento sai em pixels,
   não em graus. O lab calibra apenas assinaturas invariantes a escala (overshoot,
   correções, SPARC, timings, lag, direção). Ficam FORA: amplitude em graus e o
   proxy punho/braço (precisam de FOV), e tremor/micro (finos demais no vídeo).
2. **Dois tiers.** Tier 1 usa só o estimador de câmera (ego-motion: ballistic,
   smooth-arm, endurance). Tier 2 exige detecção de alvo no vídeo (reaction/*,
   reactive, click/timing) e por isso embute o modelo de visão — vem depois.
3. **Gate de ground-truth.** Nenhuma âncora é publicada sem validar o estimador
   (e, no Tier 2, o detector) contra as runs do PRÓPRIO usuário, onde o mouse
   real e a tela são gravados juntos. É o mesmo princípio "injeta sinal conhecido,
   exige recuperação" do resto do projeto: a âncora é calibrada com métrica de
   VÍDEO mas aplicada a métrica de SENSOR — as duas extrações têm que concordar.
4. **Só dado curado versionado chega ao produto.** O lab é Python offline; o coach
   consome um `anchors.edn` versionado (como já faz com thresholds). Zero ML na
   JVM, zero banco externo no runtime do coach.

## Justificativa

- Assumir um FOV default envenena silenciosamente justo as métricas de magnitude;
  estimar FOV por vídeo é um segundo problema de visão dentro do primeiro. Cortar
  para o adimensional é o único caminho honesto de baixo risco.
- Separar Tier 1/Tier 2 evita arrastar um modelo de visão inteiro para dentro do
  primeiro entregável, e alinha o Tier 2 com o dataset de visão da fase Valorant
  (reaproveita trabalho em vez de duplicar).
- O gate de ground-truth é o que torna a calibração *sólida* em vez de plausível:
  sem ele, âncora e runtime ficam em sistemas de coordenadas diferentes.
- `anchors.edn` versionado mantém a arquitetura já provada (thresholds), com diff
  auditável e distribuição limpa (ADR 0001, mesmo espírito).

## Critérios objetivos de reabertura

- FOV/sens do VOD passarem a ser conhecidos de forma confiável (ex.: fonte com
  config pública em escala) → reabrir as âncoras de magnitude e o proxy muscular.
- Estimador de câmera provar erro baixo mesmo em flicks rápidos sob compressão →
  reconsiderar `acquisition/ballistic` por vídeo com mais confiança.

## Consequências

- `skills.clj` passa a carregar âncoras de arquivo (fallback pro provisório).
- `population.db` (append-only, na máquina do lab) guarda observação por VOD; só
  o `anchors.edn` destilado é versionado.
- O objetivo "pesos cenário→skill" fica FORA do lab de vídeo: é regressão sobre a
  matriz de scores da API kovaaks, caminho separado sem visão computacional.
