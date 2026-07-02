# aimscope `lab/` — calibração de âncoras por VOD

> Design de 2026-07-02 (grill). Escopo escrito pra ser **delegado a um modelo mais
> forte** implementar. Nada aqui roda no caminho de captura nem na JVM do coach:
> é offline, em Python, e o único artefato que entra no produto é um `anchors.edn`
> versionado. Termos em CONTEXT.md; decisão de escopo em `docs/adr/0003`.

## 0. Uma frase

As tabelas de âncora do coach (`skills.clj`, mapa `anchors`, hoje **hardcoded e
PROVISÓRIO**) mapeiam *métrica cinemática → nível de skill 0–100*. O `lab/`
substitui esses chutes por curvas **calibradas na população**, extraindo métrica
de vídeos de runs de rank conhecido — usando a **energia como ground-truth do
nível de skill** (a régua que semeamos em 2026-07-02).

## 1. Por que VOD (e o que NÃO é VOD)

Três objetivos foram propostos; eles têm dependências de dados **diferentes** e
NÃO devem virar um pipeline só:

| Objetivo | Precisa de vídeo? | Onde vive |
|---|---|---|
| (a) âncoras **cinemática→skill** | **SIM** — só o vídeo dá o movimento de mouse de terceiros | este documento |
| (b) pesos **cenário→skill** (regressão) | **NÃO** — é matriz de scores (jogador×cenário), sai da API kovaaks | caminho separado, ver §7 |
| (c) dataset de **visão** (Valorant: kill feed, recoil) | SIM, outro animal | fase Valorant, fora daqui |

**Decisão:** o `lab/` v1 mira só (a). (b) é um script de regressão sobre
leaderboards da API, sem tocar em vídeo (esboço em §7).

## 2. Duas fontes, dois papéis (NÃO confundir)

1. **Suas próprias runs = GROUND TRUTH.** O app já grava `mouse.parquet`
   (contagens reais + QPC) **e** a tela. Seu FOV e sens são conhecidos → dá pra
   converter o mouse.parquet no **traço angular verdadeiro** e comparar com o que
   o estimador tira do vídeo *da mesma run*. É o "sinal injetado" da cultura de
   teste do projeto: o estimador só é confiável depois de recuperar o teu mouse.
2. **YouTube = POPULAÇÃO.** `yt-dlp` em runs públicas de benchmark. O estimador
   já validado roda aqui pra montar a distribuição por rank. Rank/energia vêm de
   **OCR do score na tela** (não do título), passado pela régua semeada.

### 2.1 O ponto de correção mais importante (não pule)

A âncora é **calibrada com métrica extraída de VÍDEO** mas, em produção, é
**aplicada a métrica extraída do `mouse.parquet`** (sensor). Pra isso ser válido,
as duas extrações têm que dar o MESMO número pra mesma run. A validação nas suas
runs (§2.1 é o gate) precisa estabelecer, por métrica:
- viés desprezível entre `metric(vídeo)` e `metric(mouse.parquet)`, **ou**
- uma função de correção `metric_video → metric_sensor` aplicada antes de calibrar.

Sem esse gate, a âncora fica num sistema de coordenadas e o runtime em outro.
**Nenhuma âncora vai pro `anchors.edn` sem passar por ele.**

## 3. FOV desconhecido ⇒ só métricas adimensionais

Sem o FOV do jogador do YouTube, o fluxo óptico dá movimento em *pixels/frame*,
não em *graus* (graus = f(pixels, FOV)). Logo:

- **Sobrevivem** (invariantes a escala): razão de overshoot, nº de correções,
  SPARC/suavidade, timings (ms), lag de reaquisição, direção (certa/errada).
- **NÃO sobrevivem** (precisam de grau absoluto): amplitude em graus → o **proxy
  punho/braço** do CONTEXT.md; velocidade em °/s.
- **Já fora por serem finos demais** (sub-pixel/alta-freq no vídeo comprimido):
  `stability/tremor` (8–12 Hz) e `precision/micro-adjust`.

Consequência: o proxy de grupo muscular por amplitude (<5°/>30°) **não é
calibrável por VOD**. Continua vindo só das suas runs (mouse real).

## 4. Os dois tiers de âncora

### Tier 1 — ego-motion puro (SÓ estimador de câmera, sem detectar alvo)

Calcula as métricas sobre os **bouts do próprio traço angular** — mesma
matemática do sensor (`sidecar/`), só trocando a fonte do traço. Não precisa
saber onde o alvo está.

| Skill | Métrica (adimensional) | Já existe no sensor? |
|---|---|---|
| `:acquisition/ballistic` | overshoot_ratio + n_corrections dos bouts | sim (`bouts_summary`) |
| `:tracking/smooth-arm` | SPARC dos bouts LONGOS (baixa freq.) | sim (`bouts_by_amplitude`) |
| `:consistency/endurance` | degradação 1ª→2ª metade (variância no tempo) | sim (`halves`) |

**Como fazer:**
1. Estimador de câmera (§5) → traço angular yaw/pitch (mesmo se em pixels; para
   Tier 1 as métricas são adimensionais, então escala não importa DESDE que
   consistente dentro da run).
2. Extração de bout: reusar a histerese de velocidade do sensor (CONTEXT: Bout).
   Portar a função do `sidecar/` pra rodar sobre o traço angular.
3. Computar overshoot_ratio, n_corrections, SPARC por bout; agregar (mediana).
4. Rotular a run com energia (OCR do score → régua). Empilhar `(métrica, energia)`.

### Tier 2 — precisa detectar o alvo no vídeo (puxa o dataset de visão junto)

Estas métricas são definidas *em relação ao alvo* (posição/aparição na tela),
então exigem um detector de alvo por frame — que é basicamente o objetivo (c).

| Skill | Métrica | O que o detector precisa dar |
|---|---|---|
| `:reaction/simple` | onset visual do alvo → onset do movimento (ms) | frame de aparição do alvo |
| `:reaction/choice` | % de 1º movimento na direção ERRADA | posição do alvo no spawn |
| `:tracking/reactive` | lag de realinhamento pós-inversão do alvo (ms) | trajetória do alvo |
| `:click/timing` | Δt (mira no alvo → tiro) | alvo sob a mira + evento de tiro |

**Como fazer:**
1. Detector de alvo: treinar um YOLO leve (ou template-matching para os orbes
   simples do KovaaK's) que devolve caixa/centro do(s) alvo(s) por frame. O
   `dataset de visão` (objetivo c) é o conjunto de frames rotulados que treina
   isso — daí Tier 2 e (c) compartilharem trabalho.
2. Evento de tiro: detectar muzzle flash / mudança de HUD de munição / hit-marker
   (varia por config → começar pelo flash, que é o mais universal).
3. Onset visual: primeiro frame em que o alvo aparece (diff de aparição na ROI).
4. Com alvo+mira+tiro por frame, computar as 4 métricas acima; rotular por energia
   como no Tier 1.
5. **Gate extra:** validar o detector do mesmo jeito — nas suas runs, o
   `screen.parquet` (v0.4, centroide de mudança) e os eventos do sensor já dão um
   proxy de ground-truth pra conferir a detecção antes de confiar no YouTube.

> Ordem sugerida de implementação: Tier 1 inteiro e validado → só então Tier 2,
> porque Tier 2 embute o modelo de visão (custo e risco muito maiores).

## 5. Estimador de câmera

**Saída:** traço temporal de velocidade angular (yaw, pitch) por frame. Roll ≈ 0
(FPS não rotaciona a câmera) → problema de 2 graus de liberdade.

**Abordagem recomendada:** feature tracking esparso (ORB/KLT) no **fundo estático**
(o KovaaK's é uma sala fixa) → homografia entre frames consecutivos → componente
de rotação. Mascarar alvos/HUD/muzzle antes (senão poluem o fluxo):
- alvos: a própria detecção do Tier 2 (ou, no Tier 1, mascarar a ROI central onde
  os alvos costumam estar + descartar clusters de movimento inconsistentes com
  rotação rígida via RANSAC).
- HUD: máscara fixa das bordas/centro (crosshair) por resolução.
- Fallback: fluxo óptico denso (Farneback) + mediana robusta se o esparso falhar
  em cenas de baixa textura.

**Unidades:** em pixels/frame por padrão. Vira °/s só com FOV conhecido (suas
runs). Para Tier 1 (adimensional) pixels bastam. Cuidado com **framerate variável**
do YouTube (VFR): reamostrar pra grade temporal fixa usando os timestamps reais
do container, não assumir fps constante.

**Riscos conhecidos (documentar no achado):** compressão/motion-blur do YouTube
degrada o fluxo em movimentos rápidos (flicks) — justo os bouts de `ballistic`;
letterbox/escala/overlay de webcam variam por vídeo (detectar a ROI de jogo
antes). Medir o erro do estimador nas suas runs por faixa de velocidade e
**declarar a faixa em que ele é confiável** (não esconder onde falha).

## 6. Calibração: de `(métrica, energia)` para tabela de âncora

Para cada métrica-âncora:
1. Corpus de VODs do cenário/família S, cada um com energia E (OCR→régua) →
   nível-proxy L = E/12 (0–100), o mesmo espaço que o `skills.clj` usa.
2. Extrair a métrica m de cada VOD (Tier 1/2 acima).
3. Pares `(m_i, L_i)`. Bucketizar por rank (iron..celestial), mediana de m por
   bucket → pontos de âncora `[[m_bucket L_bucket] ...]`. Ajuste monotônico
   (regressão isotônica) pra garantir tabela lerp bem-comportada, formato
   idêntico ao `anchors` de hoje (`[[x score]...]`).
4. Guardar `n`, IQR e fonte por ponto. Âncora com `n` baixo fica marcada e o coach
   trata com confiança menor (não silenciar amostra pobre).

**Escopo do agrupamento:** começar **global por métrica** (pool de todos os
cenários), igual ao shape atual. Refinamento futuro: condicionar por categoria de
cenário ou faixa de amplitude, se os dados mostrarem que a curva difere.

## 7. Objetivo (b) sem vídeo — esboço (caminho separado)

Não é deste `lab/` mas foi decidido junto: matriz jogador×cenário dos leaderboards
da API kovaaks → fatoração (PCA/NMF) → correlações empíricas entre cenários →
valida/ajusta os pesos `:skills` do catálogo (hoje prior curado). Fecha com o loop
previsto×realizado já existente. Implementável hoje, zero visão computacional.

## 8. `population.db` (na máquina do lab, NÃO no repo)

Append-only (mesmo espírito do ADR 0001). Uma linha por VOD processado:

```
observation(
  video_id, source_url, scenario_name, ocr_score, energy, rank_bucket,
  metric_key, metric_value, estimator_confidence, tier, processed_at)
```

- Âncoras = **agregação** por `(scenario/família, metric_key, rank_bucket)`. Nada
  de guardar tabela final aqui: re-agregar/re-bucketizar sem reprocessar.
- Traço angular cru por frame NÃO é guardado (grande demais); só as métricas
  agregadas por VOD. Se as métricas mudarem muito, reprocessa do vídeo.
- Só o **`anchors.edn` destilado** sai daqui pro repo (via §9).

## 9. Fiação: `anchors.edn` versionado

O `lab/` emite `coach/catalog/anchors.edn`:

```clojure
{:anchors/source "population.db 2026-..-.. (N vods, ...)"
 :anchors
 {:sparc        [[-3.5 10.0] ... [-1.2 92.0]]
  :overshoot    [[1.0 90.0] ... [1.4 10.0]]
  ...}
 :meta {:sparc {:n 240 :buckets {...}} ...}}   ; procedência por âncora
```

`skills.clj` passa a **carregar** esse mapa em vez do `def ^:private anchors`
hardcoded (fallback pro provisório se o arquivo não existir, pra não quebrar o
coach offline). Mesma arquitetura dos thresholds: dado gerado offline, versionado,
diff auditável, **zero ML na JVM**.

## 10. Não-objetivos (explícitos)

- Tremor e micro-adjust: fora — finos demais pro vídeo comprimido.
- Amplitude em graus / proxy punho-braço via VOD: fora — precisa de FOV.
- Rodar qualquer coisa disto em tempo real ou na JVM: fora — é batch, Python,
  offline; produto só consome `anchors.edn`.
- Scraping do evxl: continua fora (403). Fonte de rank é OCR do próprio vídeo.

## 11. Ética / legal

VODs públicos, `yt-dlp` com rate polido e cache local obrigatório (mesma regra do
cliente da API kovaaks). Guardar `source_url` por observação pra procedência.
Não redistribuir vídeo; o produto só publica âncoras agregadas (números), nunca
frames de terceiros.
