"""Validade de choice/pursuit: direções e lags CONHECIDOS injetados devem ser
recuperados pelo pipeline."""

import numpy as np

from aimscope_sidecar.targeting import (
    CentroidTrace,
    choice,
    pursuit,
    _target_direction_changes,
)

FS = 1000.0


def _kin(dur=20.0):
    t = np.arange(0, dur, 1.0 / FS)
    return t, np.zeros_like(t), np.zeros_like(t)


def _pulse(v, t, start, dur, amp):
    i0, i1 = int(start * FS), int((start + dur) * FS)
    v[i0:i1] = amp


# ------------------------------------------------------------------- choice

def test_choice_right_and_wrong_directions():
    fps = 120.0
    tc = np.arange(0, 20.0, 1.0 / fps)
    cx = np.zeros_like(tc)
    cy = np.zeros_like(tc)
    w = np.full_like(tc, 0.5)

    # 8 spawns: alvo à DIREITA (cx=+0.5); mouse vai certo nos 6 primeiros,
    # errado (esquerda) nos 2 últimos
    events = np.array([2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0])
    for ev in events:
        i = int(ev * fps)
        cx[i : i + 3] = 0.5
        w[i : i + 3] = 10.0

    t, vx, vy = _kin()
    onsets = []
    for k, ev in enumerate(events):
        on = ev + 0.20
        onsets.append(on)
        _pulse(vx, t, on, 0.15, 80.0 if k < 6 else -80.0)

    cen = CentroidTrace(t=tc, x=cx, y=cy, w=w)
    r = choice(cen, events, np.array(onsets), t, vx, vy, FS)
    assert r is not None
    assert r["n"] == 8
    assert abs(r["wrong_direction_rate"] - 2 / 8) < 1e-6
    assert r["median_angle_err_deg"] < 20.0


def test_choice_skips_center_targets():
    fps = 120.0
    tc = np.arange(0, 10.0, 1.0 / fps)
    cen = CentroidTrace(t=tc, x=np.full_like(tc, 0.02),
                        y=np.zeros_like(tc), w=np.full_like(tc, 10.0))
    t, vx, vy = _kin(10.0)
    events = np.array([2.0, 4.0, 6.0])
    r = choice(cen, events, events + 0.2, t, vx, vy, FS)
    assert r is None  # alvo no centro: direção indefinida, honesto = sem dado


# ------------------------------------------------------------------ pursuit

LAG_S = 0.180


def _tracking_scene(n_flips=8, period=1.2, fps=240.0, dur=None):
    dur = dur or (n_flips + 2) * period
    tc = np.arange(0, dur, 1.0 / fps)
    # alvo em vaivém horizontal (triângulo): inversões a cada `period`
    from scipy.signal import sawtooth
    x = 0.6 * sawtooth(2 * np.pi * tc / (2 * period), width=0.5)
    w = np.full_like(tc, 10.0)
    cen = CentroidTrace(t=tc, x=x, y=np.zeros_like(tc), w=w)

    # mouse segue a MESMA forma de onda, atrasado LAG_S
    t = np.arange(0, dur, 1.0 / FS)
    xm = 0.6 * sawtooth(2 * np.pi * (t - LAG_S) / (2 * period), width=0.5)
    vx = np.gradient(xm, t) * 50.0  # escala arbitrária (deg/s)
    return cen, t, vx


def test_pursuit_detects_flips():
    cen, _, _ = _tracking_scene()
    flips = _target_direction_changes(cen)
    assert len(flips) >= 6


def test_pursuit_recovers_injected_lag():
    cen, t, vx = _tracking_scene()
    r = pursuit(cen, t, vx, FS)
    assert r is not None
    assert abs(r["realign_median_ms"] - LAG_S * 1000.0) < 30.0, r
    assert r["loss_rate"] < 0.5  # 180ms < limiar de perda (350ms)


def test_pursuit_slow_mouse_flags_loss():
    global LAG_S
    old = LAG_S
    try:
        LAG_S = 0.45  # bem acima do limiar de perda
        cen, t, vx = _tracking_scene()
        r = pursuit(cen, t, vx, FS)
        assert r is not None
        assert r["loss_rate"] > 0.5
    finally:
        LAG_S = old
