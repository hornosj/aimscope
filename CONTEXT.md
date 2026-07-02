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
  (scores + métricas cinemáticas). Vocabulário fechado de 14, seguindo a
  taxonomia do benchmark Viscose (4 categorias × subcategorias):
  *Control Tracking* `:control-tracking/arm|wrist|fingertip|blending`;
  *Reactive Tracking* `:reactive-tracking/control|speed|reading`;
  *Flick Tech* `:flick-tech/speed|stability|micro|post-flick`;
  *Click Timing* `:click-timing/reading|precision|stability`.
  Skills sem canal cinemático no sensor (reactive-reading, flick-speed,
  reactive-speed, click-stability) são score-driven até o canal existir.
  Nível score-driven é sempre ABSOLUTO (régua de tiers do benchmark, a mesma
  da energia) — nunca a tendência relativa ao próprio histórico.
- **Tendência** — variação recente de score vs. a própria média do jogador
  (por cenário/skill). Indicador direcional separado; nunca é exibida nem
  fundida como nível de skill.
- **Grupo muscular (proxy)** — inferido pela amplitude do bout, não observado:
  <5° ≈ punho/dedos, >30° ≈ braço. É proxy declarado, não medida anatômica.
- **Cenário** — um mapa jogável do KovaaK's. Tem entrada no catálogo.
- **Catálogo** — base curada de cenários com suas chaves (categoria, skills com
  pesos, faixa de amplitude, thresholds de rank). Versionado por temporada.
- **Benchmark** — conjunto oficial de cenários com sistema de rank (Voltaic S5,
  Viscose S2). Score em cenário de benchmark vira **energia**.
- **Pontuações oficiais** — os scores do jogador registrados no webapp
  kovaaks.com, por benchmark (categoria → cenário, com a régua de tiers
  oficial). A conta de entrada é a **Steam** (link/ID64/vanity) — o app resolve
  o steamId e busca sozinho; ninguém precisa conhecer o kovaaks.com. Régua
  oficial da API > thresholds semeados quando o jogador tem conta.
- **Fraco (benchmark)** — cenário jogado cujo tier fica abaixo da mediana dos
  tiers do próprio jogador naquele benchmark. A régua é o próprio jogador,
  não a população: ficar pra trás numa coluna do bench é o sinal.
- **Guia** — leitura transversal dos benchmarks: nível médio (tier) por skill
  dominante, do mais fraco pro mais forte, com os mapas que treinam cada uma.
  É o "o que atacar primeiro" — plano de visão geral, distinto do **Plano**
  (GOAP, sequência com previsão).
- **Coach IA (chat)** — conversa em linguagem natural com um coach de mira
  (LLM) cujo contexto são os dados REAIS do jogador (diagnóstico + benchmarks
  + cenário clicado). Orienta e explica; nunca inventa número — o que não foi
  medido é dito como não medido.
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
- **Perfil de objetivo** — declaração do jogador em três eixos ORTOGONAIS
  (não um modo único):
  1. **Política de sens** — `:fixed` ("já tenho uma sens": coach nunca propõe
     mudança), `:range` ("tenho um range": mudar sens dentro do range é action
     de plano, com custo), `:search` ("procuro a melhor sens": experimentos de
     sens são a via de calibração).
  2. **Alvo de jogo** — `:kovaaks` puro ou `:valorant-transfer` (prioriza
     skills que transferem; prior de baixa confiança até existir telemetria
     visual).
  3. **Foco** — categorias de skill declaradas.
  Política fixa não amordaça o coach: ele pode comentar conhecimento geral de
  população ("sens baixa costuma dar flick mais consistente e leitura mais
  difícil"), só não prescreve mudança direta. O alvo do plano não é escolhido
  pelo jogador: o plano mira automaticamente o drill da skill mais fraca com
  evidência.
- **Experimento de sens** — período declarado (pelo coach ou pelo jogador) de
  runs numa sens diferente da habitual, em cenários que refletem skills
  distintas. Runs são atribuídas ao experimento pela sens registrada na
  própria run. Veredito só após um mínimo de runs por cenário, sempre POR
  skill (melhorou/piorou/neutro + o que isso ensina) — nunca "aprovada ou
  reprovada" global; queda inicial por falta de adaptação é esperada e
  descontada. Na política `:search`, a sequência de experimentos É a
  calibração de sens. Só existe sob política `:range` ou `:search`.
- **Faixa de sens** — vocabulário canônico em cm/360: **alta** < 40,
  **média** 40–60, **baixa** > 60. Valores extremos (< 10 ou > 120) são
  atípicos, não uma faixa própria.
- **Objetivo (linguagem natural)** — texto livre do jogador ("quero melhorar
  minha mira no Valorant sem trocar de sens") interpretado em um mapa
  estruturado (os três eixos do perfil de objetivo: política de sens + alvo
  de jogo + foco) por LLM com validação e fallback determinístico por regras.
  O mapa sobrepõe o perfil e repondera custos do plano; o texto nunca é
  executado, só interpretado.
- **Placement (calibração do perfil)** — sequência curada de cenários que gera
  a evidência inicial das skills do jogador. Adaptativa em dois estágios:
  triagem em dificuldade baixa (1 run por cenário), e escalada de dificuldade
  por categoria quando o jogador bate no teto da régua (2 runs no estágio
  alto). Cenários reativos exigem captura de tela para medir leitura por
  mecanismo. Distinta da calibração de *âncoras* (que é do lab).
- **Briefing** — a narrativa única do produto: texto pessoal do coach pro
  jogador com saudação (nome do perfil), nível (tier oficial do benchmark),
  objetivo, pontos fortes/fracos e o que jogar agora, com cenários acionáveis.
  Substitui a antiga "explicação do coach". Montado deterministicamente a
  partir dos dados reais; LLM só refina a redação (com validação e fallback).
- **Insight** — observação FACTUAL por sessão produzida pelo sensor (ex.:
  "overshoot 12% acima do seu histórico"). Não prescreve nada; prescrição é
  monopólio do coach. Não confundir com **Leitura do coach**.
- **Leitura do coach** — a prosa (LLM) que acompanha quase toda saída
  determinística voltada ao usuário (briefing, painel de cenário, diário de
  sens…): traduz dado e decisão em linguagem humana. Sempre ancorada nos
  números reais, com validação e fallback determinístico; o LLM redige,
  nunca decide.
- **Âncora** — mapa de uma *métrica cinemática* para um nível de skill 0–100. É a
  régua que traduz mecanismo em skill latente. Hoje é um prior provisório
  (hardcoded); o **lab** substitui por versão calibrada na população.
- **Lab** — pipeline OFFLINE (Python, fora do produto) que calibra âncoras a
  partir de VODs de rank conhecido. Nunca roda no caminho de captura nem no coach;
  seu único artefato para o produto é um arquivo de âncoras versionado.
- **Estimador de câmera** — componente do lab que recupera o movimento angular da
  câmera (proxy do mouse de terceiros) a partir do vídeo. Produz traço em pixels
  por padrão; só vira graus com FOV conhecido. Roll ≈ 0 (FPS não rotaciona).
- **População** — distribuição de métricas por faixa de rank, agregada de muitos
  jogadores (VODs). É contra ela que as âncoras são calibradas. O **rank de um
  VOD** é derivado do score na tela (OCR) pela régua de energia, não do título.
- **Ground truth (do estimador)** — as runs do próprio usuário, onde o mouse real
  (mouse.parquet) e a tela são gravados juntos: o único dado onde o movimento
  verdadeiro é conhecido, usado pra validar o estimador antes de confiar em VODs.
