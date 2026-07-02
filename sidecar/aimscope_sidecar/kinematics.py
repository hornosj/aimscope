"""Reamostragem do trace de mouse e derivadas (velocidade/aceleracao).

O Raw Input chega irregular (~1000Hz com jitter). Reamostramos a POSICAO
acumulada numa grade uniforme de 1kHz por interpolacao linear e derivamos
com Savitzky-Golay (suaviza sem defasar).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy.signal import savgol_filter

FS_DEFAULT = 1000.0
SAVGOL_WINDOW_MS = 21
SAVGOL_POLY = 3


@dataclass
class Kinematics:
    t: np.ndarray        # grade uniforme, segundos
    x: np.ndarray        # posicao acumulada (deg ou counts)
    y: np.ndarray
    vx: np.ndarray       # unidade/s
    vy: np.ndarray
    speed: np.ndarray
    fs: float
    unit: str            # "deg" ou "counts"

    @property
    def duration_s(self) -> float:
        return float(self.t[-1] - self.t[0]) if len(self.t) > 1 else 0.0


def resample(
    t_raw: np.ndarray,
    dx: np.ndarray,
    dy: np.ndarray,
    deg_per_count: float | None,
    fs: float = FS_DEFAULT,
) -> Kinematics:
    if len(t_raw) < 10:
        raise ValueError(f"trace curto demais para analise ({len(t_raw)} eventos)")

    # Eventos fora de ordem ou duplicados no tempo quebram a interpolacao.
    order = np.argsort(t_raw, kind="stable")
    t_raw = t_raw[order]
    cx = np.cumsum(dx[order]).astype(np.float64)
    cy = np.cumsum(dy[order]).astype(np.float64)

    scale = deg_per_count if deg_per_count is not None else 1.0
    unit = "deg" if deg_per_count is not None else "counts"

    grid = np.arange(t_raw[0], t_raw[-1], 1.0 / fs)
    x = np.interp(grid, t_raw, cx) * scale
    # eixo y de tela cresce para baixo; invertemos para "cima = positivo"
    y = np.interp(grid, t_raw, cy) * -scale

    win = max(5, int(SAVGOL_WINDOW_MS * fs / 1000.0) | 1)  # impar
    if len(grid) <= win:
        raise ValueError("trace curto demais para o filtro de derivada")
    vx = savgol_filter(x, win, SAVGOL_POLY, deriv=1, delta=1.0 / fs)
    vy = savgol_filter(y, win, SAVGOL_POLY, deriv=1, delta=1.0 / fs)
    speed = np.hypot(vx, vy)

    return Kinematics(t=grid, x=x, y=y, vx=vx, vy=vy, speed=speed, fs=fs, unit=unit)
