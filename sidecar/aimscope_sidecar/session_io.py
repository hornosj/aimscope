"""Leitura do formato de sessao (contrato com o recorder Rust)."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

# Flags de botao do RAWMOUSE (usButtonFlags)
LEFT_DOWN = 0x0001
LEFT_UP = 0x0002
RIGHT_DOWN = 0x0004
WHEEL = 0x0400


@dataclass
class MouseTrace:
    t: np.ndarray            # segundos desde o inicio da sessao (float64)
    dx: np.ndarray           # counts (int32)
    dy: np.ndarray
    button_flags: np.ndarray  # uint16
    button_data: np.ndarray   # int16 (delta de wheel quando flag WHEEL)

    @property
    def left_click_times(self) -> np.ndarray:
        return self.t[(self.button_flags & LEFT_DOWN) != 0]


@dataclass
class Session:
    dir: Path
    manifest: dict
    mouse: MouseTrace
    csv_paths: list[Path] = field(default_factory=list)

    @property
    def wall_start(self) -> datetime:
        """Relogio de parede (local, naive) no inicio da sessao."""
        return datetime.fromtimestamp(self.manifest["unix_ns_at_start"] / 1e9)

    @property
    def deg_per_count(self) -> float | None:
        """Graus por count, se sens+escala conhecidos. None -> trabalhar em counts."""
        sens = self.manifest.get("sens")
        scale = (self.manifest.get("sens_scale") or "").lower()
        if sens is None:
            return None
        base = {
            "valorant": 0.07,
            "cs": 0.022, "csgo": 0.022, "cs2": 0.022,
            "quakecs": 0.022, "quake": 0.022, "apex": 0.022, "source": 0.022,
            "overwatch": 0.0066, "ow": 0.0066,
        }.get(scale)
        return base * sens if base is not None else None


def load_session(session_dir: str | Path) -> Session:
    d = Path(session_dir)
    manifest = json.loads((d / "manifest.json").read_text(encoding="utf-8"))

    table = pq.read_table(d / "mouse.parquet")
    ts_qpc = table["ts_qpc"].to_numpy()
    qpc0 = manifest["qpc_at_start"]
    freq = manifest["qpc_frequency"]
    t = (ts_qpc - qpc0) / freq

    mouse = MouseTrace(
        t=t.astype(np.float64),
        dx=table["dx"].to_numpy(),
        dy=table["dy"].to_numpy(),
        button_flags=table["button_flags"].to_numpy(),
        button_data=table["button_data"].to_numpy(),
    )
    csvs = sorted(d.glob("*.csv"))
    return Session(dir=d, manifest=manifest, mouse=mouse, csv_paths=csvs)
