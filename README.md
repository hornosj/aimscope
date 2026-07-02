# aimscope

Análise de mecânica de mira/mouse control. **v0.1 — produto KovaaK's**: gravador
Rust (Raw Input 1000Hz+ com timestamps QPC → Parquet) + sidecar Python de análise
(segmentação de movimentos, smoothness, tremor, submovimentos, alinhamento com o
CSV do KovaaK's, relatório HTML).

100% observador externo: captura via APIs do SO, nenhum handle/hook em processo
de jogo. Restrição arquitetural, não detalhe.

## Estrutura

```
crates/
  capture/    Raw Input (WM_INPUT) + QPC + escrita mouse.parquet + recorder
  session/    formato de sessão (o CONTRATO entre Rust e Python) + tools
  cli/        binário `aimscope`: record / analyze / list
  app/        binário `aimscope-ui`: interface desktop (egui)
coach/        o cérebro (Clojure), DOIS runtimes:
              deps.edn  -> determinístico: clojure -M:run all --sessions <dir>
                           (skills + resíduo + plano GOAP; sem LLM, por decisão)
              pom.xml   -> agente Embabel REAL (Spring+AgentPlatform+OpenRouter):
                           mvn -q -DskipTests package
                           java -jar target\aimscope-coach.jar  -> narrative.md
                           (LLM aditivo: sem OPENROUTER_APIKEY cai no fallback)
              saídas: %LOCALAPPDATA%\aimscope\coach\{diagnosis,plan,outcome}.json + narrative.md
sidecar/
  aimscope_sidecar/   análise: cinemática, segmentos, métricas, relatório
  tests/              testes de validade contra sessões sintéticas
sessions/     (gitignored) sessões gravadas — demo_sintetica incluída
```

## Começar a usar (o caminho curto)

```powershell
powershell -ExecutionPolicy Bypass -File tools\install.ps1
```
Cria os atalhos **aimscope** na área de trabalho e no menu iniciar. Dali em
diante: abrir o atalho → gravar → treinar → "Parar e analisar" → relatório no
navegador + diagnóstico/plano do coach direto na janela. Distribuição:
`tools\make-dist.ps1` gera `dist\aimscope-vX.zip` (destino roda `setup.ps1`
uma vez; exige Python 3.11+, e Clojure+JDK só para o coach).

## Setup

```powershell
# Rust (requer Smart App Control DESLIGADO — ele bloqueia build scripts do cargo)
cargo build --release

# Python
cd sidecar
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[dev]"
.\.venv\Scripts\python -m pytest tests   # testes de validade das métricas
```

## Uso

**Interface gráfica** (recomendado): rode `target\release\aimscope-ui.exe`,
preencha DPI + sens, clique **Iniciar gravação**, treine, **Parar e analisar** —
o relatório abre sozinho no navegador com a seção "O que treinar" (insights).

**Linha de comando:**

```powershell
# 1. Grave uma sessão (informe SEU dpi e sens — sem isso as métricas ficam em counts)
aimscope record --dpi 800 --sens 0.4 --sens-scale valorant
#    ... treine no KovaaK's ... Ctrl+C para parar.
#    O CSV de stats do KovaaK's é copiado automaticamente para a sessão.

# 2. Analise (chama o sidecar Python e gera report.html + metrics.json)
aimscope analyze latest --open

# Sessão sintética de demonstração (não precisa do gravador):
sidecar\.venv\Scripts\python -m aimscope_sidecar.synth sessions\demo --tremor 0.05
sidecar\.venv\Scripts\python -m aimscope_sidecar sessions\demo
```

## Métricas (todas POR MOVIMENTO, nunca sobre o trace inteiro)

| Métrica | O que mede | Referência |
|---|---|---|
| Eficiência de trajeto | deslocamento líquido / caminho percorrido (1.0 = reto) | — |
| Correções | submovimentos corretivos após o movimento balístico | Meyer 1988 |
| Overshoot | quanto passou do alvo no eixo principal | — |
| SPARC | smoothness espectral (≈0 = suave) | Balasubramanian 2015 |
| LDLJ | log dimensionless jerk | idem |
| Tremor 8–12 Hz | fração de potência na banda de tremor fisiológico, em janelas quietas | Elble & Koller |
| Tempo de aquisição | início do movimento → clique da kill (via CSV do KovaaK's) | — |
| Reação visual | mudança abrupta na tela (DXGI) → onset do movimento; antecipação <80ms contada à parte | v0.3 |
| Direção da reação | spawn com posição (centroide) → 1º movimento na direção certa? erro angular + taxa de direção errada | v0.4 |
| Perda de movimento | alvo inverte direção → lag de realinhamento do mouse; perda = realinhar >350ms | v0.4 |

Validação: `sidecar/tests` gera sessões sintéticas com propriedades **conhecidas**
(jerk mínimo puro, tremor de 10 Hz injetado, correções deliberadas) e exige que
cada métrica discrimine as condições. O alinhamento kill↔clique é validado com
offset conhecido.

## Base de tempo

Tudo em QueryPerformanceCounter. O manifest ancora QPC ↔ relógio de parede no
início da sessão; os kills do CSV (hora do dia) são realinhados aos cliques do
Raw Input por mediana, com rejeição se o resíduo for alto.

## Insights

`insights.py` transforma métricas em dicas de treino (overshoot → precisão/sens,
correções → flicks deliberados, tremor → tensão/grip, aquisição lenta → reação vs
micro-ajuste). Limiares são PROVISÓRIOS — a calibragem real vem de comparar
sessões do próprio jogador em condições contrastantes.

## Roadmap

- v0.2: ✅ UI (egui) + insights + coach (Clojure/GOAP)
- v0.3: ✅ captura de tela DXGI (screen.parquet leve, sem vídeo) + reaction time
  visual → skill `reaction/simple` no coach. Jogo precisa estar em BORDERLESS.
- v0.4: ✅ centroide de mudança visual (cx/cy/cw) → `reaction/choice` (direção
  certa no 1º movimento) e `tracking/reactive` real (lag de realinhamento /
  perda de movimento). Limitação declarada: centroide confiável com UM alvo
  dominante — multi-alvo espera detecção de verdade (fase Valorant/YOLO).
  Próximos: histórico entre sessões na UI, thresholds das sheets, git/releases
- v0.3: captura de tela (DXGI) p/ reaction time real no KovaaK's
- v1.x: Valorant (kill feed OCR → YOLO), comparação treino × ranked
