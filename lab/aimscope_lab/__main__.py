"""CLI do lab: python -m aimscope_lab <fetch|process|validate|calibrate>.

Fluxo (design-vod-lab.md):
  1. validate  — gate de ground-truth nas runs PROPRIAS (video + mouse.parquet)
  2. fetch     — baixa VOD publico pro cache (yt-dlp, rate polido)
  3. process   — VOD -> estimador -> Tier 1 -> observacoes no population.db
  4. calibrate — population.db + relatorio do gate -> coach/catalog/anchors.edn
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CATALOG = REPO_ROOT / "coach" / "catalog"
DEFAULT_ANCHORS_OUT = DEFAULT_CATALOG / "anchors.edn"


def cmd_fetch(args) -> int:
    from .vods import fetch

    path = fetch(args.url, args.cache)
    print(f"vod em cache: {path}")
    return 0


def cmd_process(args) -> int:
    import numpy as np  # noqa: F401  (garante deps de video presentes)

    from . import camera, db, ocr, tier1, video
    from .bouts import bouts_for
    from .ruler import energy_of, load_thresholds, normalize_name, rank_bucket
    from .trace import to_kinematics

    video_path = Path(args.video)
    video_id = video_path.stem

    conn = db.connect(args.db)
    if db.already_processed(conn, video_id) and not args.force:
        print(f"{video_id} ja processado (append-only; use --force pra reprocessar "
              f"ciente de que duplica observacoes)", file=sys.stderr)
        return 1

    thresholds = load_thresholds(args.catalog)
    spec = thresholds.get(normalize_name(args.scenario))
    if spec is None:
        print(f"cenario sem regua semeada no catalogo: {args.scenario!r} — "
              f"sem regua nao ha energia, sem energia nao ha ground-truth de rank",
              file=sys.stderr)
        return 2

    score = args.score
    if score is None:
        score, n_agree = ocr.score_from_video(video_path)
        if score is None:
            print("OCR do score inconclusivo; passe --score com o valor lido NA TELA",
                  file=sys.stderr)
            return 2
        print(f"OCR: score {score} ({n_agree} frames concordando)")

    energy = energy_of(spec, score)
    bucket = rank_bucket(energy)

    # prepasses (ROI + overlay) numa UNICA passada sequencial: seek aleatorio
    # em VP9/webm decodifica desde o keyframe e custava dezenas de minutos
    samples = video.sample_grays(video_path)
    roi = video.detect_game_roi_from(samples)
    overlay = video.static_overlay_mask_from(samples, roi)
    ego = camera.estimate(((t, roi.crop(f)) for t, f in video.frames(video_path)),
                          extra_mask=overlay)
    kin = to_kinematics(ego)
    # sanidade: p95 de velocidade abaixo de ~5 deg/s (escala nominal) e
    # fisicamente absurdo pra aim training — e o estimador travado (grid-lock
    # em cena lisa) MENTINDO com confianca alta, nao uma run parada. Recusar
    # e melhor que inserir observacao corrompida no population.db.
    import numpy as np
    from .trace import px_per_deg_of
    from .bouts import NOMINAL_HFOV_DEG
    p95_deg_s = float(np.percentile(kin.speed, 95)) / px_per_deg_of(roi.width, NOMINAL_HFOV_DEG)
    if p95_deg_s < 5.0:
        print(f"estimador nao recuperou movimento critico (p95 {p95_deg_s:.1f} deg/s "
              f"nominal): cena de baixa textura alem do alcance do estimador — "
              f"VOD descartado por honestidade, nada inserido", file=sys.stderr)
        return 2

    bouts = bouts_for(kin, roi_width_px=roi.width)
    metrics = tier1.compute(kin, bouts)
    if not metrics:
        print(f"run sem bouts suficientes pra virar observacao ({len(bouts)} bouts; "
              f"minimo {tier1.MIN_BOUTS}). Cenario de pursuit puro (Smoothbot etc.) "
              f"nao gera bout balistico por definicao — Tier 1 e bout-based; use "
              f"VODs de clicking/target-switching pras ancoras.", file=sys.stderr)
        return 2

    rows = [
        db.Observation(
            video_id=video_id, source_url=args.url, scenario_name=args.scenario,
            ocr_score=float(score), energy=float(energy), rank_bucket=bucket,
            metric_key=k, metric_value=float(v),
            estimator_confidence=ego.confidence, tier=1,
        )
        for k, v in sorted(metrics.items())
    ]
    # re-checagem NA HORA do insert: dois processos no mesmo video (orfao de um
    # batch morto, p.ex.) passavam ambos pela checagem do inicio e duplicavam
    if db.already_processed(conn, video_id) and not args.force:
        print(f"{video_id} inserido por outro processo durante o processamento — "
              f"descartando este resultado (sem duplicar)", file=sys.stderr)
        return 1
    db.insert(conn, rows)
    print(f"{video_id}: {len(rows)} observacoes ({bucket}, energia {energy:.0f}, "
          f"confianca do estimador {ego.confidence:.2f})")
    return 0


def cmd_validate(args) -> int:
    from .groundtruth import validate, write_report

    report = validate([Path(d) for d in args.sessions], fov_override=args.fov)
    write_report(report, Path(args.out))
    print(f"relatorio do gate: {args.out}")
    for k, r in report["metrics"].items():
        status = "PASSOU" if r.get("passed") else "REPROVOU"
        extra = " (com correcao linear)" if r.get("correction") else ""
        print(f"  {k}: {status}{extra} — {r}")
    for b in report["estimator_speed_bands"]:
        tag = "confiavel" if b["reliable"] else "NAO confiavel"
        print(f"  faixa {b['band_deg_s']} deg/s: {tag} "
              f"(err rel {b['worst_median_rel_err']:.2f})")
    return 0


def cmd_calibrate(args) -> int:
    from . import db
    from .calibrate import build, emit_edn
    from .groundtruth import passed_metrics

    report = json.loads(Path(args.validation).read_text(encoding="utf-8"))
    passed = passed_metrics(report)
    if not passed:
        print("nenhuma metrica passou no gate de ground-truth; nada a emitir "
              "(design §2.1: nenhuma ancora sem gate)", file=sys.stderr)
        return 2

    conn = db.connect(args.db)
    anchors, meta, excluded = build(db.all_observations(conn), passed)
    for msg in excluded:
        print(f"[fora] {msg}", file=sys.stderr)
    if not anchors:
        print("nenhuma ancora calibravel com os dados atuais", file=sys.stderr)
        return 2

    out = Path(args.out)
    out.write_text(emit_edn(anchors, meta, db.summary(conn)), encoding="utf-8")
    print(f"ancoras emitidas: {out} ({len(anchors)} metricas)")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="aimscope_lab")
    sub = ap.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fetch", help="baixa VOD publico pro cache local")
    f.add_argument("url")
    f.add_argument("--cache", default=str(REPO_ROOT / "lab" / "vod-cache"))
    f.set_defaults(fn=cmd_fetch)

    p = sub.add_parser("process", help="VOD -> observacoes Tier 1 no population.db")
    p.add_argument("video", help="arquivo local (use fetch antes p/ YouTube)")
    p.add_argument("--scenario", required=True, help="nome do cenario (regua do catalogo)")
    p.add_argument("--score", type=float, default=None,
                   help="score lido NA TELA (quando o OCR nao fecha)")
    p.add_argument("--url", default=None, help="source_url pra procedencia")
    p.add_argument("--db", default=str(REPO_ROOT / "lab" / "population.db"))
    p.add_argument("--catalog", default=str(DEFAULT_CATALOG))
    p.add_argument("--force", action="store_true")
    p.set_defaults(fn=cmd_process)

    v = sub.add_parser("validate", help="gate de ground-truth nas runs proprias")
    v.add_argument("sessions", nargs="+", help="diretorios de sessao com video gravado")
    v.add_argument("--out", default=str(REPO_ROOT / "lab" / "validation.json"))
    v.add_argument("--fov", type=float, default=None, help="FOV horizontal se nao estiver no CSV")
    v.set_defaults(fn=cmd_validate)

    c = sub.add_parser("calibrate", help="population.db -> anchors.edn versionado")
    c.add_argument("--db", default=str(REPO_ROOT / "lab" / "population.db"))
    c.add_argument("--validation", required=True,
                   help="relatorio do validate; sem ele nao ha emissao")
    c.add_argument("--out", default=str(DEFAULT_ANCHORS_OUT))
    c.set_defaults(fn=cmd_calibrate)

    args = ap.parse_args(argv)
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
