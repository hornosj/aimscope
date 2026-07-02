"""Renderizador sintetico: 'sala' texturizada + camera que panora com traco
CONHECIDO. E o vetor dourado do lab: sabemos o movimento injetado, entao
sabemos o que o estimador DEVE recuperar."""

from __future__ import annotations

import cv2
import numpy as np


def make_texture(rng: np.random.Generator, w: int = 2600, h: int = 2000) -> np.ndarray:
    """Fundo com textura estavel (blobs + ruido suavizado), tipo sala do KovaaK's."""
    tex = rng.integers(40, 90, size=(h, w), dtype=np.uint8)
    tex = cv2.GaussianBlur(tex, (0, 0), 3.0)
    for _ in range(400):
        x, y = int(rng.uniform(0, w)), int(rng.uniform(0, h))
        r = int(rng.uniform(6, 30))
        cv2.circle(tex, (x, y), r, int(rng.uniform(90, 220)), -1)
    return cv2.GaussianBlur(tex, (0, 0), 1.0)


def render(texture: np.ndarray, centers_px: np.ndarray,
           size: tuple[int, int] = (640, 360),
           target_blob: bool = True):
    """Gera (t, frame) por centro de viewport. centers_px: (n, 2) float.
    target_blob: circulo movel no CENTRO (regiao de alvos) — tem que ser
    ignorado pela mascara/RANSAC do estimador."""
    w, h = size
    margin_x = w / 2 + 2
    margin_y = h / 2 + 2
    assert centers_px[:, 0].min() >= margin_x and centers_px[:, 0].max() <= texture.shape[1] - margin_x
    assert centers_px[:, 1].min() >= margin_y and centers_px[:, 1].max() <= texture.shape[0] - margin_y

    for i, (cx, cy) in enumerate(centers_px):
        frame = cv2.getRectSubPix(texture, (w, h), (float(cx), float(cy)))
        if target_blob:
            # alvo "strafando" perto do centro da tela
            tx = int(w / 2 + 40 * np.sin(i * 0.15))
            ty = int(h / 2 + 10 * np.cos(i * 0.11))
            frame = frame.copy()
            cv2.circle(frame, (tx, ty), 12, 250, -1)
        yield frame
