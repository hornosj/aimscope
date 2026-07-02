"""Calibracao: observacoes (metrica, energia) -> tabelas de ancora (design §6).

Por metrica que PASSOU no gate de ground-truth:
  1. aplica a correcao metric_video -> metric_sensor (se o gate exigiu);
  2. bucketiza por rank (iron..celestial), mediana de m e de L=E/12 por bucket;
  3. regressao isotonica (PAVA) garante tabela lerp monotona, mesmo formato
     [[x score] ...] do skills.clj;
  4. guarda n/IQR por ponto no :meta — ancora com n baixo fica MARCADA e o
     coach a trata com confianca menor (nunca silenciada).

Escopo v1: global por metrica (pool de todos os cenarios), igual ao shape
atual do provisorio.
"""

from __future__ import annotations

from datetime import datetime, timezone

import numpy as np

from .db import Observation
from .edn import K
from .ruler import RANKS, level_0_100

MIN_PER_BUCKET = 5    # observacoes por bucket pra virar ponto de ancora
MIN_BUCKETS = 4       # pontos minimos pra tabela lerp fazer sentido
LOW_N_MARK = 30       # abaixo disso a ancora e marcada :low-n true


def pava(y: list[float], w: list[float], increasing: bool = True) -> list[float]:
    """Pool Adjacent Violators — regressao isotonica ponderada."""
    if not increasing:
        return [-v for v in pava([-v for v in y], w, increasing=True)]
    blocks: list[list[float]] = []  # [soma_wy, soma_w, n_itens]
    for yi, wi in zip(y, w):
        blocks.append([yi * wi, wi, 1])
        while len(blocks) > 1 and (blocks[-2][0] / blocks[-2][1]
                                   > blocks[-1][0] / blocks[-1][1]):
            b = blocks.pop()
            blocks[-1][0] += b[0]
            blocks[-1][1] += b[1]
            blocks[-1][2] += b[2]
    out: list[float] = []
    for wy, wsum, n in blocks:
        out.extend([wy / wsum] * n)
    return out


def anchor_for(obs: list[Observation],
               correction: dict | None = None) -> tuple[list, dict] | None:
    """Observacoes de UMA metrica -> (tabela [[m L]...], meta) ou None."""
    corrected = []
    for o in obs:
        m = o.metric_value
        if correction:
            m = correction["a"] * m + correction["b"]
        corrected.append((m, level_0_100(o.energy), o.rank_bucket))

    buckets: dict[str, list[tuple[float, float]]] = {}
    for m, lvl, bucket in corrected:
        buckets.setdefault(bucket, []).append((m, lvl))

    points = []   # (m_mediana, L_mediana, n, iqr, bucket)
    for bucket in RANKS:
        rows = buckets.get(bucket, [])
        if len(rows) < MIN_PER_BUCKET:
            continue
        ms = np.array([r[0] for r in rows])
        ls = np.array([r[1] for r in rows])
        iqr = float(np.percentile(ms, 75) - np.percentile(ms, 25))
        points.append((float(np.median(ms)), float(np.median(ls)),
                       len(rows), iqr, bucket))
    if len(points) < MIN_BUCKETS:
        return None

    points.sort(key=lambda p: p[0])  # ordena pela metrica (eixo x da lerp)
    ms = [p[0] for p in points]
    ls = [p[1] for p in points]
    ws = [float(p[2]) for p in points]
    # direcao da monotonia: correlacao entre m e L nos proprios pontos
    increasing = bool(np.corrcoef(ms, ls)[0, 1] >= 0)
    ls_iso = pava(ls, ws, increasing=increasing)

    table = [[round(m, 6), round(l, 2)] for m, l in zip(ms, ls_iso)]
    n_total = sum(p[2] for p in points)
    meta = {
        K("n"): n_total,
        K("low-n"): n_total < LOW_N_MARK,
        K("buckets"): {
            K(p[4]): {K("n"): p[2], K("median"): round(p[0], 6),
                      K("iqr"): round(p[3], 6)}
            for p in points
        },
    }
    if correction:
        meta[K("correction")] = {K("a"): correction["a"], K("b"): correction["b"]}
    return table, meta


def build(observations: list[Observation],
          passed: dict[str, dict | None]) -> tuple[dict, dict, list[str]]:
    """(anchors, meta, excluidas). So metricas que passaram no gate entram."""
    by_key: dict[str, list[Observation]] = {}
    for o in observations:
        by_key.setdefault(o.metric_key, []).append(o)

    anchors: dict = {}
    meta: dict = {}
    excluded: list[str] = []
    for key, obs in sorted(by_key.items()):
        if key not in passed:
            excluded.append(f"{key}: nao passou no gate de ground-truth")
            continue
        result = anchor_for(obs, correction=passed[key])
        if result is None:
            excluded.append(f"{key}: buckets insuficientes "
                            f"(minimo {MIN_BUCKETS} com n>={MIN_PER_BUCKET})")
            continue
        anchors[K(key)] = result[0]
        meta[K(key)] = result[1]
    return anchors, meta, excluded


def emit_edn(anchors: dict, meta: dict, db_summary: dict) -> str:
    """Arquivo anchors.edn no formato do design §9 (consumo: skills.clj)."""
    from .edn import dumps

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    source = (f"population.db {stamp} ({db_summary['n_videos']} vods, "
              f"{db_summary['n_observations']} observacoes)")
    doc = {
        K("anchors/source"): source,
        K("anchors"): anchors,
        K("meta"): meta,
    }
    header = (";; GERADO pelo aimscope lab (docs/design-vod-lab.md §9) — nao editar a mao.\n"
              ";; Ancoras calibradas na populacao; metricas que nao passaram no gate de\n"
              ";; ground-truth (ADR 0003) NAO aparecem aqui e caem no provisorio do coach.\n")
    return header + dumps(doc) + "\n"
