"""Regua do lab = regua do coach: valores conhecidos das sheets tem que bater
com os mesmos casos do coach_test.clj (thresholds-seed-carrega-e-pontua)."""

from pathlib import Path

import pytest

from aimscope_lab.ruler import (
    energy_of, level_0_100, level_of, load_thresholds, normalize_name, rank_bucket,
)

CATALOG = Path(__file__).resolve().parents[2] / "coach" / "catalog"


@pytest.fixture(scope="module")
def thresholds():
    return load_thresholds(CATALOG)


def test_level_of_interpola_e_clampa():
    pts = [[100, 500], [200, 600], [300, 700], [400, 800]]
    assert level_of(pts, 250) == pytest.approx(50.0)    # abaixo do 1o: 0->100
    assert level_of(pts, 500) == pytest.approx(100.0)
    assert level_of(pts, 550) == pytest.approx(150.0)
    assert level_of(pts, 9999) == pytest.approx(400.0)  # clampa
    assert level_of([], 500) is None


def test_regua_bate_com_coach(thresholds):
    pasu = thresholds[normalize_name("VT Pasu Rasp Novice")]
    assert pasu["scale"] == "energy"
    assert energy_of(pasu, 850) == pytest.approx(400.0)          # gold novice
    assert level_0_100(energy_of(pasu, 850)) == pytest.approx(400.0 / 12.0)

    ss = thresholds[normalize_name("Smoothsphere Viscose Easier")]
    assert ss["scale"] == "percentile"
    assert energy_of(ss, 13600) == pytest.approx(62.5 * 12.0)    # percentil x12

    assert normalize_name("  vt   pasu rasp novice ") in thresholds
    assert normalize_name("cenario inexistente xyz") not in thresholds


def test_rank_buckets():
    assert rank_bucket(100) == "iron"
    assert rank_bucket(199) == "iron"
    assert rank_bucket(400) == "gold"
    assert rank_bucket(1200) == "celestial"
    assert rank_bucket(50) == "iron"      # abaixo da escada: clampa
    assert rank_bucket(1500) == "celestial"
