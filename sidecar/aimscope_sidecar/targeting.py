"""Direção do alvo via centroide de mudança visual (cx/cy/cw do screen.parquet).

Dois analisadores:

1. CHOICE (spawn): no evento visual, o centroide diz ONDE o alvo apareceu
   (mira = centro da tela ⇒ direção ao alvo = direção do centroide). Comparamos
   com a direção do movimento inicial do mouse (primeiros CHOICE_WINDOW_S após
   o onset): erro angular; > 90° = direção ERRADA ("reagiu incorretamente").

2. PURSUIT (tracking): em movimento contínuo do alvo, o centroide segue o
   alvo. Mudança abrupta de direção do centroide -> quanto tempo o mouse leva
   pra REALINHAR a direção da velocidade ("perda de movimento" = realinhamento
   lento > LOSS_MS).

Honestidade: o centroide é de ENERGIA DE MUDANÇA — confiável com UM alvo
dominante (cenários de benchmark). Multi-alvo/efeitos de tela poluem; o cw
baixo/instável filtra parte disso, o resto é limitação declarada até haver
detecção de alvo de verdade (YOLO, fase Valorant).

Convenção de eixos: cy da tela cresce PARA BAIXO; a cinemática usa +y PARA
CIMA. Invertemos cy aqui, na fronteira.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pyarrow.parquet as pq

CHOICE_WINDOW_S = 0.08     # direção inicial = média da velocidade nesse pós-onset
MIN_CENTROID_W = 3.0       # cw mínimo p/ confiar no centroide
MIN_TARGET_ECC = 0.12      # alvo muito perto do centro não define direção
WRONG_DEG = 90.0
PURSUIT_SMOOTH_S = 0.05
PURSUIT_MIN_SPEED = 0.3    # ROI/s: centroide precisa estar se movendo
REALIGN_MAX_S = 0.6
LOSS_MS = 350.0


@dataclass
class CentroidTrace:
    t: np.ndarray
    x: np.ndarray   # [-1,1] ROI, +dir
    y: np.ndarray   # +CIMA (já invertido da tela)
    w: np.ndarray


def load_centroids(session_dir, manifest) -> CentroidTrace | None:
    p = session_dir / "screen.parquet"
    if not p.exists():
        return None
    tb = pq.read_table(p)
    if "cx" not in tb.column_names or tb.num_rows < 30:
        return None  # screen.parquet antigo (v0.3 sem centroide)
    ts = tb["ts_qpc"].to_numpy()
    t = (ts - manifest["qpc_at_start"]) / manifest["qpc_frequency"]
    return CentroidTrace(
        t=t.astype(np.float64),
        x=tb["cx"].to_numpy().astype(np.float64),
        y=-tb["cy"].to_numpy().astype(np.float64),  # tela(+baixo) -> física(+cima)
        w=tb["cw"].to_numpy().astype(np.float64),
    )


def _angle_diff_deg(a: float, b: float) -> float:
    d = np.degrees(np.arctan2(np.sin(a - b), np.cos(a - b)))
    return abs(float(d))


# ------------------------------------------------------------------- choice

def choice(
    cen: CentroidTrace,
    event_times: np.ndarray,
    onsets: np.ndarray,
    kin_t: np.ndarray,
    vx: np.ndarray,
    vy: np.ndarray,
    fs: float,
) -> dict | None:
    """Para cada spawn com centroide confiável: direção inicial do mouse
    certa ou errada em relação ao alvo."""
    samples = []
    for ev in event_times:
        i = int(np.searchsorted(cen.t, ev))
        if i >= len(cen.t):
            continue
        # centroide no frame do evento (ou no seguinte, se o peso for baixo)
        j = i if cen.w[i] >= MIN_CENTROID_W else min(i + 1, len(cen.t) - 1)
        if cen.w[j] < MIN_CENTROID_W:
            continue
        tx, ty = cen.x[j], cen.y[j]
        if np.hypot(tx, ty) < MIN_TARGET_ECC:
            continue  # alvo ~no centro: direção indefinida, pule (honesto)
        after = onsets[onsets > ev]
        if len(after) == 0 or after[0] - ev > 0.7:
            continue
        on = after[0]
        k0 = int(np.searchsorted(kin_t, on))
        k1 = int(np.searchsorted(kin_t, on + CHOICE_WINDOW_S))
        if k1 - k0 < 5:
            continue
        mvx, mvy = float(np.mean(vx[k0:k1])), float(np.mean(vy[k0:k1]))
        if np.hypot(mvx, mvy) < 1e-9:
            continue
        err = _angle_diff_deg(np.arctan2(mvy, mvx), np.arctan2(ty, tx))
        samples.append(err)

    if len(samples) < 5:
        return None
    errs = np.array(samples)
    return {
        "n": int(len(errs)),
        "wrong_direction_rate": float(np.mean(errs > WRONG_DEG)),
        "median_angle_err_deg": float(np.median(errs)),
    }


# ------------------------------------------------------------------ pursuit

def _target_direction_changes(cen: CentroidTrace) -> np.ndarray:
    """Instantes em que o alvo (centroide) inverte a direção dominante.

    No cruzamento por zero a velocidade suavizada é ~0 por construção — a
    validação de magnitude é feita em JANELAS antes/depois do cruzamento
    (eixo dominante de tracking costuma ser o horizontal/strafe)."""
    if len(cen.t) < 20:
        return np.array([])
    dt = float(np.median(np.diff(cen.t)))
    n_smooth = max(3, int(PURSUIT_SMOOTH_S / max(dt, 1e-6)))
    kern = np.ones(n_smooth) / n_smooth

    # frames sem sinal (cw baixo): forward-fill antes de suavizar (nan poisona)
    x = cen.x.copy()
    bad = cen.w < MIN_CENTROID_W
    for i in range(1, len(x)):
        if bad[i]:
            x[i] = x[i - 1]
    x = np.convolve(x, kern, mode="same")
    vx_t = np.gradient(x, cen.t)

    win = max(2, int(0.10 / dt))   # janelas de validação
    gap = max(1, int(0.03 / dt))   # zona morta em volta do cruzamento
    sign = np.sign(vx_t)
    changes = []
    last = -1e9
    for i in range(win + gap, len(vx_t) - win - gap):
        if sign[i - 1] * sign[i] >= 0:
            continue
        pre = np.mean(vx_t[i - gap - win : i - gap])
        post = np.mean(vx_t[i + gap : i + gap + win])
        if (
            pre * post < 0
            and abs(pre) > PURSUIT_MIN_SPEED
            and abs(post) > PURSUIT_MIN_SPEED * 0.5
            and cen.t[i] - last > 0.25
        ):
            changes.append(cen.t[i])
            last = cen.t[i]
    return np.array(changes)


def pursuit(
    cen: CentroidTrace,
    kin_t: np.ndarray,
    vx: np.ndarray,
    fs: float,
) -> dict | None:
    """Lag de realinhamento do mouse após inversão de direção do alvo
    ('perda de movimento' quando > LOSS_MS)."""
    changes = _target_direction_changes(cen)
    if len(changes) < 5:
        return None
    lags = []
    for ch in changes:
        i = int(np.searchsorted(kin_t, ch))
        if i >= len(vx):
            continue
        before = np.sign(np.mean(vx[max(0, i - int(0.08 * fs)) : max(1, i)]))
        if before == 0:
            continue
        j_end = min(len(vx), i + int(REALIGN_MAX_S * fs))
        win = vx[i:j_end]
        flipped = np.where(np.sign(win) == -before)[0]
        # exige a inversão SUSTENTADA por 30ms (não um zero-crossing de ruído)
        sustain = int(0.03 * fs)
        for f0 in flipped:
            if f0 + sustain <= len(win) and (np.sign(win[f0 : f0 + sustain]) == -before).all():
                lags.append((f0 / fs) * 1000.0)
                break
    if len(lags) < 5:
        return None
    lags = np.array(lags)
    return {
        "n_direction_changes": int(len(changes)),
        "n_matched": int(len(lags)),
        "realign_median_ms": float(np.median(lags)),
        "realign_p75_ms": float(np.percentile(lags, 75)),
        "loss_rate": float(np.mean(lags > LOSS_MS)),
    }
