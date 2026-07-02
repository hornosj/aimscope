"""Testes de validade: geramos sessoes sinteticas com propriedades CONHECIDAS
e verificamos que as metricas medem o que foi injetado."""

import numpy as np
import pytest

from aimscope_sidecar.__main__ import analyze
from aimscope_sidecar.kovaaks import parse_stats_csv
from aimscope_sidecar.synth import write_session


@pytest.fixture(scope="module")
def clean_session(tmp_path_factory):
    d = tmp_path_factory.mktemp("clean")
    write_session(d, tremor_amp_deg=0.0, corrections=False, seed=1)
    return d, analyze(d, html=False)


@pytest.fixture(scope="module")
def tremor_session(tmp_path_factory):
    d = tmp_path_factory.mktemp("tremor")
    write_session(d, tremor_amp_deg=0.10, corrections=False, seed=1)
    return d, analyze(d, html=False)


@pytest.fixture(scope="module")
def corrected_session(tmp_path_factory):
    d = tmp_path_factory.mktemp("corr")
    write_session(d, tremor_amp_deg=0.0, corrections=True, seed=1)
    return d, analyze(d, html=False)


def test_pipeline_produces_metrics(clean_session):
    d, m = clean_session
    assert (d / "metrics.json").exists()
    assert m["n_bouts"] > 20
    assert m["unit"] == "deg"


def test_clean_movements_are_efficient(clean_session):
    _, m = clean_session
    # jerk minimo em linha reta: eficiencia ~1
    assert m["bouts_summary"]["efficiency"]["median"] > 0.97
    # sem correcoes injetadas: mediana de correcoes = 0
    assert m["bouts_summary"]["n_corrections"]["median"] == 0


def test_corrections_are_detected(clean_session, corrected_session):
    _, clean = clean_session
    _, corr = corrected_session
    assert (
        corr["bouts_summary"]["n_corrections"]["mean"]
        > clean["bouts_summary"]["n_corrections"]["mean"] + 0.2
    )
    # correcoes de volta => overshoot detectado
    assert (
        corr["bouts_summary"]["overshoot_ratio"]["mean"]
        > clean["bouts_summary"]["overshoot_ratio"]["mean"]
    )


def test_tremor_detected_only_when_injected(clean_session, tremor_session):
    _, clean = clean_session
    _, trem = tremor_session
    assert "tremor" in clean and "tremor" in trem
    # quantizacao em counts inteiros espalha potencia; o que valida a metrica
    # e a DISCRIMINACAO entre condicoes + pico na frequencia injetada.
    assert trem["tremor"]["band_power_ratio"] > 0.35
    assert trem["tremor"]["band_power_ratio"] > 2 * clean["tremor"]["band_power_ratio"]
    assert 8.0 <= trem["tremor"]["peak_freq_hz"] <= 12.0  # injetamos 10 Hz


def test_smoothness_degrades_with_corrections(clean_session, corrected_session):
    _, clean = clean_session
    _, corr = corrected_session
    # SPARC mais negativo = menos suave
    assert corr["bouts_summary"]["sparc"]["median"] < clean["bouts_summary"]["sparc"]["median"]


def test_kill_alignment(clean_session):
    _, m = clean_session
    ka = m.get("kill_analysis")
    assert ka is not None, "alinhamento kill<->clique falhou na sessao sintetica"
    assert ka["n_matched"] >= 30
    # offset injetado e ~0 (CSV gerado do mesmo relogio)
    assert abs(ka["clock_offset_s"]) < 0.1
    assert ka["acquisition_ms"]["median"] > 50


def test_amplitude_bands_and_halves(clean_session):
    _, m = clean_session
    assert "bouts_by_amplitude" in m, "faixas de amplitude ausentes do metrics.json"
    # sintético gera amplitudes 5-40°: small e/ou medium devem existir
    assert any(k in m["bouts_by_amplitude"] for k in ("small", "medium"))
    band = next(iter(m["bouts_by_amplitude"].values()))
    assert "sparc" in band and "median" in band["sparc"]
    assert "halves" in m and "first" in m["halves"]


def test_insights_generated(clean_session, corrected_session, tremor_session):
    _, clean = clean_session
    _, corr = corrected_session
    _, trem = tremor_session
    for m in (clean, corr, trem):
        assert isinstance(m.get("insights"), list) and len(m["insights"]) > 0
    # tremor injetado forte deve disparar a observacao de tremor
    trem_titles = " ".join(i["title"].lower() for i in trem["insights"])
    assert "tremor" in trem_titles
    # sessao limpa nao deve acusar "atencao"
    assert not any(i["severity"] == "atencao" for i in clean["insights"])
    # decisao do grill: observacao FACTUAL — nunca prescreve (sem drill, sem sens)
    for m in (clean, corr, trem):
        for i in m["insights"]:
            assert "drill" not in i
            assert "sens" not in i["body"].lower() or "sens diferente" in i["body"].lower()


def test_kovaaks_parser(clean_session):
    d, _ = clean_session
    csv = next(d.glob("*.csv"))
    kv = parse_stats_csv(csv)
    assert kv.scenario == "synthetic scenario"
    assert kv.sens_scale == "Valorant"
    assert kv.horiz_sens == 0.4
    assert kv.score == 100.0
    assert len(kv.kills) == 40
    assert kv.kills[0].time is not None
    assert kv.kills[0].ttk_s == 0.5
