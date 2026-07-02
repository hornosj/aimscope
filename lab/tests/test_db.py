"""population.db: append-only, round-trip, dedupe por video_id."""

from aimscope_lab import db


def _obs(video_id="v1", metric_key="overshoot", metric_value=1.1):
    return db.Observation(
        video_id=video_id, source_url="https://youtu.be/x", scenario_name="pasu voltaic",
        ocr_score=850.0, energy=400.0, rank_bucket="gold",
        metric_key=metric_key, metric_value=metric_value,
        estimator_confidence=0.8, tier=1,
    )


def test_roundtrip(tmp_path):
    conn = db.connect(tmp_path / "population.db")
    db.insert(conn, [_obs(), _obs(metric_key="sparc", metric_value=-1.8)])
    rows = db.all_observations(conn)
    assert len(rows) == 2
    assert {r.metric_key for r in rows} == {"overshoot", "sparc"}
    assert rows[0].rank_bucket == "gold"
    assert db.summary(conn) == {"n_videos": 1, "n_observations": 2}


def test_already_processed(tmp_path):
    conn = db.connect(tmp_path / "population.db")
    assert not db.already_processed(conn, "v1")
    db.insert(conn, [_obs()])
    assert db.already_processed(conn, "v1")
    assert not db.already_processed(conn, "v2")
