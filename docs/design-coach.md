# aimscope coach — skill graph, catálogo de benchmarks e prescrição de treino

> Rascunho de design (2026-07-01). Decisões em aberto marcadas com ⚖️ — ver sessão de grill.

## 1. O que "dicas de mira" realmente são (reflexão)

Três camadas distintas que o produto hoje mistura:

1. **Diagnóstico (mecanismo)** — o que o gravador mede: overshoot, correções,
   tremor, eficiência, aquisição. São *causas* no nível motor.
2. **Skills latentes (constructo)** — clusters que a comunidade validou na prática:
   clicking (static/dynamic), tracking (precise/reactive/control), switching
   (speed/evasive/stability). Benchmarks medem *resultado* por cluster, não causa.
3. **Prescrição (plano)** — mapear (skill fraca + objetivo) → cenários a grindar.

**A tese do produto**: ninguém liga as camadas 1 e 2. Benchmark diz "seu switching
é fraco"; o aimscope diz "seu switching é fraco PORQUE sua smoothness de braço
degrada em amplitudes > 30° — aqui está a assinatura cinemática — grinde
Smoothsphere/Whisphere e re-teste Bounce 180 em 2 semanas". E aí a killer feature:
**a prescrição é uma hipótese que o próprio app verifica depois** ("grindou X,
Y moveu?"). Nenhum aim trainer fecha esse loop.

## 2. O que a pesquisa diz (honestidade primeiro)

- **Voltaic S5** (KovaaK's): 3 tiers (Novice/Intermediate/Advanced), categorias
  Clicking/Tracking/Switching com subcategorias (static, dynamic, precise,
  reactive, control, speed, evasive, stability). 12 ranks (Iron→Celestial),
  energia 100–1200 linear, e **rank geral = média harmônica das energias por
  subcategoria** — a comunidade já codifica "seu rank é seu elo mais fraco",
  exatamente a filosofia do "destravar" ([blog VT S5](https://blog.voltaic.gg/announcing-the-voltaic-season-5-aiming-benchmarks-beta-for-kovaaks/),
  [leaderboards](https://app.voltaic.gg/leaderboards/about), [Medium S4](https://voltaic.medium.com/voltaic-kovaaks-benchmarks-season-4-35f3e3fb7512)).
- **Viscose S2** (abril/2026): benchmarks mais bem conceituados do momento junto
  com VT (input do JP); estrutura detalhada em sheet oficial — seed manual
  ([evxl](https://evxl.app/u/Viscose/Viscose%20Benchmarks/Easier), [X](https://x.com/ViscoseOCE/status/2048841430010081721)).
- **evxl.app**: agrega VT, Viscose, Aimerz+, MIRA etc. **Bloqueia scraping**
  (Cloudflare 403, sem API pública) ([evxl](https://evxl.app/)).
- **Ciência**: existe validação do KovaaK's como *plataforma de medida*
  ([Frontiers 2024 — confiabilidade em micro/macro flicking, strafe tracking](https://www.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1309991/full)),
  efeito de latência ([NVIDIA, 15k jogadores](https://developer.nvidia.com/blog/improving-player-performance-with-low-latency-as-evident-from-fps-aim-trainer-experiments/))
  e de cafeína. **Não existe estudo causal "mapa X destrava mapa Y"** — isso é
  lore de coach. Consequência de design: a matriz cenário→skill entra como
  **prior curado**, e a correlação real é aprendida **nos dados do próprio
  usuário** (que é mais ciência do que a lore, não menos).

## 3. Taxonomia — as chaves por cenário (catálogo)

```clojure
{:scenario/id        :vt-s5/pentabounce-advanced
 :scenario/name      "PentaBounce Advanced"
 :benchmark          :voltaic-s5          ; ou :viscose-s2
 :tier               :advanced
 :category           :switching
 :subcategory        :stability
 :skills             {:tracking/smooth 0.35, :click/timing 0.25,
                      :orientation/spatial 0.25, :precision/micro-adjust 0.15}
 :muscle-group       {:arm 0.7, :wrist 0.3}   ; derivado do amplitude-profile
 :amplitude-profile  :large                    ; :micro <2° | :small 2-10° | :medium 10-40° | :large >40°
 :target-kinematics  :bouncing-predictable     ; :static :constant-vel :strafing :erratic
 :pressure           #{:decay}                 ; :timed :reload :decay
 :rank-thresholds    {:iron 500 ... :celestial 1200}
 :prescription/notes "..."}                    ; curado de guias
```

**Vocabulário de skills latentes** (as "chaves" de verdade — ⚖️ validar):

| skill | evidência cinemática (gravador) | evidência de score |
|---|---|---|
| `:reaction/simple` | onset visual → onset de movimento (**precisa DXGI**) | cenários de reflex |
| `:reaction/choice` | taxa de direção inicial errada (**precisa DXGI**) | evasive |
| `:acquisition/ballistic` | erro do 1º movimento → nº de correções | dynamic clicking |
| `:precision/micro-adjust` | tempo pico→clique, correções em amplitude micro | static small |
| `:click/timing` | Δt mira-no-alvo → clique | static/speed switching |
| `:tracking/smooth` | SPARC/jerk em bouts longos de baixa freq. | precise/smooth tracking |
| `:tracking/reactive` | lag de re-aquisição pós-mudança de direção (**DXGI**) | reactive/evasive |
| `:stability/tremor` | potência 8–12 Hz em janelas quietas | precise tracking |
| `:orientation/spatial` | métricas em bouts de amplitude :large | 180s, bounce |
| `:consistency/endurance` | variância das métricas ao longo da sessão | qualquer, fim vs início |

**Grupo muscular**: não dá pra ver o braço — usamos **amplitude como proxy
medido** (< ~5° ≈ punho/dedos; > ~30° ≈ braço). Defensável e automático; as
métricas cinemáticas passam a ser reportadas POR FAIXA DE AMPLITUDE (a régua
que separa "smoothness de punho ok, de braço ruim").

**Métricas novas pedidas pelo JP** (viabilidade honesta):
- tremor, TTK ✅ já temos
- tempo de reação ao alvo / à resposta visual ⚠️ exige **captura de tela (DXGI)** — puxa o roadmap v0.3 pra frente
- "perda de movimento" (alvo muda imprevisível → accuracy despenca) = *pursuit
  re-acquisition lag* + pico de erro ⚠️ exige DXGI (posição do alvo)
- "reagiu rápido demais" = onset < ~100 ms pós-estímulo (antecipação/chute) ⚠️ DXGI
- decisão incoerente com o target = direção inicial errada do movimento ⚠️ DXGI
- Sem DXGI dá versão parcial via CSV per-kill + cinemática (accuracy por kill ×
  assinatura do bout precedente).

## 4. Arquitetura de dados

```
catalog/                  EDN curado por temporada (VT S5, Viscose S2) — versionado no repo
user.db (SQLite)          sessions, session_metrics, scores (TODO CSV vira score,
                          não só benchmark), skill_estimates (série temporal)
coach/ (Clojure+embabel)  lê user.db + catalog → diagnosis.edn + plan.edn
```

- **Catálogo curado à mão, não scraping**: evxl 403a, catálogo é pequeno
  (~20 cenários × 2 benchmarks × temporada) e muda 1×/temporada. Fontes: sheets
  oficiais VT/Viscose. evxl fica como contexto global opcional (percentil), nunca dependência. ⚖️
- **SQLite como store of record**: 1 arquivo, zero ops, legível por Rust, Python
  e Clojure (org.xerial). O lado Clojure pode carregar em Datascript em memória
  para as queries datalog de correlação. ⚖️ (alternativa: Datalevin datalog-first)
- **Já colhemos os CSVs do KovaaK's por sessão** → cada CSV é um score datado de
  um cenário. O usuário treina normalmente e o histórico de scores se constrói
  sozinho, sem submeter benchmark em site nenhum.

## 5. Motor de correlação + GOAP (a parte mirabolante)

**Diagnóstico (dataflow, NÃO GOAP)**:
1. Score de cenário → energia (fórmula VT) → atualiza skills latentes via pesos
   `:skills` do catálogo (EWMA no início; modelo de fatores quando houver dados).
2. Cinemática → skills diretamente (tabela acima), por faixa de amplitude.
3. **Análise de resíduo**: "sua energia em Bounce 180 está 180 abaixo do que o
   resto do seu perfil prevê; a skill com maior déficit que carrega em Bounce 180
   é :tracking/smooth em amplitude :large" → gargalo identificado COM evidência.

**Plano (GOAP/embabel — encaixe legítimo)**:
- Estado do mundo = skills atuais + tempo disponível + flags de platô
- Goal = "energia(Bounce 180) ≥ X" ou "rank Jade no VT S5"
- Actions = "grindar cenário S por N min" com `:action/pre` (gates de skill:
  evasive só paga depois de smoothness ≥ limiar), `:action/post` (deltas
  esperados de skill), custo = tempo × utilidade marginal (retorno decrescente)
- A* acha o caminho mais barato pelo grafo de skills = o "destravar" do JP
- Polos de falha à la hunter-v2: `skill/plateaued?` dispara replanejamento
  para mapas-prerequisito. Mesmo padrão do embabel.clj (tags em vars, dados puros).
- **Fechamento de loop**: cada plan.edn guarda a previsão; 2 semanas depois o
  coach compara previsto × realizado e ajusta os pesos `:skills` DO USUÁRIO.

**Por que Clojure aqui é defensável** (e não só amor): o domínio é literalmente
mapas + grafo de dependências + replanejamento; roda offline/batch (nunca no
caminho da captura); comunicação = arquivos (metrics.json/SQLite entram,
plan.edn/JSON sai — Rust só exibe). Custo real: 3º runtime (JVM) no monorepo. ⚖️

## 5.5 Perfil do jogador: objetivo e sens (input do JP)

Toda prescrição passa por um **filtro de objetivo** persistido no perfil:

```clojure
{:player/goal      :fixed-sens        ; ou :sens-range, :rank-target, :game-transfer
 :player/sens      {:value 0.4 :scale :valorant :dpi 800}   ; cm/360 canônico
 :player/sens-range [25.0 45.0]       ; cm/360, só se :sens-range
 :player/target    {:benchmark :voltaic-s5 :rank :jade}     ; opcional
 :player/time-budget-min 45}          ; por dia, para o custo do GOAP
```

Regras duras:
- `:fixed-sens` → o coach **nunca** prescreve mudança de sens; gargalos que
  seriam "sens alta demais" viram treino de freio/controle naquela sens.
- `:sens-range` → mudanças de sens entram como ACTION do GOAP (custo alto:
  re-adaptação ~dias), dentro do range declarado.
- Toda métrica comparada entre sessões é indexada por cm/360 — score histórico
  com sens diferente não se mistura sem normalização.
- ⚠️ Bug de design atual: `insights.py` sugere "teste sens 10–15% menor" sem
  conhecer o objetivo — precisa ser gateado pelo perfil.

**evxl como fonte por mapa** (input do JP): as páginas de cenário do evxl têm
dados valiosos (distribuição de scores, plays, ranks por cenário) que
enriqueceriam o prior do catálogo e o percentil do jogador. Bloqueio técnico
atual: 403 server-side (Cloudflare). Caminhos possíveis, em ordem de decência:
(a) checar se existe API/JSON público não documentado; (b) pedir permissão/chave
ao dev do evxl; (c) o próprio usuário exporta/cola a página do perfil dele;
(d) só catálogo curado. ⚖️

## 6. Fluxo completo

```
recorder (Rust) ──► session/ ──► sidecar (Python) ──► metrics.json ──┐
KovaaK's CSVs ───► ingester ──► user.db (SQLite) ◄───────────────────┤
catalog/*.edn ────────────────────────────► coach (Clojure/GOAP) ◄───┘
                                              │
                              diagnosis.edn + plan.edn
                                              │
                               UI egui + report.html
```

## 7. Decisões — RESOLVIDAS no grill de 2026-07-01

1. **Storage**: SQLite append-only (nunca UPDATE, só INSERT com tx_time) +
   datascript em memória no coach. Datomic avaliado e rejeitado com critérios
   de reabertura → **ADR 0001**.
2. **Escopo do Clojure**: Python = sensor puro (metrics.json); skills + resíduo
   + GOAP inteiros em Clojure → **ADR 0002**.
3. **Sequência**: coach v1 ANTES do DXGI; DXGI logo depois adiciona
   reaction/choice/reactive ao mesmo grafo.
4. **Catálogo**: curadoria manual das sheets oficiais (VT S5 + Viscose S2),
   1×/temporada.
   **Nota 2026-07-01**: Viscose S2 SEMEADO via API kovaaks.com (14 famílias ×
   tiers Entry/Easier/base/Medium/Hard/Advanced/Expert, de 349 cenários
   retornados); pesos de skills curados, thresholds seguem :pending (sheet).
5. **Skills**: os 10 aprovados + split `:tracking/smooth` →
   `:tracking/smooth-wrist` e `:tracking/smooth-arm` (11 no total). Proxy de
   grupo muscular por amplitude aprovado (<5°≈punho/dedos, >30°≈braço).
6. **Prescrição**: coach manda; insights.py vira observação FACTUAL por sessão
   (nada de conselho — corrige o bug da sugestão de sens sem perfil).
7. **Monorepo**: coach/ dentro deste repo, deps.edn próprio.
8. **evxl**: sem scraping; percentil global via export/cole manual do perfil
   pelo próprio usuário.
   **EMENDA 2026-07-01** (achado do JP: [333moxy/kovaaker](https://github.com/333moxy/kovaaker)):
   a API oficial do webapp `kovaaks.com/webapp-backend/*` é ABERTA (JSON, sem
   auth, sem Cloudflare — verificado ao vivo: 61.489 cenários com aimType,
   plays, authors, topScore; leaderboards globais paginados). Ela SUBSTITUI o
   export manual do evxl como fonte primária de: (a) percentil global — via
   leaderboard por cenário, filtro de posição pessoal; (b) scaffolding do
   catálogo — metadados de cenário e playlists de benchmark (endpoint de
   playlist confirmado no wrapper, mas deu timeout no probe: validar na
   implementação). O que CONTINUA manual: pesos `:skills` e thresholds de rank
   (vivem nas sheets VT/Viscose, não na API). Não dependemos da lib kovaaker
   (2 commits, ~3 arquivos): cliente próprio no coach (~50 linhas), com cache
   local OBRIGATÓRIO e rate polido — API é aberta porém não documentada.
   evxl rebaixado a fonte opcional de contexto.
9. **Modos de goal (3)**: `:fixed-sens`, `:sens-range` (mudança de sens = action
   GOAP com custo de re-adaptação, em cm/360), `:game-transfer` (prior curado
   de coaching Valorant, marcado BAIXA CONFIANÇA até a fase de visão). Alvo de
   rank é GOAL do GOAP, ortogonal ao modo.
10. **embabel-clj**: adapter copiado do beautiful-linkedin para coach/;
    extração de lib compartilhada só quando o shape estiver provado nos dois
    domínios.
11. **Calibração de âncoras por VOD** (grill 2026-07-02): as âncoras
    cinemática→skill (hoje prior provisório em `skills.clj`) serão calibradas na
    população por um pipeline OFFLINE de vídeo. Escopo, dois tiers e gate de
    ground-truth em `docs/design-vod-lab.md`; decisão de escopo em ADR 0003.
    Régua de nível = energia (thresholds semeados 2026-07-02).
```
