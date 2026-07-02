"""A regua: score na tela -> energia -> nivel 0-100 -> bucket de rank.

Espelha residual.clj (level-of / scaled-actual): a MESMA regua semeada que o
coach usa e a ground-truth de nivel de skill do lab (design-vod-lab.md §6).
Escalas: :energy (VT 0-1200, nivel = energia/12) e :percentile (0-100 direto).
Para uniformizar o population.db, energia e sempre armazenada na escala VT
0-1200 (percentil vira nivel*12).
"""

from __future__ import annotations

import re
from pathlib import Path

from .edn import K, Keyword, loads

# Mesma ordem/lista de arquivos do catalog.clj: primeiro arquivo vence colisao.
CATALOG_FILES = ["viscose-s2.edn", "voltaic-s4.edn", "voltaic-s5.edn", "community.edn"]

# 12 ranks Voltaic, 100 de energia cada (100-1200).
RANKS = [
    "iron", "bronze", "silver", "gold", "platinum", "diamond",
    "jade", "master", "grandmaster", "nova", "astra", "celestial",
]


def normalize_name(s: str) -> str:
    """Mesma chave canonica do catalog.clj/normalize-name."""
    return re.sub(r"\s+", " ", str(s).strip().lower())


def load_thresholds(catalog_dir: str | Path) -> dict[str, dict]:
    """{nome-normalizado: {"scale": "energy"|"percentile", "points": [[nivel score]...]}}"""
    out: dict[str, dict] = {}
    for fname in CATALOG_FILES:
        p = Path(catalog_dir) / fname
        if not p.exists():
            continue
        data = loads(p.read_text(encoding="utf-8"))
        th = data.get(K("thresholds"))
        if not isinstance(th, dict):
            continue
        for nome, spec in th.items():
            key = normalize_name(nome)
            if key in out:
                continue
            scale = spec.get(K("scale"))
            out[key] = {
                "scale": scale.name if isinstance(scale, Keyword) else str(scale),
                "points": [[float(l), float(s)] for l, s in spec.get(K("points"), [])],
            }
    return out


def level_of(points: list[list[float]], score: float) -> float | None:
    """Score -> nivel por interpolacao linear (espelho de residual/level-of).

    Abaixo do 1o ponto: linear de 0 ao 1o nivel. Acima do ultimo: clampa.
    """
    pts = sorted(
        ([l, s] for l, s in points if l is not None and s is not None),
        key=lambda p: p[1],
    )
    if not pts or score is None:
        return None
    l0, s0 = pts[0]
    ln, sn = pts[-1]
    if score <= s0:
        return l0 * (score / max(s0, 1e-9))
    if score >= sn:
        return ln
    for (la, sa), (lb, sb) in zip(pts, pts[1:]):
        if score <= sb:
            return la + (lb - la) * (score - sa) / max(sb - sa, 1e-9)
    return ln


def energy_of(spec: dict, score: float) -> float | None:
    """Score -> energia na escala VT 0-1200 (percentil e reescalado x12)."""
    lvl = level_of(spec["points"], score)
    if lvl is None:
        return None
    return lvl if spec["scale"] == "energy" else lvl * 12.0


def level_0_100(energy: float) -> float:
    """Energia VT -> nivel-proxy L = E/12, o espaco 0-100 do skills.clj."""
    return energy / 12.0


def rank_bucket(energy: float) -> str:
    """Energia VT 0-1200 -> um dos 12 ranks (iron..celestial).

    Iron comeca em 100 (abaixo disso ainda bucketiza como iron), celestial
    e atingido em 1200.
    """
    idx = int(energy // 100) - 1
    return RANKS[max(0, min(idx, len(RANKS) - 1))]
