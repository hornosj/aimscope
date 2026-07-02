"""Vetor dourado do lab (o gate §2.1 em miniatura, sem video de terceiros):

o MESMO traco angular conhecido passa (a) pelo caminho do SENSOR (1 kHz, graus,
limiares nativos) e (b) pelo caminho de VOD (render 60fps -> estimador de
camera -> px -> limiares nominais). As metricas Tier 1 tem que concordar —
senao a ancora seria calibrada num sistema de coordenadas e aplicada em outro.
"""

import numpy as np
import pytest

from aimscope_sidecar.kinematics import resample
from aimscope_sidecar.segments import movement_bouts as sensor_bouts
from aimscope_sidecar.synth import min_jerk

from aimscope_lab import camera, tier1
from aimscope_lab.bouts import bouts_for
from aimscope_lab.trace import px_per_deg_of, to_kinematics
from rendering import make_texture, render

FS = 1000.0
FPS = 60.0
SIZE = (640, 360)
HFOV = 103.0


def golden_trace(n_moves=26, overshoot_frac=0.15, seed=11):
    """Traco angular (deg) com overshoot DELIBERADO e posicao contida:
    vai-e-volta alternado, igual em espirito ao synth do sensor."""
    rng = np.random.default_rng(seed)
    xs, ys = [np.zeros(int(0.8 * FS))], [np.zeros(int(0.8 * FS))]

    def append(mx, my):
        xs.append(xs[-1][-1] + mx)
        ys.append(ys[-1][-1] + my)

    def hold(dur):
        n = int(dur * FS)
        xs.append(np.full(n, xs[-1][-1]))
        ys.append(np.full(n, ys[-1][-1]))

    for i in range(n_moves):
        amp = rng.uniform(8.0, 18.0)
        sign = -1.0 if xs[-1][-1] > 0 else 1.0     # volta pro centro: contido
        ang = rng.uniform(-0.35, 0.35)             # quase horizontal
        dur = 0.14 + 0.004 * amp
        over = amp * (1.0 + overshoot_frac)
        append(min_jerk(sign * over * np.cos(ang), dur),
               min_jerk(sign * over * np.sin(ang), dur))
        # correcao de volta (submovimento) — e o overshoot que o Tier 1 mede
        cdur = 0.09
        append(min_jerk(-sign * amp * overshoot_frac * np.cos(ang), cdur),
               min_jerk(-sign * amp * overshoot_frac * np.sin(ang), cdur))
        hold(rng.uniform(0.35, 0.6))

    x = np.concatenate(xs)
    y = np.concatenate(ys)
    t = np.arange(len(x)) / FS
    return t, x, y


@pytest.fixture(scope="module")
def pipelines():
    t, x, y = golden_trace()

    # --- caminho do SENSOR: deltas em graus na grade de 1 kHz -------------
    dx = np.diff(x, prepend=x[0])
    dy_mouse = -np.diff(y, prepend=y[0])   # convencao raw-input (y de tela)
    kin_s = resample(t, dx, dy_mouse, deg_per_count=1.0)
    m_sensor = tier1.compute(kin_s, sensor_bouts(kin_s))

    # --- caminho de VOD: render 60fps -> estimador -> px ------------------
    ppd = px_per_deg_of(SIZE[0], HFOV)
    idx = (np.arange(0, t[-1], 1.0 / FPS) * FS).astype(int)
    cx = 1300 + x[idx] * ppd
    cy = 1000 - y[idx] * ppd               # cima = cy menor
    centers = np.stack([cx, cy], axis=1)
    tex = make_texture(np.random.default_rng(2))
    frames = ((i / FPS, f) for i, f in enumerate(render(tex, centers, size=SIZE)))
    ego = camera.estimate(frames)
    kin_v = to_kinematics(ego)
    m_video = tier1.compute(kin_v, bouts_for(kin_v, roi_width_px=SIZE[0]))

    return m_sensor, m_video


def test_ambos_os_caminhos_medem(pipelines):
    m_sensor, m_video = pipelines
    for key in ("overshoot", "corrections", "sparc"):
        assert key in m_sensor, f"sensor sem {key}"
        assert key in m_video, f"video sem {key}"


def test_overshoot_concorda(pipelines):
    m_sensor, m_video = pipelines
    # overshoot injetado ~1.15; os dois caminhos tem que enxergar o MESMO
    assert m_sensor["overshoot"] == pytest.approx(1.15, abs=0.06)
    assert m_video["overshoot"] == pytest.approx(m_sensor["overshoot"], abs=0.06)


def test_correcoes_concordam(pipelines):
    m_sensor, m_video = pipelines
    assert abs(m_video["corrections"] - m_sensor["corrections"]) <= 0.7


def test_sparc_concorda(pipelines):
    m_sensor, m_video = pipelines
    assert abs(m_video["sparc"] - m_sensor["sparc"]) <= 0.5


def test_video_distingue_suave_de_corrigido():
    """Sensibilidade: o caminho de VOD sozinho separa run limpa de run com
    overshoot — condicao minima pra ancora ter poder discriminativo."""

    def video_metrics(overshoot_frac, seed):
        t, x, y = golden_trace(overshoot_frac=overshoot_frac, seed=seed)
        ppd = px_per_deg_of(SIZE[0], HFOV)
        idx = (np.arange(0, t[-1], 1.0 / FPS) * FS).astype(int)
        centers = np.stack([1300 + x[idx] * ppd, 1000 - y[idx] * ppd], axis=1)
        tex = make_texture(np.random.default_rng(5))
        frames = ((i / FPS, f) for i, f in enumerate(render(tex, centers, size=SIZE)))
        kin = to_kinematics(camera.estimate(frames))
        return tier1.compute(kin, bouts_for(kin, roi_width_px=SIZE[0]))

    limpo = video_metrics(0.02, seed=21)
    sujo = video_metrics(0.20, seed=21)
    assert limpo["overshoot"] < sujo["overshoot"]
