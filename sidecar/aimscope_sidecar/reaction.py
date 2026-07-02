"""Reaction time visual: eventos de mudança na tela -> onset do movimento.

Entrada: screen.parquet do gravador (ts_qpc + diff global + grade 3x3 de
deltas de luminância por frame, ROI central). Sem vídeo — só o sinal.

Pipeline:
1. Eventos visuais: z-robusto (mediana/MAD) do diff; evento = z > Z_TH com
   período refratário (spawn de alvo, kill-pop, flash).
2. Onsets de movimento: velocidade cruza limiar vindo de repouso.
3. RT = primeiro onset na janela [RT_MIN, RT_MAX] após o evento.
   Onset antes de RT_MIN = antecipação (chute), contado separadamente.

Honestidade: sem posição do alvo, isto mede REAÇÃO A MUDANÇA VISUAL
(reaction/simple). Direção certa/errada (reaction/choice) exige detecção de
alvo — fase seguinte.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pyarrow.parquet as pq

Z_TH = 6.0            # limiar em desvios robustos
REFRACTORY_S = 0.30   # min entre eventos
RT_MIN_S = 0.08       # abaixo disso = antecipação, não reação
RT_MAX_S = 0.70       # acima disso = não pareia (distração/sem alvo)
ONSET_SPEED_FRAC = 0.15  # onset = velocidade cruza 15% do p95 da sessão
ONSET_QUIET_S = 0.06     # exigência de repouso antes do onset


@dataclass
class ScreenTrace:
    t: np.ndarray      # segundos desde o início da sessão
    diff: np.ndarray   # delta global por frame


def load_screen(session_dir, manifest) -> ScreenTrace | None:
    p = session_dir / "screen.parquet"
    if not p.exists():
        return None
    tb = pq.read_table(p)
    if tb.num_rows < 30:
        return None
    ts = tb["ts_qpc"].to_numpy()
    t = (ts - manifest["qpc_at_start"]) / manifest["qpc_frequency"]
    return ScreenTrace(t=t.astype(np.float64), diff=tb["diff"].to_numpy().astype(np.float64))


def visual_events(scr: ScreenTrace) -> np.ndarray:
    """Timestamps (s) de mudança visual abrupta, com refratário."""
    d = scr.diff
    med = np.median(d)
    mad = np.median(np.abs(d - med)) + 1e-9
    z = (d - med) / (1.4826 * mad)
    idx = np.where(z > Z_TH)[0]
    events = []
    last = -1e9
    for i in idx:
        if scr.t[i] - last >= REFRACTORY_S:
            events.append(scr.t[i])
            last = scr.t[i]
    return np.array(events)


def movement_onsets(t: np.ndarray, speed: np.ndarray, fs: float) -> np.ndarray:
    """Instantes em que a velocidade cruza o limiar vindo de repouso.

    O cruzamento do limiar ALTO acontece ~dezenas de ms depois do início real
    do movimento (a velocidade sobe suave); por isso, ao detectar, fazemos
    BACKTRACK até o piso de ruído — o onset reportado é o início verdadeiro.
    """
    th = ONSET_SPEED_FRAC * np.percentile(speed, 95)
    th = max(th, 1e-9)
    floor = max(2.0 * np.median(speed), 0.02 * th)
    quiet_n = int(ONSET_QUIET_S * fs)
    max_back = int(0.10 * fs)
    above = speed >= th
    onsets = []
    i = quiet_n
    while i < len(speed):
        if above[i] and not above[i - quiet_n : i].any():
            j = i
            while j > 0 and speed[j - 1] > floor and i - j < max_back:
                j -= 1
            onsets.append(t[j])
            i += int(0.05 * fs)
        i += 1
    return np.array(onsets)


def compute(scr: ScreenTrace, kin_t: np.ndarray, kin_speed: np.ndarray, fs: float) -> dict | None:
    events = visual_events(scr)
    if len(events) < 5:
        return None
    onsets = movement_onsets(kin_t, kin_speed, fs)
    if len(onsets) == 0:
        return None

    rts = []
    anticipations = 0
    for ev in events:
        after = onsets[onsets > ev - RT_MIN_S]
        if len(after) == 0:
            continue
        first = after[0]
        dt = first - ev
        if dt < RT_MIN_S:
            anticipations += 1
        elif dt <= RT_MAX_S:
            rts.append(dt * 1000.0)

    if len(rts) < 5:
        return None
    rts = np.array(rts)
    considered = len(rts) + anticipations
    return {
        "n_events": int(len(events)),
        "n_matched": int(len(rts)),
        "rt_median_ms": float(np.median(rts)),
        "rt_p25_ms": float(np.percentile(rts, 25)),
        "rt_p75_ms": float(np.percentile(rts, 75)),
        "anticipation_rate": float(anticipations / considered) if considered else 0.0,
    }
