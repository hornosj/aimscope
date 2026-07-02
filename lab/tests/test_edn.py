"""Leitor/escritor EDN minimo: round-trip e leitura do catalogo REAL."""

from pathlib import Path

from aimscope_lab.edn import K, dumps, loads

REPO = Path(__file__).resolve().parents[2]


def test_roundtrip_basico():
    doc = {
        K("anchors/source"): "population.db 2026-07-02 (3 vods)",
        K("anchors"): {K("sparc"): [[-3.5, 10.0], [-1.2, 92.0]]},
        K("meta"): {K("sparc"): {K("n"): 42, K("low-n"): False,
                                 K("buckets"): {K("gold"): {K("n"): 7}}}},
    }
    out = loads(dumps(doc))
    assert out == doc


def test_tipos_escalares():
    assert loads("nil") is None
    assert loads("true") is True
    assert loads("[1 2.5 -3]") == [1, 2.5, -3]
    assert loads('"a\\"b"') == 'a"b'
    assert loads("#{:a :b}") == {K("a"), K("b")}
    assert loads("; comentario\n42") == 42


def test_le_catalogo_real():
    text = (REPO / "coach" / "catalog" / "viscose-s2.edn").read_text(encoding="utf-8")
    data = loads(text)
    assert data[K("benchmark")] == K("viscose-s2")
    th = data[K("thresholds")]
    spec = th["Smoothsphere Viscose Easier"]
    assert spec[K("scale")] == K("percentile")
    assert [1, 5500] in spec[K("points")] or len(spec[K("points")]) >= 4
