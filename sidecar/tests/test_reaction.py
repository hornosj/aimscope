"""Validade do reaction time: injetamos lag CONHECIDO evento->movimento e
exigimos que o pipeline o recupere."""

import numpy as np

from aimscope_sidecar.reaction import ScreenTrace, compute, movement_onsets, visual_events

FS = 1000.0
LAG_S = 0.220  # lag injetado


def _make_screen(event_times, fps=120.0, dur=15.0, rng=None):
    rng = rng or np.random.default_rng(7)
    t = np.arange(0, dur, 1.0 / fps)
    diff = rng.normal(0.5, 0.1, len(t)).clip(0.01)  # ruído de fundo
    for ev in event_times:
        i = int(ev * fps)
        diff[i] += 40.0  # spawn: mudança abrupta na ROI
    return ScreenTrace(t=t, diff=diff)


def _make_speed(onset_times, dur=15.0):
    t = np.arange(0, dur, 1.0 / FS)
    speed = np.abs(np.random.default_rng(8).normal(0.5, 0.2, len(t)))
    for on in onset_times:
        i = int(on * FS)
        n = int(0.30 * FS)
        pulse = 120.0 * np.sin(np.linspace(0, np.pi, n)) ** 2
        speed[i : i + n] = np.maximum(speed[i : i + n], pulse[: len(speed[i : i + n])])
    return t, speed


EVENTS = [2.0, 4.0, 6.0, 8.0, 10.0, 12.0]


def test_visual_events_detected():
    scr = _make_screen(EVENTS)
    ev = visual_events(scr)
    assert len(ev) == len(EVENTS)
    assert np.allclose(ev, EVENTS, atol=0.02)


def test_onsets_detected():
    t, speed = _make_speed([e + LAG_S for e in EVENTS])
    on = movement_onsets(t, speed, FS)
    for e in EVENTS:
        assert np.min(np.abs(on - (e + LAG_S))) < 0.03


def test_rt_recovers_injected_lag():
    scr = _make_screen(EVENTS)
    t, speed = _make_speed([e + LAG_S for e in EVENTS])
    r = compute(scr, t, speed, FS)
    assert r is not None
    assert abs(r["rt_median_ms"] - LAG_S * 1000.0) < 25.0, r
    assert r["n_matched"] >= 5
    assert r["anticipation_rate"] < 0.2


def test_too_few_events_returns_none():
    scr = _make_screen([2.0])
    t, speed = _make_speed([2.22])
    assert compute(scr, t, speed, FS) is None
