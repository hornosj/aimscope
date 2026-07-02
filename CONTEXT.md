# CONTEXT — glossário do aimscope

Termos canônicos do domínio. Sem detalhes de implementação.

- **Sessão** — um período contínuo de gravação (diretório autodescritivo:
  manifest + mouse.parquet + CSVs + metrics.json). Unidade de ingestão.
- **Bout** — um movimento intencional de mouse detectado por histerese de
  velocidade. Unidade de análise cinemática; métricas nunca são calculadas
  sobre o trace inteiro.
- **Métrica cinemática** — medida de *mecanismo* extraída do trace de uma sessão
  (overshoot, correções, SPARC, tremor, eficiência). Produzida pelo **sensor**.
- **Sensor** — o par gravador (Rust) + análise por sessão (Python). Só mede;
  não opina, não prescreve, não guarda estado do jogador.
- **Skill latente** — habilidade não-observável estimada a partir de evidências
  (scores + métricas cinemáticas). Vocabulário fechado de 11:
  `:reaction/simple`, `:reaction/choice`, `:acquisition/ballistic`,
  `:precision/micro-adjust`, `:click/timing`, `:tracking/smooth-wrist`,
  `:tracking/smooth-arm`, `:tracking/reactive`, `:stability/tremor`,
  `:orientation/spatial`, `:consistency/endurance`.
- **Grupo muscular (proxy)** — inferido pela amplitude do bout, não observado:
  <5° ≈ punho/dedos, >30° ≈ braço. É proxy declarado, não medida anatômica.
- **Cenário** — um mapa jogável do KovaaK's. Tem entrada no catálogo.
- **Catálogo** — base curada de cenários com suas chaves (categoria, skills com
  pesos, faixa de amplitude, thresholds de rank). Versionado por temporada.
- **Benchmark** — conjunto oficial de cenários com sistema de rank (Voltaic S5,
  Viscose S2). Score em cenário de benchmark vira **energia**.
- **Energia** — score normalizado (100–1200) pela régua da Voltaic; rank geral é
  média harmônica das energias por subcategoria.
- **Coach** — o cérebro (Clojure): estima skills, faz análise de resíduo, gera
  diagnóstico e plano de treino (GOAP). Único escritor do estado do jogador.
- **Diagnóstico** — saída do coach: skills estimadas + gargalo identificado com
  evidência cinemática.
- **Plano** — saída do coach: sequência de cenários a grindar com previsão de
  efeito. Toda previsão é conferida depois (previsto × realizado).
- **Estado do jogador** — histórico de scores, série temporal de skills, planos
  e previsões. Vive no store of record (SQLite); o coach consulta via datalog
  em memória.
- **Perfil de objetivo** — declaração do jogador que restringe prescrições.
  Três modos: `:fixed-sens` (coach nunca sugere mudar sens), `:sens-range`
  (mudança de sens é action de plano, com custo, dentro do range em cm/360),
  `:game-transfer` (prioriza skills que transferem pro Valorant; prior de
  baixa confiança até existir telemetria visual). Alvo de rank é um goal,
  ortogonal ao modo.
- **Insight** — observação FACTUAL por sessão produzida pelo sensor (ex.:
  "overshoot 12% acima do seu histórico"). Não prescreve nada; prescrição é
  monopólio do coach.
