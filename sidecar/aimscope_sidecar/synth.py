"""Gerador de sessao sintetica.

Produz um diretorio de sessao valido (manifest + mouse.parquet + CSV KovaaK's
opcional) com movimentos de jerk minimo, tremor senoidal injetado e correcoes
deliberadas — serve de "vetor dourado" para os testes: sabemos o que foi
injetado, entao sabemos o que as metricas DEVEM medir.

Uso: python -m aimscope_sidecar.synth <dir_destino> [--tremor] [--seed N]
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

FS = 1000.0
QPC_FREQ = 10_000_000  # 10 MHz, tipico no Windows
DEG_PER_COUNT = 0.07 * 0.4  # valorant scale, sens 0.4


def min_jerk(amplitude: float, duration_s: float, fs: float = FS) -> np.ndarray:
    """Posicao 1D de jerk minimo, 0 -> amplitude."""
    n = int(duration_s * fs)
    tau = np.linspace(0, 1, n)
    return amplitude * (10 * tau**3 - 15 * tau**4 + 6 * tau**5)


def build_trace(
    n_moves: int = 40,
    tremor_amp_deg: float = 0.0,
    corrections: bool = True,
    seed: int = 42,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, list[float]]:
    """Retorna (t, dx, dy, button_flags, click_times_s) em counts/segundos."""
    rng = np.random.default_rng(seed)
    pos_x: list[np.ndarray] = []
    pos_y: list[np.ndarray] = []
    click_t: list[float] = []
    t_cursor = 0.0

    def hold(dur):
        nonlocal t_cursor
        n = int(dur * FS)
        base_x = pos_x[-1][-1] if pos_x else 0.0
        base_y = pos_y[-1][-1] if pos_y else 0.0
        x = np.full(n, base_x)
        y = np.full(n, base_y)
        if tremor_amp_deg > 0:
            tt = np.arange(n) / FS
            x = x + tremor_amp_deg * np.sin(2 * np.pi * 10.0 * tt + rng.uniform(0, 6))
            y = y + 0.6 * tremor_amp_deg * np.sin(2 * np.pi * 9.5 * tt + rng.uniform(0, 6))
        pos_x.append(x)
        pos_y.append(y)
        t_cursor += dur

    hold(1.0)
    for _ in range(n_moves):
        amp = rng.uniform(5.0, 40.0)  # graus
        ang = rng.uniform(0, 2 * np.pi)
        dur = 0.12 + 0.004 * amp + rng.uniform(0, 0.05)
        mx = min_jerk(amp * np.cos(ang), dur)
        my = min_jerk(amp * np.sin(ang), dur)
        base_x = pos_x[-1][-1]
        base_y = pos_y[-1][-1]
        pos_x.append(base_x + mx)
        pos_y.append(base_y + my)
        t_cursor += dur

        if corrections and rng.random() < 0.6:
            # correcao: pequeno submovimento de volta (overshoot deliberado)
            camp = amp * rng.uniform(0.05, 0.15)
            cdur = 0.08 + rng.uniform(0, 0.04)
            cx = min_jerk(-camp * np.cos(ang), cdur)
            cy = min_jerk(-camp * np.sin(ang), cdur)
            pos_x.append(pos_x[-1][-1] + cx)
            pos_y.append(pos_y[-1][-1] + cy)
            t_cursor += cdur

        click_t.append(t_cursor + 0.03)
        hold(rng.uniform(0.4, 1.0))

    x = np.concatenate(pos_x) / DEG_PER_COUNT  # graus -> counts
    y = np.concatenate(pos_y) / DEG_PER_COUNT
    n = len(x)
    t = np.arange(n) / FS
    # jitter de entrega tipo Raw Input (nao acumulativo)
    t_jit = t + np.clip(rng.normal(0, 0.0002, n), -0.0004, 0.0004)
    t_jit = np.maximum.accumulate(t_jit)

    dx = np.diff(x, prepend=x[0])
    dy = np.diff(y, prepend=y[0])  # tela: y cresce para baixo; sinal nao importa p/ speed

    # quantiza em counts inteiros preservando o acumulado (como um mouse real)
    qx = np.round(np.cumsum(dx))
    qy = np.round(np.cumsum(dy))
    dxi = np.diff(qx, prepend=0.0).astype(np.int32)
    dyi = np.diff(qy, prepend=0.0).astype(np.int32)

    flags = np.zeros(n, dtype=np.uint16)
    for ct in click_t:
        i = min(n - 1, int(ct * FS))
        flags[i] |= 0x0001  # LEFT_DOWN
        if i + 60 < n:
            flags[i + 60] |= 0x0002  # LEFT_UP

    return t_jit, dxi, dyi, flags, click_t


def write_session(
    out_dir: Path,
    tremor_amp_deg: float = 0.0,
    corrections: bool = True,
    with_csv: bool = True,
    n_moves: int = 40,
    seed: int = 42,
) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    t, dx, dy, flags, click_t = build_trace(
        n_moves=n_moves, tremor_amp_deg=tremor_amp_deg,
        corrections=corrections, seed=seed,
    )

    start = datetime(2026, 7, 1, 14, 0, 0)
    manifest = {
        "schema_version": 1,
        "app_version": "synth",
        "game": "kovaaks",
        "created_local": start.isoformat(),
        "unix_ns_at_start": int(start.timestamp() * 1e9),
        "qpc_at_start": 5_000_000_000,
        "qpc_frequency": QPC_FREQ,
        "dpi": 800.0,
        "sens": 0.4,
        "sens_scale": "valorant",
        "cm_per_360": 360.0 / (0.07 * 0.4) / 800.0 * 2.54,
        "notes": "sessao sintetica",
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))

    ts_qpc = (manifest["qpc_at_start"] + t * QPC_FREQ).astype(np.int64)
    table = pa.table({
        "ts_qpc": ts_qpc,
        "dx": dx.astype(np.int32),
        "dy": dy.astype(np.int32),
        "button_flags": flags.astype(np.uint16),
        "button_data": np.zeros(len(t), dtype=np.int16),
        "device_flags": np.zeros(len(t), dtype=np.uint16),
    })
    pq.write_table(table, out_dir / "mouse.parquet")

    if with_csv:
        lines = ["Kill #,Timestamp,Bot,Weapon,TTK,Shots,Hits,Accuracy"]
        for i, ct in enumerate(click_t, start=1):
            wall = start + timedelta(seconds=ct)
            stamp = wall.strftime("%H:%M:%S.%f")[:-3]
            lines.append(f"{i},{stamp},bot,synth_gun,0.500s,2,1,0.5")
        lines += [
            "", "Weapon,Shots,Hits,Damage Done,Damage Possible",
            f"synth_gun,{2*len(click_t)},{len(click_t)},100,200", "",
            f"Kills:,{len(click_t)}",
            "Score:,100.0",
            "Scenario:,synthetic scenario",
            "Sens Scale:,Valorant",
            "Horiz Sens:,0.4",
            "FOV:,103",
        ]
        name = f"synthetic scenario - Challenge - {start.strftime('%Y.%m.%d-%H.%M.%S')} Stats.csv"
        (out_dir / name).write_text("\n".join(lines), encoding="utf-8")

    return out_dir


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir", type=Path)
    ap.add_argument("--tremor", type=float, default=0.0,
                    help="amplitude do tremor injetado em graus (ex.: 0.05)")
    ap.add_argument("--no-corrections", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    d = write_session(args.out_dir, tremor_amp_deg=args.tremor,
                      corrections=not args.no_corrections, seed=args.seed)
    print(f"sessao sintetica em {d}")


if __name__ == "__main__":
    main()
