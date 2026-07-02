"""Tier 1 — metricas adimensionais por ego-motion puro (design §4).

Mesma matematica do sensor (bout_features do sidecar) sobre o traco angular
do video. Sem detector de alvo. Chaves de metrica = chaves de ancora do
skills.clj (o anchors.edn emitido cai direto no lugar do provisorio):

  overshoot      razao de overshoot (mediana dos bouts)   -> :acquisition/ballistic
  corrections    n_corrections (mediana dos bouts)        -> :acquisition/ballistic
  sparc          SPARC dos bouts LONGOS (top quartil de   -> :tracking/smooth-arm
                 amplitude — proxy adimensional do band
                 "large" do sensor)
  endurance-eff  eficiencia 2a metade / 1a metade         -> :consistency/endurance

Fora por decisao (ADR 0003): tremor, micro-adjust, qualquer magnitude em graus.
"""

from __future__ import annotations

import numpy as np

from aimscope_sidecar.kinematics import Kinematics
from aimscope_sidecar.metrics import BoutFeatures, bout_features
from aimscope_sidecar.segments import Bout

MIN_BOUTS = 20          # menos que isso: run nao vira observacao
MIN_LONG_BOUTS = 5      # p/ sparc de bouts longos
LONG_QUANTILE = 0.75    # "longo" = top quartil de amplitude da propria run
MIN_HALF_BOUTS = 5      # p/ endurance por metades


def _median(values: list[float]) -> float | None:
    v = np.asarray([x for x in values if x is not None and np.isfinite(x)])
    return float(np.median(v)) if len(v) else None


def compute(kin: Kinematics, bouts: list[Bout]) -> dict[str, float]:
    """Traco + bouts -> {metric_key: valor}. So o que da pra medir honesto."""
    if len(bouts) < MIN_BOUTS:
        return {}
    feats: list[BoutFeatures] = [bout_features(kin, b) for b in bouts]

    out: dict[str, float] = {}
    if (v := _median([f.overshoot_ratio for f in feats])) is not None:
        out["overshoot"] = v
    if (v := _median([float(f.n_corrections) for f in feats])) is not None:
        out["corrections"] = v

    amps = np.asarray([f.amplitude for f in feats])
    long_cut = float(np.quantile(amps, LONG_QUANTILE))
    long_feats = [f for f in feats if f.amplitude >= long_cut]
    if len(long_feats) >= MIN_LONG_BOUTS:
        if (v := _median([f.sparc for f in long_feats])) is not None:
            out["sparc"] = v

    # endurance: degradacao de eficiencia 1a -> 2a metade (espelha __main__ do
    # sidecar; razao e adimensional, sobrevive ao px)
    t_mid = (feats[0].t_start + feats[-1].t_end) / 2.0
    first = [f for f in feats if f.t_end <= t_mid]
    second = [f for f in feats if f.t_start > t_mid]
    if len(first) >= MIN_HALF_BOUTS and len(second) >= MIN_HALF_BOUTS:
        e1 = _median([f.efficiency for f in first])
        e2 = _median([f.efficiency for f in second])
        if e1 and e2 and e1 > 0:
            out["endurance-eff"] = e2 / e1

    return out
