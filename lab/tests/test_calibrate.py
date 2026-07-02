"""Calibracao: populacao sintetica com relacao CONHECIDA metrica<->nivel tem
que sair como tabela de ancora monotona que recupera essa relacao."""

import numpy as np
import pytest

from aimscope_lab import db
from aimscope_lab.calibrate import LOW_N_MARK, anchor_for, build, emit_edn, pava
from aimscope_lab.edn import K, loads
from aimscope_lab.ruler import RANKS


def test_pava_monotono():
    y = [1.0, 3.0, 2.0, 4.0]
    out = pava(y, [1.0] * 4, increasing=True)
    assert out == sorted(out)
    assert out[1] == pytest.approx(out[2])  # violadores poolados
    dec = pava([4.0, 2.0, 3.0, 1.0], [1.0] * 4, increasing=False)
    assert dec == sorted(dec, reverse=True)


def _population(rng, metric_fn, n_per_bucket=8, buckets=("silver", "gold", "diamond", "master")):
    """overshoot cai com o nivel: metric_fn(level) + ruido."""
    obs = []
    for bucket in buckets:
        energy = (RANKS.index(bucket) + 1) * 100 + 50
        level = energy / 12.0
        for i in range(n_per_bucket):
            obs.append(db.Observation(
                video_id=f"{bucket}{i}", source_url=None, scenario_name="pasu",
                ocr_score=1.0, energy=energy, rank_bucket=bucket,
                metric_key="overshoot",
                metric_value=metric_fn(level) + rng.normal(0, 0.005),
                estimator_confidence=0.8, tier=1,
            ))
    return obs


def test_anchor_recupera_relacao_injetada():
    rng = np.random.default_rng(7)
    # relacao injetada: overshoot = 1.4 - 0.004 * nivel (decrescente)
    obs = _population(rng, lambda lvl: 1.4 - 0.004 * lvl)
    table, meta = anchor_for(obs)

    xs = [p[0] for p in table]
    ls = [p[1] for p in table]
    assert xs == sorted(xs)                      # eixo x ordenado (lerp)
    assert ls == sorted(ls, reverse=True)        # monotonia na direcao certa
    # recupera a relacao: no ponto x, nivel ~ (1.4 - x) / 0.004
    for x, l in table:
        assert l == pytest.approx((1.4 - x) / 0.004, abs=4.0)
    assert meta[K("n")] == len(obs)
    assert meta[K("low-n")] == (len(obs) < LOW_N_MARK)
    assert set(meta[K("buckets")]) == {K("silver"), K("gold"), K("diamond"), K("master")}


def test_correcao_linear_aplicada_antes():
    rng = np.random.default_rng(7)
    # video mede overshoot com vies: m_video = m_sensor - 0.05
    obs = _population(rng, lambda lvl: (1.4 - 0.004 * lvl) - 0.05)
    table_corr, _ = anchor_for(obs, correction={"a": 1.0, "b": 0.05})
    for x, l in table_corr:
        assert l == pytest.approx((1.4 - x) / 0.004, abs=4.0)


def test_build_exclui_metrica_sem_gate():
    rng = np.random.default_rng(7)
    obs = _population(rng, lambda lvl: 1.4 - 0.004 * lvl)
    anchors, meta, excluded = build(obs, passed={})  # nada passou no gate
    assert anchors == {}
    assert any("ground-truth" in e for e in excluded)

    anchors, meta, _ = build(obs, passed={"overshoot": None})
    assert K("overshoot") in anchors


def test_poucos_buckets_nao_viram_ancora():
    rng = np.random.default_rng(7)
    obs = _population(rng, lambda lvl: 1.4 - 0.004 * lvl, buckets=("gold", "master"))
    assert anchor_for(obs) is None


def test_emit_edn_parseavel_e_no_formato_do_design():
    rng = np.random.default_rng(7)
    obs = _population(rng, lambda lvl: 1.4 - 0.004 * lvl)
    anchors, meta, _ = build(obs, passed={"overshoot": None})
    text = emit_edn(anchors, meta, {"n_videos": 32, "n_observations": 32})
    doc = loads(text)
    assert "population.db" in doc[K("anchors/source")]
    table = doc[K("anchors")][K("overshoot")]
    assert all(len(p) == 2 for p in table)
    assert doc[K("meta")][K("overshoot")][K("n")] == 32
