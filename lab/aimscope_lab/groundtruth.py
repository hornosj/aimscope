"""Gate de ground-truth (design §2.1; ADR 0003 decisao 3).

A ancora e calibrada com metrica de VIDEO mas aplicada a metrica de SENSOR.
Este modulo estabelece, por metrica, que as duas extracoes dao o MESMO numero
para a MESMA run — nas runs do proprio usuario, onde mouse.parquet e tela
foram gravados juntos ("sinal injetado" da cultura de teste do projeto).

Saida: relatorio JSON por metrica com vies, tolerancia, passou?, e correcao
linear metric_video -> metric_sensor quando o vies e sistematico. O calibrate
RECUSA emitir ancora de metrica que nao passou por aqui.

Tambem mede o erro do estimador por faixa de velocidade e declara a faixa em
que ele e confiavel (§5: nao esconder onde falha).
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import numpy as np

from aimscope_sidecar.kinematics import resample
from aimscope_sidecar.segments import movement_bouts as sensor_bouts
from aimscope_sidecar.session_io import load_session

from . import camera, tier1, video
from .bouts import bouts_for
from .trace import px_per_deg_of, to_kinematics

VIDEO_EXTS = (".mp4", ".mkv", ".webm", ".avi")

# Tolerancia de vies |mediana(video - sensor)| por metrica.
# kind: "abs" compara direto; "rel" compara relativo ao valor do sensor.
TOLERANCES: dict[str, tuple[str, float]] = {
    "overshoot":     ("rel", 0.05),
    "corrections":   ("abs", 0.5),
    "sparc":         ("abs", 0.30),
    "endurance-eff": ("rel", 0.05),
}

MIN_RUNS = 3          # abaixo disso nenhuma metrica passa (amostra ridicula)
MIN_RUNS_FIT = 5      # correcao linear so com pontos suficientes

# Faixas de velocidade (deg/s, medidas pelo SENSOR) p/ erro do estimador.
SPEED_BANDS = [(0.0, 25.0), (25.0, 100.0), (100.0, 300.0), (300.0, float("inf"))]
BAND_REL_ERR_OK = 0.25


def find_video(session_dir: Path) -> Path | None:
    for ext in VIDEO_EXTS:
        hits = sorted(session_dir.glob(f"*{ext}"))
        if hits:
            return hits[0]
    return None


def fov_from_csv(session_dir: Path) -> float | None:
    """FOV:,103 no rodape do Stats.csv do KovaaK's."""
    for csv in sorted(session_dir.glob("*.csv")):
        m = re.search(r"^FOV:,\s*([\d.]+)", csv.read_text(encoding="utf-8", errors="replace"),
                      re.MULTILINE)
        if m:
            return float(m.group(1))
    return None


def sensor_metrics(session_dir: Path) -> dict[str, float]:
    """Pipeline do sensor ate as MESMAS chaves Tier 1 (mesma matematica)."""
    ses = load_session(session_dir)
    kin = resample(ses.mouse.t, ses.mouse.dx, ses.mouse.dy, ses.deg_per_count)
    return tier1.compute(kin, sensor_bouts(kin))


def video_metrics(video_path: Path) -> tuple[dict[str, float], "camera.EgoMotion", video.Roi]:
    """Pipeline de VOD (modo YouTube: px + limiares nominais) na gravacao local."""
    samples = video.sample_grays(video_path)
    roi = video.detect_game_roi_from(samples)
    overlay = video.static_overlay_mask_from(samples, roi)
    ego = camera.estimate(((t, roi.crop(f)) for t, f in video.frames(video_path)),
                          extra_mask=overlay)
    kin = to_kinematics(ego)
    metrics = tier1.compute(kin, bouts_for(kin, roi_width_px=roi.width))
    return metrics, ego, roi


def estimator_speed_error(session_dir: Path, ego: "camera.EgoMotion",
                          roi: video.Roi, fov_deg: float) -> list[dict]:
    """Erro relativo do estimador por faixa de velocidade do sensor (deg/s).
    Exige FOV/sens da run propria pra por os dois tracos em graus."""
    ses = load_session(session_dir)
    if ses.deg_per_count is None:
        return []
    kin = resample(ses.mouse.t, ses.mouse.dx, ses.mouse.dy, ses.deg_per_count)
    kin_v = to_kinematics(ego, px_per_deg=px_per_deg_of(roi.width, fov_deg))

    # velocidade do sensor amostrada na grade do video (mesmo relogio: a
    # gravacao de tela e da mesma maquina/sessao)
    v_sensor = np.interp(kin_v.t, kin.t, kin.speed)
    v_video = kin_v.speed

    out = []
    for lo, hi in SPEED_BANDS:
        sel = (v_sensor >= lo) & (v_sensor < hi)
        if sel.sum() < 30:
            continue
        rel = np.abs(v_video[sel] - v_sensor[sel]) / np.maximum(v_sensor[sel], 5.0)
        med = float(np.median(rel))
        out.append({
            "band_deg_s": [lo, None if np.isinf(hi) else hi],
            "n": int(sel.sum()),
            "median_rel_err": med,
            "reliable": med <= BAND_REL_ERR_OK,
        })
    return out


def validate(session_dirs: list[Path], fov_override: float | None = None) -> dict:
    """Roda o gate em N runs proprias -> relatorio (dict pronto pra JSON)."""
    pairs: dict[str, list[tuple[float, float]]] = {k: [] for k in TOLERANCES}
    speed_bands: list[list[dict]] = []
    runs = []

    for d in session_dirs:
        d = Path(d)
        vid = find_video(d)
        if vid is None:
            runs.append({"session": d.name, "skipped": "sem video na sessao"})
            continue
        ms = sensor_metrics(d)
        mv, ego, roi = video_metrics(vid)
        fov = fov_override or fov_from_csv(d)
        if fov:
            bands = estimator_speed_error(d, ego, roi, fov)
            if bands:
                speed_bands.append(bands)
        common = sorted(set(ms) & set(mv) & set(TOLERANCES))
        for k in common:
            pairs[k].append((mv[k], ms[k]))
        runs.append({
            "session": d.name, "video": vid.name,
            "estimator_confidence": ego.confidence,
            "metrics_sensor": ms, "metrics_video": mv,
        })

    metrics_report: dict[str, dict] = {}
    for k, (kind, tol) in TOLERANCES.items():
        pts = pairs[k]
        if len(pts) < MIN_RUNS:
            metrics_report[k] = {"n_runs": len(pts), "passed": False,
                                 "reason": f"menos de {MIN_RUNS} runs com a metrica"}
            continue
        v = np.array([p[0] for p in pts])
        s = np.array([p[1] for p in pts])
        diff = v - s
        bias = float(np.median(diff))
        bias_metric = (abs(bias) / max(float(np.median(np.abs(s))), 1e-9)
                       if kind == "rel" else abs(bias))
        passed = bias_metric <= tol
        correction = None
        if not passed and len(pts) >= MIN_RUNS_FIT:
            # correcao linear metric_video -> metric_sensor (design §2.1)
            a, b = np.polyfit(v, s, 1)
            resid = s - (a * v + b)
            resid_metric = (float(np.median(np.abs(resid))) /
                            max(float(np.median(np.abs(s))), 1e-9)
                            if kind == "rel" else float(np.median(np.abs(resid))))
            if resid_metric <= tol:
                correction = {"a": float(a), "b": float(b)}
                passed = True
        metrics_report[k] = {
            "n_runs": len(pts), "bias": bias, "kind": kind, "tolerance": tol,
            "passed": passed, "correction": correction,
            "pairs_video_sensor": [[float(a), float(b)] for a, b in pts],
        }

    # consolida faixas de velocidade entre runs (pior caso por faixa)
    bands_out = []
    if speed_bands:
        by_band: dict[tuple, list[dict]] = {}
        for run_bands in speed_bands:
            for b in run_bands:
                by_band.setdefault(tuple(b["band_deg_s"]), []).append(b)
        for band, bs in sorted(by_band.items(), key=lambda kv: (kv[0][0],)):
            worst = max(b["median_rel_err"] for b in bs)
            bands_out.append({
                "band_deg_s": list(band), "n_runs": len(bs),
                "worst_median_rel_err": worst,
                "reliable": worst <= BAND_REL_ERR_OK,
            })

    return {"runs": runs, "metrics": metrics_report, "estimator_speed_bands": bands_out}


def write_report(report: dict, out_path: Path) -> None:
    out_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


def passed_metrics(report: dict) -> dict[str, dict | None]:
    """{metric_key: correction|None} SO das metricas que passaram no gate."""
    return {k: r.get("correction")
            for k, r in report.get("metrics", {}).items() if r.get("passed")}
