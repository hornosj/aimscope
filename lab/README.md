# aimscope `lab/`

Calibração OFFLINE de âncoras por VOD. Design: `docs/design-vod-lab.md`;
decisão de escopo: `docs/adr/0003`; termos: `CONTEXT.md` (Lab, Âncora,
Estimador de câmera, População, Ground truth).

Nada daqui roda no produto. O único artefato que sai é o
`coach/catalog/anchors.edn` versionado (e ele só é emitido para métricas que
passaram no gate de ground-truth).

## Setup

```
python -m venv .venv
.venv/Scripts/pip install -e ../sidecar -e .[dev,ocr,fetch]
```

## Fluxo (na ordem)

```
# 1. gate de ground-truth nas SUAS runs (sessão com mouse.parquet + vídeo)
python -m aimscope_lab validate sessions/2026-07-01_14-00 ... --out validation.json

# 2. VODs públicos -> cache local (rate polido, cache obrigatório)
python -m aimscope_lab fetch https://youtu.be/...

# 3. VOD -> observações Tier 1 no population.db (score lido DA TELA)
python -m aimscope_lab process vod-cache/<id>.mp4 --scenario "pasu voltaic" [--score N]

# 4. população + gate -> âncoras versionadas
python -m aimscope_lab calibrate --validation validation.json
```

`population.db`, `vod-cache/` e `validation.json` vivem SÓ nesta máquina
(gitignored). Testes: `.venv/Scripts/python -m pytest tests` — o vetor
dourado (`test_golden_pipeline.py`) injeta um traço angular conhecido, rende
um VOD sintético e exige que o caminho de vídeo recupere as mesmas métricas
Tier 1 do caminho do sensor.
