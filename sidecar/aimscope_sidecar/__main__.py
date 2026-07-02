"""Ponto de entrada do sidecar: python -m aimscope_sidecar <session_dir>."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

from . import __version__
from .insights import generate as generate_insights
from .kinematics import resample
from .kovaaks import parse_stats_csv
from .metrics import (
    align_kills_to_clicks,
    bout_features,
    kill_windows,
    robust_summary,
    tremor as tremor_metric,
)
from .reaction import (
    compute as compute_reaction,
    load_screen,
    movement_onsets,
    visual_events,
)
from .report import write_report
from .targeting import choice as compute_choice, load_centroids, pursuit as compute_pursuit
from .segments import movement_bouts, quiet_windows
from .session_io import load_session


def analyze(session_dir: Path, html: bool = True) -> dict:
    ses = load_session(session_dir)
    warnings: list[str] = []

    dpc = ses.deg_per_count
    if dpc is None:
        warnings.append(
            "Sem DPI/sens no manifest: métricas em counts, não em graus. "
            "Grave com --dpi e --sens para unidades físicas."
        )

    metrics: dict = {
        "sidecar_version": __version__,
        "session": ses.dir.name,
        "unit": "deg" if dpc is not None else "counts",
        "warnings": warnings,
    }

    # ---------------- KovaaK's CSV (se presente) ----------------
    kv = None
    if ses.csv_paths:
        kv = parse_stats_csv(ses.csv_paths[-1])
        metrics["kovaaks"] = {
            "file": kv.path.name,
            "scenario": kv.scenario,
            "score": kv.score,
            "accuracy": kv.overall_accuracy,
            "n_kills": len(kv.kills),
            "sens_scale": kv.sens_scale,
            "horiz_sens": kv.horiz_sens,
        }
        if len(ses.csv_paths) > 1:
            warnings.append(
                f"{len(ses.csv_paths)} CSVs na sessão; analisando o mais recente "
                f"({kv.path.name}). Análise multi-cenário vem depois."
            )
    else:
        warnings.append("Nenhum CSV do KovaaK's na sessão: sem ground truth de kills.")

    # ---------------- cinemática ----------------
    try:
        kin = resample(ses.mouse.t, ses.mouse.dx, ses.mouse.dy, dpc)
    except ValueError as e:
        warnings.append(f"Trace inaproveitável: {e}")
        write_report(ses.dir, metrics, None, [], [], None, np.array([]), html=False)
        return metrics

    bouts_raw = movement_bouts(kin)
    quiet = quiet_windows(kin, bouts_raw)
    bouts = [bout_features(kin, b) for b in bouts_raw]
    clicks = ses.mouse.left_click_times

    metrics["n_bouts"] = len(bouts)
    metrics["n_clicks"] = int(len(clicks))
    metrics["duration_s"] = kin.duration_s

    # Faixas de amplitude = proxy de grupo muscular (CONTEXT.md):
    # micro <2°, small 2-10°, medium 10-40°, large >40° (em counts: escala x50)
    amp_scale = 1.0 if kin.unit == "deg" else 50.0
    bands = {"micro": (0, 2 * amp_scale), "small": (2 * amp_scale, 10 * amp_scale),
             "medium": (10 * amp_scale, 40 * amp_scale), "large": (40 * amp_scale, 1e18)}
    by_amp = {}
    for name, (lo, hi) in bands.items():
        sel = [b for b in bouts if lo <= b.amplitude < hi]
        if len(sel) >= 5:
            by_amp[name] = {
                "n": len(sel),
                "sparc": robust_summary([b.sparc for b in sel]),
                "efficiency": robust_summary([b.efficiency for b in sel]),
                "n_corrections": robust_summary([float(b.n_corrections) for b in sel]),
                "overshoot_ratio": robust_summary([b.overshoot_ratio for b in sel]),
            }
    if by_amp:
        metrics["bouts_by_amplitude"] = by_amp

    # Metades da sessão -> consistency/endurance (degradação 1ª->2ª metade)
    if len(bouts) >= 10:
        t_mid = (bouts[0].t_start + bouts[-1].t_end) / 2.0
        first = [b for b in bouts if b.t_end <= t_mid]
        second = [b for b in bouts if b.t_start > t_mid]
        if len(first) >= 5 and len(second) >= 5:
            metrics["halves"] = {
                "first": {"efficiency": float(np.median([b.efficiency for b in first])),
                          "sparc": float(np.nanmedian([b.sparc for b in first]))},
                "second": {"efficiency": float(np.median([b.efficiency for b in second])),
                           "sparc": float(np.nanmedian([b.sparc for b in second]))},
            }

    if bouts:
        metrics["bouts_summary"] = {
            "amplitude": robust_summary([b.amplitude for b in bouts]),
            "duration_ms": robust_summary([b.duration_ms for b in bouts]),
            "efficiency": robust_summary([b.efficiency for b in bouts]),
            "peak_speed": robust_summary([b.peak_speed for b in bouts]),
            "n_corrections": robust_summary([float(b.n_corrections) for b in bouts]),
            "overshoot_ratio": robust_summary([b.overshoot_ratio for b in bouts]),
            "sparc": robust_summary([b.sparc for b in bouts]),
            "ldlj": robust_summary([b.ldlj for b in bouts]),
            "time_peak_to_end_ms": robust_summary([b.time_peak_to_end_ms for b in bouts]),
        }
    else:
        warnings.append("Nenhum movimento detectado (sessão parada?).")

    # ---------------- reaction time visual (screen.parquet, v0.3) ----------
    scr = load_screen(ses.dir, ses.manifest)
    if scr is not None:
        r = compute_reaction(scr, kin.t, kin.speed, kin.fs)
        if r is not None:
            metrics["reaction"] = r
        else:
            warnings.append(
                "Tela gravada, mas eventos visuais/onsets insuficientes p/ reaction time."
            )

        # ------------ direção do alvo: choice + pursuit (v0.4) -------------
        cen = load_centroids(ses.dir, ses.manifest)
        if cen is not None:
            events = visual_events(scr)
            onsets = movement_onsets(kin.t, kin.speed, kin.fs)
            ch = compute_choice(cen, events, onsets, kin.t, kin.vx, kin.vy, kin.fs)
            if ch is not None:
                metrics["reaction_choice"] = ch
            pu = compute_pursuit(cen, kin.t, kin.vx, kin.fs)
            if pu is not None:
                metrics["pursuit"] = pu

    tr = tremor_metric(kin, quiet)
    if tr is not None:
        metrics["tremor"] = {
            "band_power_ratio": tr.band_power_ratio,
            "band_rms": tr.band_rms,
            "peak_freq_hz": tr.peak_freq_hz,
            "total_quiet_s": tr.total_quiet_s,
        }
    else:
        warnings.append("Sem janelas quietas suficientes para medir tremor.")

    # ---------------- alinhamento kill <-> clique ----------------
    if kv is not None and kv.kills and len(clicks):
        offset, matches = align_kills_to_clicks(
            [k.time for k in kv.kills], ses.wall_start, clicks
        )
        if offset is not None and matches:
            km = kill_windows(matches, bouts)
            with_bout = [k for k in km if k.bout is not None]
            metrics["kill_analysis"] = {
                "clock_offset_s": offset,
                "n_matched": len(matches),
                "n_with_acquisition_bout": len(with_bout),
                "acquisition_ms": robust_summary([k.acquisition_ms for k in with_bout]),
                "acq_efficiency": robust_summary([k.bout.efficiency for k in with_bout]),
                "acq_corrections": robust_summary(
                    [float(k.bout.n_corrections) for k in with_bout]
                ),
            }
        else:
            warnings.append(
                "Não foi possível alinhar kills do CSV aos cliques "
                "(poucos matches ou resíduo alto)."
            )

    metrics["insights"] = generate_insights(metrics)

    write_report(ses.dir, metrics, kin, bouts_raw, bouts, tr, clicks, html=html)
    return metrics


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="aimscope_sidecar")
    ap.add_argument("session_dir", type=Path)
    ap.add_argument("--no-html", action="store_true")
    args = ap.parse_args(argv)

    if not (args.session_dir / "manifest.json").exists():
        print(f"erro: {args.session_dir} não tem manifest.json", file=sys.stderr)
        return 2

    m = analyze(args.session_dir, html=not args.no_html)

    print(f"análise concluída: {args.session_dir / 'metrics.json'}")
    if not args.no_html and (args.session_dir / "report.html").exists():
        print(f"relatório: {args.session_dir / 'report.html'}")
    for w in m.get("warnings", []):
        print(f"[aviso] {w}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
