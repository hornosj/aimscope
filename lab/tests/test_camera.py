"""Estimador de camera: pan injetado tem que ser recuperado frame a frame."""

import numpy as np
import pytest

from aimscope_lab import camera
from rendering import make_texture, render

FPS = 60.0


def _run_estimator(centers, texture, **kw):
    frames = ((i / FPS, f) for i, f in enumerate(render(texture, centers, **kw)))
    return camera.estimate(frames)


def test_pan_senoidal_recuperado():
    rng = np.random.default_rng(3)
    tex = make_texture(rng)
    n = 90
    t = np.arange(n)
    cx = 1300 + 60 * np.sin(2 * np.pi * t / 45)   # +-60 px, ~1.3 Hz
    cy = 1000 + 25 * np.cos(2 * np.pi * t / 60)
    centers = np.stack([cx, cy], axis=1)

    ego = _run_estimator(centers, tex)

    # convencao estilo mouse: dx do estimador == delta do centro da camera
    inj_dx = np.diff(cx)
    inj_dy = np.diff(cy)
    assert len(ego.dx) == n - 1
    assert np.corrcoef(ego.dx, inj_dx)[0, 1] > 0.99
    assert np.corrcoef(ego.dy, inj_dy)[0, 1] > 0.99
    assert np.median(np.abs(ego.dx - inj_dx)) < 0.3   # px/frame
    assert np.median(np.abs(ego.dy - inj_dy)) < 0.3
    assert ego.confidence > 0.5


def test_alvo_movel_no_centro_nao_polui():
    """O blob de alvo se move DIFERENTE da camera; mascara+RANSAC o descartam."""
    rng = np.random.default_rng(4)
    tex = make_texture(rng)
    n = 60
    cx = 1300 + np.cumsum(np.full(n, 1.5)); cx[0] = 1300
    cy = np.full(n, 1000.0)
    centers = np.stack([cx, cy], axis=1)

    com = _run_estimator(centers, tex, target_blob=True)
    sem = _run_estimator(centers, tex, target_blob=False)
    assert np.median(np.abs(com.dx - sem.dx)) < 0.2


def test_mask_exclui_centro_e_bordas():
    m = camera.build_mask((360, 640))
    assert m[180, 320] == 0        # centro (alvos/crosshair)
    assert m[5, 320] == 0          # borda (HUD)
    assert m[60, 60] == 255        # fundo utilizavel
