# 0001 — SQLite append-only como store do estado do jogador

Data: 2026-07-01 · Status: aceito

## Contexto

O coach (Clojure) é o único escritor do estado do jogador (scores, série
temporal de skills, planos+previsões). As queries naturais do domínio são
datalog (joins cenário × skill × tempo) e o produto precisa de auditoria
temporal ("o que o coach acreditava quando gerou este plano?") para o loop
previsto × realizado. Candidatos avaliados: SQLite, Datomic Pro (grátis desde
2023), Datalevin, arquivos puros.

## Decisão

SQLite como store of record, com **regra de ouro: nunca UPDATE em estado do
jogador — só INSERT com `tx_time`** (append-only). O coach carrega o estado em
**datascript em memória** para as queries datalog.

## Justificativa

- A única vantagem decisiva do Datomic (histórico nativo / as-of) é replicada
  em escala single-user (MBs/ano) pela disciplina append-only, com custo ~zero.
- Restrição de produto desde o dia 1: "poucas peças móveis, distribuição
  limpa". Transactor + H2 + peer embarcados num app desktop violam isso.
- SQLite é legível por Rust/Python/Clojure e por qualquer ferramenta em 10
  anos; backup = copiar 1 arquivo. Datomic/Datalevin prendem os dados à JVM.
- datascript usa a mesma API datalog do Datomic → migração futura das queries
  é quase copy-paste.

## Critérios objetivos de reabertura

Reavaliar Datomic (ou similar) se surgir QUALQUER um:
1. Sync multi-device do estado do jogador
2. Simulações what-if com branching de histórico
3. Coach como serviço multi-usuário

## Consequências

- Schema desenhado como log de fatos (event-sourcing leve), não como CRUD.
- Snapshots derivados (skill atual) são SEMPRE recomputáveis do log.
- O coach paga um load inicial SQLite→datascript por execução (irrelevante em
  batch na escala atual).
