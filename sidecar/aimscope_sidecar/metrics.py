"""Metricas de mecanica de mira.

Todas as metricas cinematicas sao POR BOUT (movimento intencional), nunca
sobre a sessao inteira — ver segments.py sobre confundimento.

Referencias:
- SPARC: Balasubramanian et al. 2015, "On the analysis of movement smoothness".
- LDLJ (log dimensionless jerk): idem.
- Submovimentos corretivos: decomposicao do perfil de velocidade
  (Meyer et al. 1988, modelo de submovimentos otimizados).
- Tremor fisiologico: banda 8-12 Hz (Elble & Koller 1990).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

import numpy as np
from scipy.signal import find_peaks, welch

from .kinematics import Kinematics
from .segments import Bout

TREMOR_BAND = (8.0, 12.0)
TREMOR_TOTAL_BAND = (2.0, 20.0)


# ---------------------------------------------------------------- smoothness

def sparc(speed: np.ndarray, fs: float, padlevel: int = 4,
          fc: float = 10.0, amp_th: float = 0.05) -> float:
    """Spectral Arc Length. Mais proximo de 0 = mais suave (valores negativos)."""
    n = len(speed)
    if n < 8 or np.max(speed) <= 0:
        return float("nan")
    nfft = int(2 ** np.ceil(np.log2(n) + padlevel))
    f = np.fft.rfftfreq(nfft, 1.0 / fs)
    mag = np.abs(np.fft.rfft(speed, nfft))
    mag = mag / np.max(mag)

    sel = f <= fc
    f_sel, m_sel = f[sel], mag[sel]
    above = np.where(m_sel >= amp_th)[0]
    if len(above) < 2:
        return float("nan")
    f_sel = f_sel[above[0]: above[-1] + 1]
    m_sel = m_sel[above[0]: above[-1] + 1]

    df = np.diff(f_sel) / (f_sel[-1] - f_sel[0])
    dm = np.diff(m_sel)
    return float(-np.sum(np.sqrt(df ** 2 + dm ** 2)))


def ldlj(speed: np.ndarray, fs: float) -> float:
    """Log dimensionless jerk do perfil de velocidade. Maior (menos negativo) = mais suave."""
    n = len(speed)
    if n < 5 or np.max(speed) <= 0:
        return float("nan")
    dt = 1.0 / fs
    duration = n * dt
    accel2 = np.gradient(speed, dt)          # d(speed)/dt
    jerk_int = np.trapezoid(np.gradient(accel2, dt) ** 2, dx=dt)
    dlj = (duration ** 3 / np.max(speed) ** 2) * jerk_int
    return float(-np.log(dlj)) if dlj > 0 else float("nan")


# ------------------------------------------------------------------- bouts

@dataclass
class BoutFeatures:
    t_start: float
    t_end: float
    duration_ms: float
    amplitude: float          # deslocamento liquido (deg ou counts)
    path_length: float
    efficiency: float         # amplitude / path_length (1.0 = trajeto perfeito)
    peak_speed: float
    n_submovements: int       # picos de velocidade; 1 = balistico puro
    n_corrections: int        # n_submovements - 1 (>=0)
    overshoot_ratio: float    # >1.0 = passou do alvo e voltou
    sparc: float
    ldlj: float
    time_peak_to_end_ms: float  # fase de homing (do pico de vel. ao fim)


def bout_features(kin: Kinematics, bout: Bout) -> BoutFeatures:
    s = bout.slice()
    x, y = kin.x[s], kin.y[s]
    speed = kin.speed[s]
    n = len(speed)
    dt = 1.0 / kin.fs

    net = np.hypot(x[-1] - x[0], y[-1] - y[0])
    path = float(np.sum(np.hypot(np.diff(x), np.diff(y))))
    eff = float(net / path) if path > 0 else 1.0

    peak = float(np.max(speed))
    i_peak = int(np.argmax(speed))

    # submovimentos = picos proeminentes no perfil de velocidade
    peaks, _ = find_peaks(speed, prominence=0.10 * peak, height=0.05 * peak)
    n_sub = max(1, len(peaks))

    # overshoot: projecao no eixo principal do movimento
    ux, uy = (x[-1] - x[0], y[-1] - y[0])
    overshoot = 1.0
    if net > 1e-9:
        ux, uy = ux / net, uy / net
        proj = (x - x[0]) * ux + (y - y[0]) * uy
        final = proj[-1]
        if final > 1e-9:
            overshoot = float(np.max(proj) / final)

    return BoutFeatures(
        t_start=float(kin.t[bout.i0]),
        t_end=float(kin.t[bout.i1 - 1]),
        duration_ms=n * dt * 1000.0,
        amplitude=float(net),
        path_length=path,
        efficiency=eff,
        peak_speed=peak,
        n_submovements=n_sub,
        n_corrections=n_sub - 1,
        overshoot_ratio=overshoot,
        sparc=sparc(speed, kin.fs),
        ldlj=ldlj(speed, kin.fs),
        time_peak_to_end_ms=(n - i_peak) * dt * 1000.0,
    )


# ------------------------------------------------------------------ tremor

@dataclass
class TremorResult:
    band_power_ratio: float    # potencia 8-12Hz / potencia 2-20Hz (0..1)
    band_rms: float            # RMS da velocidade em 8-12Hz (unidade/s)
    peak_freq_hz: float        # pico espectral dentro de 2-20Hz
    total_quiet_s: float
    freqs: np.ndarray = field(repr=False, default=None)
    psd: np.ndarray = field(repr=False, default=None)


def tremor(kin: Kinematics, quiet: list[Bout]) -> TremorResult | None:
    segs = [q for q in quiet if q.i1 - q.i0 >= int(0.5 * kin.fs)]
    if not segs:
        return None
    nper = min(1024, min(q.i1 - q.i0 for q in segs))
    psds = []
    total_s = 0.0
    for q in segs:
        for comp in (kin.vx[q.slice()], kin.vy[q.slice()]):
            f, p = welch(comp - np.mean(comp), fs=kin.fs, nperseg=nper)
            psds.append(p)
        total_s += (q.i1 - q.i0) / kin.fs
    psd = np.mean(psds, axis=0)

    def band_power(lo, hi):
        m = (f >= lo) & (f <= hi)
        return float(np.trapezoid(psd[m], f[m])) if np.any(m) else 0.0

    p_band = band_power(*TREMOR_BAND)
    p_total = band_power(*TREMOR_TOTAL_BAND)
    m_total = (f >= TREMOR_TOTAL_BAND[0]) & (f <= TREMOR_TOTAL_BAND[1])
    peak_f = float(f[m_total][np.argmax(psd[m_total])]) if np.any(m_total) else float("nan")

    return TremorResult(
        band_power_ratio=p_band / p_total if p_total > 0 else 0.0,
        band_rms=float(np.sqrt(2.0 * p_band)),  # vx e vy somados na media
        peak_freq_hz=peak_f,
        total_quiet_s=total_s,
        freqs=f,
        psd=psd,
    )


# --------------------------------------------------- alinhamento kill<->click

@dataclass
class KillMatch:
    kill_index: int
    kill_t: float          # tempo de sessao (s) apos aplicar offset
    click_t: float
    bout: BoutFeatures | None   # bout de aquisicao que precede o clique
    acquisition_ms: float | None  # inicio do bout -> clique


def align_kills_to_clicks(
    kill_times_wall: list[datetime | None],
    wall_start: datetime,
    click_times: np.ndarray,
    tolerance_s: float = 2.0,
) -> tuple[float | None, list[tuple[int, float, float]]]:
    """Estima o offset CSV<->sessao casando kills com o clique mais proximo.

    Retorna (offset_mediano, [(kill_idx, kill_t_corrigido, click_t)]).
    O offset absorve deriva de relogio e o atraso jogo->CSV.
    """
    if len(click_times) == 0:
        return None, []
    kill_t = [
        (kt - wall_start).total_seconds() if kt is not None else None
        for kt in kill_times_wall
    ]
    offsets = []
    for t in kill_t:
        if t is None:
            continue
        d = click_times - t
        near = d[np.abs(d) <= tolerance_s]
        if len(near):
            offsets.append(near[np.argmin(np.abs(near))])
    if len(offsets) < 3:
        return None, []
    offset = float(np.median(offsets))
    mad = float(np.median(np.abs(np.array(offsets) - offset)))
    if mad > 0.25:
        return None, []  # alinhamento nao confiavel

    matches = []
    for i, t in enumerate(kill_t):
        if t is None:
            continue
        t_corr = t + offset
        d = np.abs(click_times - t_corr)
        j = int(np.argmin(d))
        if d[j] <= 0.5:
            matches.append((i, t_corr, float(click_times[j])))
    return offset, matches


def kill_windows(
    matches: list[tuple[int, float, float]],
    bouts: list[BoutFeatures],
) -> list[KillMatch]:
    """Para cada kill casada, acha o bout de aquisicao que termina perto do clique."""
    out = []
    for kill_idx, kill_t, click_t in matches:
        best = None
        for b in bouts:
            # bout que termina ate 300ms antes do clique (micro-ajuste final e quiet)
            if b.t_start >= click_t - 1.5 and b.t_end <= click_t + 0.05:
                if best is None or b.t_end > best.t_end:
                    best = b
        acq = (click_t - best.t_start) * 1000.0 if best else None
        out.append(KillMatch(
            kill_index=kill_idx, kill_t=kill_t, click_t=click_t,
            bout=best, acquisition_ms=acq,
        ))
    return out


# ------------------------------------------------------------------ resumo

def robust_summary(values: list[float]) -> dict:
    v = np.array([x for x in values if x is not None and np.isfinite(x)])
    if len(v) == 0:
        return {"n": 0}
    return {
        "n": int(len(v)),
        "median": float(np.median(v)),
        "p25": float(np.percentile(v, 25)),
        "p75": float(np.percentile(v, 75)),
        "mean": float(np.mean(v)),
    }
