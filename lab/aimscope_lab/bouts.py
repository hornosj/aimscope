"""Extracao de bout sobre o traco angular do video.

Mesma histerese de velocidade do sensor (sidecar/segments.py), portada com
limiares parametricos: no sensor os limiares sao em deg/s; no video a escala
px<->grau e desconhecida (design §3). Decisao: limiares vem de um FOV NOMINAL
(103°, o mais comum no KovaaK's) aplicado a largura da ROI — erro de ±20% no
FOV real so desloca limiares de segmentacao (histerese absorve), NUNCA entra
em metrica de magnitude. O gate de ground-truth (§2.1) valida essa escolha
nas runs proprias antes de qualquer ancora ser publicada.
"""

from __future__ import annotations

from aimscope_sidecar.kinematics import Kinematics
from aimscope_sidecar.segments import (
    Bout,
    HI_THRESH_DEG_S,
    LO_THRESH_DEG_S,
    MERGE_GAP_MS,
    MIN_BOUT_MS,
)

from .trace import px_per_deg_of

NOMINAL_HFOV_DEG = 103.0


def thresholds_px_s(roi_width_px: int, hfov_deg: float = NOMINAL_HFOV_DEG) -> tuple[float, float]:
    """(hi, lo) em px/s equivalentes aos 25/8 deg/s do sensor, via FOV nominal."""
    scale = px_per_deg_of(roi_width_px, hfov_deg)
    return HI_THRESH_DEG_S * scale, LO_THRESH_DEG_S * scale


def movement_bouts(kin: Kinematics, hi: float, lo: float) -> list[Bout]:
    """Histerese de velocidade identica ao sensor, limiares explicitos."""
    speed = kin.speed
    n = len(speed)
    min_len = int(MIN_BOUT_MS * kin.fs / 1000.0)
    merge_gap = int(MERGE_GAP_MS * kin.fs / 1000.0)

    bouts: list[Bout] = []
    i = 0
    while i < n:
        if speed[i] >= hi:
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

    merged: list[Bout] = []
    for b in bouts:
        if merged and b.i0 - merged[-1].i1 <= merge_gap:
            merged[-1] = Bout(merged[-1].i0, b.i1)
        else:
            merged.append(b)

    return [b for b in merged if b.i1 - b.i0 >= min_len]


def bouts_for(kin: Kinematics, roi_width_px: int | None = None) -> list[Bout]:
    """Bouts do traco de video. unit=deg (FOV proprio conhecido) usa os limiares
    do sensor; unit=px exige roi_width_px pros limiares nominais."""
    if kin.unit == "deg":
        return movement_bouts(kin, HI_THRESH_DEG_S, LO_THRESH_DEG_S)
    if roi_width_px is None:
        raise ValueError("traco em px exige roi_width_px para os limiares nominais")
    hi, lo = thresholds_px_s(roi_width_px)
    return movement_bouts(kin, hi, lo)
