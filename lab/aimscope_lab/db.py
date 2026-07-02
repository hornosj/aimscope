"""population.db — observacoes por VOD (design-vod-lab.md §8).

Append-only, mesmo espirito do ADR 0001: este modulo so INSERE e LE; nao ha
update/delete. Vive na maquina do lab, NUNCA no repo. Ancoras sao agregacao
sobre estas linhas — re-bucketizar nao exige reprocessar video.
Traco angular cru nao e guardado (grande demais): so metricas agregadas.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS observation (
  id                   INTEGER PRIMARY KEY AUTOINCREMENT,
  video_id             TEXT NOT NULL,
  source_url           TEXT,
  scenario_name        TEXT NOT NULL,
  ocr_score            REAL NOT NULL,
  energy               REAL NOT NULL,   -- escala VT 0-1200 (ruler.energy_of)
  rank_bucket          TEXT NOT NULL,   -- iron..celestial
  metric_key           TEXT NOT NULL,   -- ex.: overshoot, corrections, sparc
  metric_value         REAL NOT NULL,
  estimator_confidence REAL NOT NULL,
  tier                 INTEGER NOT NULL,
  processed_at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS obs_metric ON observation (metric_key, rank_bucket);
"""


@dataclass
class Observation:
    video_id: str
    source_url: str | None
    scenario_name: str
    ocr_score: float
    energy: float
    rank_bucket: str
    metric_key: str
    metric_value: float
    estimator_confidence: float
    tier: int


def connect(path: str | Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.executescript(SCHEMA)
    return conn


def insert(conn: sqlite3.Connection, obs: list[Observation]) -> None:
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    conn.executemany(
        "INSERT INTO observation (video_id, source_url, scenario_name, ocr_score,"
        " energy, rank_bucket, metric_key, metric_value, estimator_confidence,"
        " tier, processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        [
            (o.video_id, o.source_url, o.scenario_name, o.ocr_score, o.energy,
             o.rank_bucket, o.metric_key, o.metric_value, o.estimator_confidence,
             o.tier, now)
            for o in obs
        ],
    )
    conn.commit()


def already_processed(conn: sqlite3.Connection, video_id: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM observation WHERE video_id = ? LIMIT 1", (video_id,)
    ).fetchone()
    return row is not None


def all_observations(conn: sqlite3.Connection) -> list[Observation]:
    rows = conn.execute(
        "SELECT video_id, source_url, scenario_name, ocr_score, energy,"
        " rank_bucket, metric_key, metric_value, estimator_confidence, tier"
        " FROM observation"
    ).fetchall()
    return [Observation(*r) for r in rows]


def summary(conn: sqlite3.Connection) -> dict:
    n_videos = conn.execute("SELECT COUNT(DISTINCT video_id) FROM observation").fetchone()[0]
    n_obs = conn.execute("SELECT COUNT(*) FROM observation").fetchone()[0]
    return {"n_videos": n_videos, "n_observations": n_obs}
