"""Leitura de video VFR-safe + deteccao da ROI de jogo.

YouTube entrega framerate variavel: os timestamps vem do CONTAINER
(CAP_PROP_POS_MSEC), nunca de indice*fps. Letterbox/overlay variam por video:
a ROI de jogo e detectada por variancia temporal (bordas mortas ficam fora).
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

import cv2
import numpy as np


@dataclass
class Roi:
    x0: int
    y0: int
    x1: int
    y1: int

    def crop(self, frame: np.ndarray) -> np.ndarray:
        return frame[self.y0 : self.y1, self.x0 : self.x1]

    @property
    def width(self) -> int:
        return self.x1 - self.x0

    @property
    def height(self) -> int:
        return self.y1 - self.y0


def frames(path: str | Path, gray: bool = True) -> Iterator[tuple[float, np.ndarray]]:
    """Gera (t_segundos, frame). t vem do container (VFR-safe); se o container
    nao reportar posicao, cai para indice/fps declarado (e ai VFR distorce —
    melhor que nada, mas registrado como limitacao)."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError(f"nao consegui abrir o video: {path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    idx = 0
    try:
        while True:
            t_ms = cap.get(cv2.CAP_PROP_POS_MSEC)
            ok, frame = cap.read()
            if not ok:
                return
            t = t_ms / 1000.0 if t_ms > 0 or idx == 0 else idx / fps
            if gray:
                frame = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            yield t, frame
            idx += 1
    finally:
        cap.release()


def sample_grays(path: str | Path, n_samples: int = 40) -> list[np.ndarray]:
    """Amostra ~n frames em UMA passada sequencial (grab + retrieve seletivo).
    Nunca usa seek aleatorio: em VP9/webm cada seek decodifica desde o ultimo
    keyframe e 80 seeks viravam dezenas de minutos por video."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError(f"nao consegui abrir o video: {path}")
    try:
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
        step = max(1, total // max(n_samples, 1))
        out: list[np.ndarray] = []
        idx = 0
        while True:
            if not cap.grab():
                break
            if idx % step == 0:
                ok, frame = cap.retrieve()
                if ok:
                    out.append(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY))
            idx += 1
    finally:
        cap.release()
    if len(out) < 3:
        raise ValueError(f"amostras insuficientes: {path}")
    return out


def detect_game_roi_from(samples: list[np.ndarray], dark_thresh: float = 8.0) -> Roi:
    """ROI ativa a partir de amostras ja coletadas (ver detect_game_roi)."""
    acc = np.max(np.stack([s.astype(np.float32) for s in samples]), axis=0)
    rows = np.where(acc.max(axis=1) > dark_thresh)[0]
    cols = np.where(acc.max(axis=0) > dark_thresh)[0]
    if len(rows) == 0 or len(cols) == 0:
        h, w = acc.shape
        return Roi(0, 0, w, h)
    return Roi(int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1)


def static_overlay_mask_from(samples: list[np.ndarray],
                             roi: Roi | None = None) -> np.ndarray:
    """Mascara de overlay estatico a partir de amostras ja coletadas
    (ver static_overlay_mask; limiar adaptativo identico)."""
    grays = [roi.crop(s) if roi is not None else s for s in samples]
    std = np.std(np.stack([g.astype(np.float32) for g in grays]), axis=0)
    thresh = max(0.75, 0.25 * float(np.percentile(std, 75)))
    mask = np.where(std > thresh, 255, 0).astype(np.uint8)
    mask = cv2.erode(mask, np.ones((9, 9), np.uint8))
    return mask


def static_overlay_mask(path: str | Path, roi: Roi | None = None,
                        n_samples: int = 40) -> np.ndarray:
    """Mascara (uint8, 255 = utilizavel) que EXCLUI pixels estaticos: HUD,
    timer, widgets de overlay. Criterio: desvio-padrao temporal baixo ao longo
    do video — o fundo muda quando a camera gira, overlay nao. Sem isso, em
    cena de baixa textura (paredes lisas + compressao do YouTube) os cantos
    mais fortes sao do HUD e o estimador trava em movimento zero.

    Wrapper de conveniencia (uma passada sequencial propria). Em pipeline,
    prefira sample_grays UMA vez + *_from — evita decodificar o video 2x."""
    return static_overlay_mask_from(sample_grays(path, n_samples), roi)


def detect_game_roi(path: str | Path, n_samples: int = 40, dark_thresh: float = 8.0) -> Roi:
    """Acha a regiao ativa do video: linhas/colunas cujo maximo temporal fica
    escuro em TODAS as amostras sao letterbox e caem fora.

    Wrapper de conveniencia (uma passada sequencial propria). Em pipeline,
    prefira sample_grays UMA vez + *_from — evita decodificar o video 2x."""
    return detect_game_roi_from(sample_grays(path, n_samples), dark_thresh)
