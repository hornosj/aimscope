# 0002 — Coach em Clojure/JVM como terceiro runtime do monorepo

Data: 2026-07-01 · Status: aceito

## Contexto

O aimscope já tem Rust (captura/UI) e Python (análise por sessão). O coach —
estimativa de skills latentes, análise de resíduo e geração de plano de treino
via GOAP — precisa de um lar. Alternativas: Python (scipy à mão, sem 3º
runtime), Rust (single binary), Clojure (embabel/GOAP já provado no
beautiful-linkedin, domínio data-oriented).

## Decisão

Coach inteiro em Clojure (`coach/`, deps.edn próprio): camadas de skills +
resíduo + plano GOAP via embabel. O adapter embabel-clj (~70 linhas) é COPIADO
do beautiful-linkedin, não referenciado — extração de lib compartilhada só
quando o shape estiver provado nos dois domínios.

## Justificativa

- O domínio é literalmente mapas + grafo de dependências + replanejamento por
  A* com polos de falha (`skill/plateaued?`) — o padrão exato já validado no
  email_hunter_v2 do beautiful-linkedin.
- Roda offline/batch, nunca no caminho quente da captura → custo de JVM
  (startup, memória) é irrelevante para a UX.
- Fronteiras limpas por arquivos: consome metrics.json + user.db (SQLite),
  emite diagnosis.json/plan.json. Rust/Python não linkam com a JVM.
- Fator humano explícito: é o projeto pessoal do autor e Clojure é a linguagem
  onde ele é mais produtivo e motivado. Em projeto de uma pessoa, motivação é
  requisito de engenharia.

## Trade-off aceito

- 3º runtime no monorepo: setup de dev (JDK+clj) e distribuição mais pesados.
  Mitigação: o coach é opcional em runtime — o produto KovaaK's
  (gravar→analisar→report) funciona sem ele.
- Estatística básica (EWMA, percentil, regressão simples) reimplementada em
  Clojure em vez de scipy — aceitável pelo escopo pequeno dessas rotinas.

## Adendo (2026-07-01, implementação)

O embabel 0.4.0 do beautiful-linkedin roda DENTRO de um Spring Boot (pom.xml:
starter + Spring AI + BOM; AgentPlatform é bean). Para um coach batch sem LLM,
esse plumbing não se paga. Implementado `goap.clj`: A* próprio em Clojure puro
com o CONTRATO DE TAGS IDÊNTICO ao adapter embabel (`:action/pre :action/post
:action/cost`, goals como dados, `build-agent` por scan de metadata de vars).
O domínio (plan.clj) é portável sem alteração: adotar o embabel real no futuro
= trocar o engine + adicionar o pom. O padrão GOAP (polos de falha, custo,
replanejamento) está preservado.

## Adendo 2 (2026-07-01, embabel REAL como camada de narrativa)

A pedido do autor, o embabel de verdade (Spring Boot + AgentPlatform +
OpenRouter, espelho do beautiful-linkedin) foi adicionado como RUNTIME
OPCIONAL SEPARADO: `coach/pom.xml` + `src-java/aimscope/boot/App.java` (batch:
sobe, roda o agente 1x, encerra) + `insight_agent.clj` (adapter embabel.clj
copiado). Divisão de trabalho final:

- **deps.edn (A\* puro)**: ingest/diagnose/plan/outcome — matemática
  determinística sobre dados medidos. Continua SEM LLM por decisão.
- **pom.xml (embabel+LLM)**: NARRATIVA de coaching (narrative.md) — onde
  linguagem agrega. Padrão do email_hunter_v2: narrar-llm (custo 1) com polo
  de falha `narrativa/llm-falhou?` → narrar-fallback determinístico (custo 5).
  LLM é aditivo; sem OPENROUTER_APIKEY o agente ainda entrega.

Modelo default: openai/gpt-oss-120b:free (override via env AIMSCOPE_LLM;
registro em resources/models/openai-models.yml). Validado em produção local:
GOAP roteou pelo LLM, sobreviveu a um 429 do tier free e entregou narrativa
fundamentada nos dados reais.

## Consequências

- Contratos de arquivo (metrics.json, user.db schema, plan.json) viram API
  pública interna e ganham versionamento/golden tests.
- Prescrição tem UM dono (coach); insights.py degrada para observação factual.
