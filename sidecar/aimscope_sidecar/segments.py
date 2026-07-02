"""Segmentacao do trace em regimes de movimento.

- "bout": movimento intencional (aquisicao/flick), detectado por histerese de
  velocidade. E a unidade de analise para smoothness/submovimentos/eficiencia.
- "quiet": janelas quase paradas (micro-ajuste/hold), usadas para tremor.

Confundimento e o risco n.1 do projeto: metricas de smoothness calculadas
sobre o trace inteiro misturam navegacao, flick e tremor. Toda metrica
cinematica e calculada POR SEGMENTO, nunca na sessao inteira.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .kinematics import Kinematics

# Limiares em deg/s (convertidos se a unidade for counts).
HI_THRESH_DEG_S = 25.0
LO_THRESH_DEG_S = 8.0
QUIET_THRESH_DEG_S = 12.0
MIN_BOUT_MS = 40
MERGE_GAP_MS = 30
MIN_QUIET_MS = 300
FALLBACK_DEG_PER_COUNT = 0.02  # so para escalar limiares quando unidade=counts


@dataclass
class Bout:
    i0: int  # indices na grade da Kinematics [i0, i1)
    i1: int

    def slice(self) -> slice:
        return slice(self.i0, self.i1)


def _thresholds(kin: Kinematics) -> tuple[float, float, float]:
    if kin.unit == "deg":
        return HI_THRESH_DEG_S, LO_THRESH_DEG_S, QUIET_THRESH_DEG_S
    k = 1.0 / FALLBACK_DEG_PER_COUNT
    return HI_THRESH_DEG_S * k, LO_THRESH_DEG_S * k, QUIET_THRESH_DEG_S * k


def movement_bouts(kin: Kinematics) -> list[Bout]:
    hi, lo, _ = _thresholds(kin)
    speed = kin.speed
    n = len(speed)
    min_len = int(MIN_BOUT_MS * kin.fs / 1000.0)
    merge_gap = int(MERGE_GAP_MS * kin.fs / 1000.0)

    bouts: list[Bout] = []
    i = 0
    while i < n:
        if speed[i] >= hi:
            # expande para tras ate cair abaixo de lo (pega o onset)
            start = i
            while start > 0 and speed[start - 1] >= lo:
                start -= 1
            end = i
            while end < n and speed[end] >= lo:
                end += 1
            bouts.append(Bout(start, end))
            i = end
        else:
            i += 1

    # merge de bouts separados por gaps curtos
    merged: list[Bout] = []
    for b in bouts:
        if merged and b.i0 - merged[-1].i1 <= merge_gap:
            merged[-1] = Bout(merged[-1].i0, b.i1)
        else:
            merged.append(b)

    return [b for b in merged if b.i1 - b.i0 >= min_len]


def quiet_windows(kin: Kinematics, bouts: list[Bout]) -> list[Bout]:
    """Janelas longas de baixa velocidade FORA dos bouts (para tremor)."""
    _, _, quiet_th = _thresholds(kin)
    n = len(kin.speed)
    mask = kin.speed < quiet_th
    for b in bouts:
        mask[b.slice()] = False

    min_len = int(MIN_QUIET_MS * kin.fs / 1000.0)
    wins: list[Bout] = []
    i = 0
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            if j - i >= min_len:
                wins.append(Bout(i, j))
            i = j
        else:
            i += 1
    return wins
