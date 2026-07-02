"""Ego-motion (px/frame, VFR) -> Kinematics na grade uniforme do sidecar.

Reusa aimscope_sidecar.kinematics.resample: e a MESMA matematica do sensor
(interp linear da posicao acumulada + derivada Savitzky-Golay), so trocando a
fonte do traco — exatamente o que o design pede pro Tier 1 (§4). A grade e
reamostrada dos timestamps REAIS do container (VFR do YouTube nao vira fps
constante por decreto).
"""

from __future__ import annotations

from aimscope_sidecar.kinematics import Kinematics, resample

from .camera import EgoMotion

# 240 Hz: acima de qualquer fps de VOD, bem abaixo do 1 kHz do sensor — as
# metricas Tier 1 (SPARC fc=10Hz, picos de velocidade) nao dependem de nada
# acima disso.
FS_VIDEO = 240.0


def to_kinematics(ego: EgoMotion, px_per_deg: float | None = None,
                  fs: float = FS_VIDEO) -> Kinematics:
    """px/frame por timestamp real -> Kinematics uniforme.

    px_per_deg: so em runs proprias (FOV conhecido) — ai o traco sai em graus
    e comparavel 1:1 com o sensor. Sem ele, unit="counts" significa PIXELS:
    escala arbitraria porem consistente dentro da run, que e o que as metricas
    adimensionais do Tier 1 exigem (design §3/§5).
    """
    deg_per_count = (1.0 / px_per_deg) if px_per_deg else None
    return resample(ego.t, ego.dx, ego.dy, deg_per_count, fs=fs)


def px_per_deg_of(width_px: int, hfov_deg: float) -> float:
    """Escala px/grau NO CENTRO da tela (projecao pinhole) para FOV horizontal
    conhecido — runs proprias. Nas bordas a projecao estica; para os deltas
    pequenos por frame do centro da visada, a aproximacao central basta."""
    import math

    f = (width_px / 2.0) / math.tan(math.radians(hfov_deg) / 2.0)
    return f * math.pi / 180.0
