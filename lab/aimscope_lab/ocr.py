"""OCR do score na tela — fonte do rank do VOD (design §2: nunca o titulo).

Melhor esforco: amostra frames do fim do video (tela de resultado do
KovaaK's), binariza e le digitos com pytesseract (dependencia opcional).
Quando o OCR nao fecha, o operador passa --score lido DA TELA a mao — a
regra "score na tela, nao no titulo" continua valendo; muda so quem le.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path

import cv2
import numpy as np

TAIL_FRACTION = 0.15      # tela de score vive no fim do VOD
N_SAMPLES = 25
MIN_AGREEING = 3          # mesmo valor lido em >=3 frames = confiavel


def _read_score_in_frame(gray: np.ndarray) -> float | None:
    try:
        import pytesseract
    except ImportError:
        return None
    h, w = gray.shape
    # metade superior central: onde o KovaaK's poe "Score" na tela final
    roi = gray[0 : int(h * 0.6), int(w * 0.2) : int(w * 0.8)]
    up = cv2.resize(roi, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
    _, bw = cv2.threshold(up, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    text = pytesseract.image_to_string(
        bw, config="--psm 6 -c tessedit_char_whitelist=0123456789.,Score: ")
    best: float | None = None
    for line in text.splitlines():
        if "score" not in line.lower():
            continue
        digits = "".join(c for c in line if c.isdigit() or c == ".")
        try:
            val = float(digits)
        except ValueError:
            continue
        if val > 0:
            best = val
    return best


def score_from_video(path: str | Path) -> tuple[float | None, int]:
    """(score, n_frames_concordando). None se OCR indisponivel/inconclusivo."""
    cap = cv2.VideoCapture(str(path))
    if not cap.isOpened():
        raise ValueError(f"nao consegui abrir o video: {path}")
    try:
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0
        if total <= 0:
            return None, 0
        start = int(total * (1.0 - TAIL_FRACTION))
        picks = np.linspace(start, total - 1, num=N_SAMPLES, dtype=int)
        votes: Counter[float] = Counter()
        for p in picks:
            cap.set(cv2.CAP_PROP_POS_FRAMES, int(p))
            ok, frame = cap.read()
            if not ok:
                continue
            v = _read_score_in_frame(cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY))
            if v is not None:
                votes[v] += 1
    finally:
        cap.release()
    if not votes:
        return None, 0
    score, n = votes.most_common(1)[0]
    return (score, n) if n >= MIN_AGREEING else (None, n)
