"""Estimador de camera: video -> traco de movimento angular (px/frame).

design-vod-lab.md §5. Feature tracking esparso (Shi-Tomasi + KLT) no FUNDO
estatico do KovaaK's, afim parcial com RANSAC entre frames consecutivos ->
componente de translacao ~ rotacao da camera (roll ~ 0, problema 2-DoF).
Mascara centro (alvos/crosshair) e bordas (HUD) antes; clusters de movimento
inconsistentes com rotacao rigida caem no RANSAC. Fallback: fluxo denso
Farneback + mediana robusta quando o esparso falha (cena de baixa textura).

Unidade: pixels/frame. So vira graus com FOV conhecido (runs proprias).
Convencao de sinal: deltas no ESTILO MOUSE (direita = +dx, mirar pra cima =
-dy), i.e. o negativo da translacao do fundo — assim o resample do sidecar
(que inverte y) produz "cima = positivo" igual ao sensor.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import cv2
import numpy as np

MIN_TRACKED = 12          # abaixo disso o esparso nao e confiavel -> fallback
# cross-check de grid-lock: em parede lisa, os cantos mais fortes podem ser
# artefatos de compressao em blocos — FIXOS na grade de pixels, nao no mundo.
# O KLT trava neles e reporta camera parada com inliers altissimos. Quando o
# esparso diz ~0 e a correlacao de fase (energia global de gradiente) diz que
# ha movimento, a fase vence com confianca reduzida.
GRIDLOCK_SPARSE_PX = 0.15
GRIDLOCK_PHASE_PX = 0.5
PHASE_SCALE = 0.5
PHASE_CONF = 0.3
QUALITY_LEVEL = 0.001     # relativo ao melhor canto; cena lisa (KovaaK's sob
                          # compressao) tem cantos fracos mas rastreaveis —
                          # 0.01 deixava so o punhado mais forte e empurrava
                          # quase tudo pro fallback denso, que subestima
CENTER_EXCLUDE = 0.42     # fracao central mascarada (alvos + crosshair)
EDGE_MARGIN = 0.05        # fracao das bordas mascarada (HUD)
MAX_CORNERS = 400
FARNEBACK_SCALE = 0.25    # downscale do fallback denso — ele so alimenta uma
                          # MEDIANA robusta, entao resolucao importa pouco e
                          # 0.25 e ~4x mais rapido que 0.5 (videos que caem no
                          # denso em todo frame estouravam 10min/video)
FALLBACK_CONF = 0.3


@dataclass
class EgoMotion:
    """Traco de movimento da camera por frame (px/frame, estilo mouse)."""

    t: np.ndarray          # timestamp do frame (s, do container)
    dx: np.ndarray         # px/frame
    dy: np.ndarray
    conf: np.ndarray       # 0..1 por frame (fracao de inliers; fallback = 0.3)

    @property
    def confidence(self) -> float:
        """Confianca agregada do estimador nesta run (mediana por frame)."""
        return float(np.median(self.conf)) if len(self.conf) else 0.0


def build_mask(shape: tuple[int, int],
               center_exclude: float = CENTER_EXCLUDE,
               edge_margin: float = EDGE_MARGIN) -> np.ndarray:
    """255 onde PODE haver features (fundo), 0 no centro (alvos) e bordas (HUD)."""
    h, w = shape
    mask = np.full((h, w), 255, dtype=np.uint8)
    mx, my = int(w * edge_margin), int(h * edge_margin)
    mask[:my, :] = 0
    mask[h - my :, :] = 0
    mask[:, :mx] = 0
    mask[:, w - mx :] = 0
    cw, ch = int(w * center_exclude / 2), int(h * center_exclude / 2)
    cx, cy = w // 2, h // 2
    mask[cy - ch : cy + ch, cx - cw : cx + cw] = 0
    return mask


def _sparse_shift(prev: np.ndarray, cur: np.ndarray,
                  mask: np.ndarray) -> tuple[float, float, float] | None:
    """Translacao do FUNDO entre dois frames via KLT + afim parcial RANSAC.
    Retorna (tx, ty, inlier_ratio) ou None se nao confiavel."""
    p0 = cv2.goodFeaturesToTrack(prev, maxCorners=MAX_CORNERS, qualityLevel=QUALITY_LEVEL,
                                 minDistance=8, mask=mask, blockSize=7)
    if p0 is None or len(p0) < MIN_TRACKED:
        return None
    p1, status, _ = cv2.calcOpticalFlowPyrLK(
        prev, cur, p0, None, winSize=(21, 21), maxLevel=3,
        criteria=(cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 30, 0.01))
    good = status.ravel() == 1
    if good.sum() < MIN_TRACKED:
        return None
    src, dst = p0[good], p1[good]
    m, inliers = cv2.estimateAffinePartial2D(
        src, dst, method=cv2.RANSAC, ransacReprojThreshold=2.0)
    if m is None or inliers is None or inliers.sum() < MIN_TRACKED:
        return None
    ratio = float(inliers.sum()) / float(len(src))
    return float(m[0, 2]), float(m[1, 2]), ratio


class _PhaseCorr:
    """Correlacao de fase com janela de Hanning (downscale). Pega a translacao
    dominante da ENERGIA de gradiente global — imune ao grid-lock de cantos.
    A janela zera o CENTRO (alvo em movimento nao e camera) e o OVERLAY
    ESTATICO (HUD parado domina a energia numa sala lisa e puxa o pico pra
    zero — mesmo bug do esparso, pelo outro canal)."""

    def __init__(self, center_exclude: float = CENTER_EXCLUDE,
                 extra_mask: np.ndarray | None = None) -> None:
        self._win: np.ndarray | None = None
        self._center = center_exclude
        self._extra = extra_mask

    def _window(self, shape: tuple[int, int]) -> np.ndarray:
        h, w = shape
        win = cv2.createHanningWindow((w, h), cv2.CV_32F)
        cw, ch = int(w * self._center / 2), int(h * self._center / 2)
        hole = np.ones((h, w), np.float32)
        hole[h // 2 - ch : h // 2 + ch, w // 2 - cw : w // 2 + cw] = 0.0
        if self._extra is not None:
            small = cv2.resize(self._extra, (w, h), interpolation=cv2.INTER_AREA)
            hole *= (small.astype(np.float32) / 255.0)
        hole = cv2.GaussianBlur(hole, (0, 0), min(w, h) * 0.03)
        return win * hole

    def shift(self, prev: np.ndarray, cur: np.ndarray) -> tuple[float, float]:
        p = cv2.resize(prev, None, fx=PHASE_SCALE, fy=PHASE_SCALE).astype(np.float32)
        c = cv2.resize(cur, None, fx=PHASE_SCALE, fy=PHASE_SCALE).astype(np.float32)
        if self._win is None or self._win.shape != p.shape:
            self._win = self._window(p.shape)
        (tx, ty), _resp = cv2.phaseCorrelate(p, c, self._win)
        return tx / PHASE_SCALE, ty / PHASE_SCALE


def _dense_shift(prev: np.ndarray, cur: np.ndarray,
                 mask: np.ndarray) -> tuple[float, float]:
    """Fallback: Farneback denso (downscale) + mediana robusta fora da mascara."""
    small_prev = cv2.resize(prev, None, fx=FARNEBACK_SCALE, fy=FARNEBACK_SCALE)
    small_cur = cv2.resize(cur, None, fx=FARNEBACK_SCALE, fy=FARNEBACK_SCALE)
    flow = cv2.calcOpticalFlowFarneback(
        small_prev, small_cur, None, pyr_scale=0.5, levels=3, winsize=15,
        iterations=3, poly_n=5, poly_sigma=1.2, flags=0)
    m = cv2.resize(mask, (small_prev.shape[1], small_prev.shape[0])) > 0
    if not m.any():
        m = np.ones_like(m)
    tx = float(np.median(flow[..., 0][m])) / FARNEBACK_SCALE
    ty = float(np.median(flow[..., 1][m])) / FARNEBACK_SCALE
    return tx, ty


def estimate(frame_iter: Iterable[tuple[float, np.ndarray]],
             center_exclude: float = CENTER_EXCLUDE,
             edge_margin: float = EDGE_MARGIN,
             extra_mask: np.ndarray | None = None) -> EgoMotion:
    """Consome (t, frame_gray) e devolve o traco de ego-motion da camera.

    extra_mask (255 = utilizavel): tipicamente video.static_overlay_mask —
    exclui HUD/overlays estaticos, que em cena de baixa textura dominam os
    cantos e travam o estimador em movimento zero."""
    ts: list[float] = []
    dxs: list[float] = []
    dys: list[float] = []
    confs: list[float] = []

    prev: np.ndarray | None = None
    mask: np.ndarray | None = None
    phase = _PhaseCorr(center_exclude, extra_mask=extra_mask)

    for t, frame in frame_iter:
        if prev is not None:
            if mask is None or mask.shape != frame.shape:
                mask = build_mask(frame.shape, center_exclude, edge_margin)
                if extra_mask is not None:
                    if extra_mask.shape != frame.shape:
                        raise ValueError("extra_mask com shape diferente do frame")
                    mask = cv2.bitwise_and(mask, extra_mask)
            res = _sparse_shift(prev, frame, mask)
            if res is not None:
                tx, ty, conf = res
                if abs(tx) < GRIDLOCK_SPARSE_PX and abs(ty) < GRIDLOCK_SPARSE_PX:
                    px, py = phase.shift(prev, frame)
                    if abs(px) > GRIDLOCK_PHASE_PX or abs(py) > GRIDLOCK_PHASE_PX:
                        tx, ty, conf = px, py, PHASE_CONF
            else:
                tx, ty = _dense_shift(prev, frame, mask)
                conf = FALLBACK_CONF
            # fundo translada oposto a camera; sinal estilo mouse (ver docstring)
            ts.append(t)
            dxs.append(-tx)
            dys.append(-ty)
            confs.append(conf)
        prev = frame

    return EgoMotion(
        t=np.asarray(ts, dtype=np.float64),
        dx=np.asarray(dxs, dtype=np.float64),
        dy=np.asarray(dys, dtype=np.float64),
        conf=np.asarray(confs, dtype=np.float64),
    )
