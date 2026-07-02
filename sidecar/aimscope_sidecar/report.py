"""Geracao de metrics.json e report.html (graficos matplotlib embutidos em base64)."""

from __future__ import annotations

import base64
import io
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from .kinematics import Kinematics
from .metrics import BoutFeatures, KillMatch, TremorResult
from .segments import Bout


def _fig_to_b64(fig) -> str:
    buf = io.BytesIO()
    fig.savefig(buf, format="png", dpi=110, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def fig_overview(kin: Kinematics, bouts: list[Bout], clicks: np.ndarray) -> str:
    fig, ax = plt.subplots(figsize=(12, 3.2))
    step = max(1, len(kin.t) // 200_000)  # decima para nao gerar PNG gigante
    ax.plot(kin.t[::step], kin.speed[::step], lw=0.5, color="#4477aa")
    for b in bouts:
        ax.axvspan(kin.t[b.i0], kin.t[b.i1 - 1], color="#ee7733", alpha=0.15, lw=0)
    if len(clicks):
        ax.plot(clicks, np.zeros_like(clicks), "|", color="#cc3311", ms=12, mew=1.2)
    ax.set_xlabel("tempo (s)")
    ax.set_ylabel(f"velocidade ({kin.unit}/s)")
    ax.set_title("Velocidade do mouse — laranja: movimentos | vermelho: cliques")
    return _fig_to_b64(fig)


def fig_tremor(tr: TremorResult, unit: str) -> str:
    fig, ax = plt.subplots(figsize=(6, 3.2))
    ax.semilogy(tr.freqs, tr.psd, color="#4477aa", lw=1.0)
    ax.axvspan(8, 12, color="#cc3311", alpha=0.15, lw=0, label="banda de tremor 8–12 Hz")
    ax.set_xlim(0, 30)
    ax.set_xlabel("frequência (Hz)")
    ax.set_ylabel(f"PSD (({unit}/s)²/Hz)")
    ax.set_title("Espectro da velocidade em janelas quietas")
    ax.legend(fontsize=8)
    return _fig_to_b64(fig)


def fig_bouts(bouts: list[BoutFeatures], unit: str) -> str:
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.4))
    amp = [b.amplitude for b in bouts]
    dur = [b.duration_ms for b in bouts]
    eff = [b.efficiency for b in bouts]
    corr = [b.n_corrections for b in bouts]

    sc = axes[0].scatter(amp, dur, c=corr, cmap="YlOrRd", s=14, edgecolors="none")
    axes[0].set_xlabel(f"amplitude ({unit})")
    axes[0].set_ylabel("duração (ms)")
    axes[0].set_title("Amplitude × duração (cor = correções)")
    fig.colorbar(sc, ax=axes[0], label="correções")

    axes[1].hist(eff, bins=30, range=(0.5, 1.0), color="#4477aa")
    axes[1].set_xlabel("eficiência de trajeto")
    axes[1].set_title("Eficiência (1.0 = linha reta)")

    axes[2].hist([b.overshoot_ratio for b in bouts], bins=30, range=(1.0, 1.5), color="#ee7733")
    axes[2].set_xlabel("razão de overshoot")
    axes[2].set_title("Overshoot (>1 = passou do ponto)")
    fig.tight_layout()
    return _fig_to_b64(fig)


def _fmt(v, nd=2, suffix=""):
    if v is None:
        return "—"
    if isinstance(v, float) and not np.isfinite(v):
        return "—"
    return f"{v:.{nd}f}{suffix}"


def render_html(m: dict, images: dict[str, str]) -> str:
    def card(title, value, hint=""):
        return (
            f'<div class="card"><div class="v">{value}</div>'
            f'<div class="t">{title}</div><div class="h">{hint}</div></div>'
        )

    b = m.get("bouts_summary", {})
    tr = m.get("tremor", {})
    k = m.get("kovaaks", {})
    ka = m.get("kill_analysis", {})
    unit = m.get("unit", "counts")

    cards = []
    if k:
        cards.append(card("Cenário", k.get("scenario") or "—"))
        if k.get("score") is not None:
            cards.append(card("Score", _fmt(k["score"], 1)))
        if k.get("accuracy") is not None:
            cards.append(card("Acurácia", _fmt(k["accuracy"] * 100, 1, "%")))
    if b.get("efficiency", {}).get("median") is not None:
        cards.append(card("Eficiência de trajeto (mediana)",
                          _fmt(b["efficiency"]["median"], 3),
                          "1.0 = mouse foi em linha reta ao alvo"))
    if b.get("n_corrections", {}).get("median") is not None:
        cards.append(card("Correções por movimento (mediana)",
                          _fmt(b["n_corrections"]["median"], 1),
                          "submovimentos corretivos após o balístico"))
    if b.get("sparc", {}).get("median") is not None:
        cards.append(card("Smoothness SPARC (mediana)",
                          _fmt(b["sparc"]["median"], 2),
                          "mais próximo de 0 = mais suave"))
    if tr.get("band_power_ratio") is not None:
        cards.append(card("Tremor 8–12 Hz",
                          _fmt(tr["band_power_ratio"] * 100, 1, "%"),
                          "fração da potência em banda de tremor fisiológico"))
    if ka.get("acquisition_ms", {}).get("median") is not None:
        cards.append(card("Tempo de aquisição (mediana)",
                          _fmt(ka["acquisition_ms"]["median"], 0, " ms"),
                          "início do movimento → clique da kill"))
    rx = m.get("reaction", {})
    if rx.get("rt_median_ms") is not None:
        cards.append(card("Reação visual (mediana)",
                          _fmt(rx["rt_median_ms"], 0, " ms"),
                          f"mudança na tela → primeiro movimento "
                          f"({rx.get('n_matched', 0)} amostras)"))
        if rx.get("anticipation_rate", 0) > 0.15:
            cards.append(card("Antecipação",
                              _fmt(rx["anticipation_rate"] * 100, 0, "%"),
                              "movimentos ANTES de 80ms = chute, não reação"))
    rc = m.get("reaction_choice", {})
    if rc.get("wrong_direction_rate") is not None:
        cards.append(card("Direção errada no 1º movimento",
                          _fmt(rc["wrong_direction_rate"] * 100, 0, "%"),
                          f"erro angular mediano {_fmt(rc.get('median_angle_err_deg'), 0)}° "
                          f"({rc.get('n', 0)} spawns)"))
    pu = m.get("pursuit", {})
    if pu.get("realign_median_ms") is not None:
        cards.append(card("Realinhamento pós-inversão",
                          _fmt(pu["realign_median_ms"], 0, " ms"),
                          f"alvo inverte → mouse acompanha; perda de movimento em "
                          f"{_fmt(pu.get('loss_rate', 0) * 100, 0, '%')} das inversões"))

    sev_style = {
        "atencao": ("#d68910", "ATENÇÃO"),
        "info": ("#7f8c8d", "INFO"),
        "positivo": ("#1e8449", "OK"),
    }
    insights_html = ""
    for ins in m.get("insights", []):
        color, tag = sev_style.get(ins["severity"], ("#7f8c8d", "INFO"))
        insights_html += (
            f'<div class="insight" style="border-left-color:{color}">'
            f'<span class="tag" style="background:{color}">{tag}</span>'
            f'<b>{ins["title"]}</b><p>{ins["body"]}</p></div>'
        )
    if insights_html:
        insights_html = (
            "<h2>Observações da sessão</h2>"
            '<p style="font-size:.8rem;color:#888">fatos medidos nesta sessão — '
            "prescrição de treino fica com o coach (plano + narrativa)</p>"
            + insights_html
        )

    imgs_html = "".join(
        f'<h2>{title}</h2><img src="data:image/png;base64,{b64}">'
        for title, b64 in images.items()
    )

    warns = "".join(f"<li>{w}</li>" for w in m.get("warnings", []))
    warns_html = f'<div class="warn"><ul>{warns}</ul></div>' if warns else ""

    return f"""<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<title>aimscope — {m.get('session', '')}</title>
<style>
 body {{ font-family: Segoe UI, sans-serif; margin: 2rem auto; max-width: 1100px; color: #222; }}
 h1 {{ font-size: 1.4rem; }} h2 {{ font-size: 1.05rem; margin-top: 1.6rem; }}
 .cards {{ display: flex; flex-wrap: wrap; gap: 12px; }}
 .card {{ border: 1px solid #ddd; border-radius: 8px; padding: 12px 16px; min-width: 160px; }}
 .card .v {{ font-size: 1.5rem; font-weight: 600; }}
 .card .t {{ font-size: .85rem; color: #555; margin-top: 2px; }}
 .card .h {{ font-size: .72rem; color: #999; margin-top: 2px; max-width: 200px; }}
 img {{ max-width: 100%; border: 1px solid #eee; border-radius: 6px; }}
 .warn {{ background: #fff6e5; border: 1px solid #f0c36d; border-radius: 6px;
          padding: 4px 16px; margin: 12px 0; font-size: .85rem; }}
 .insight {{ border: 1px solid #e5e5e5; border-left: 4px solid #ccc; border-radius: 6px;
             padding: 10px 14px; margin: 10px 0; }}
 .insight p {{ margin: 6px 0 0; font-size: .9rem; color: #444; }}
 .insight .tag {{ color: #fff; font-size: .68rem; font-weight: 700; padding: 2px 8px;
                  border-radius: 10px; margin-right: 8px; vertical-align: middle; }}
 .drill {{ margin-top: 8px; font-size: .88rem; background: #f4f9f4;
           border-radius: 6px; padding: 8px 10px; }}
 footer {{ margin-top: 2rem; font-size: .75rem; color: #999; }}
</style></head><body>
<h1>aimscope — relatório de sessão</h1>
<p><b>{m.get('session', '')}</b> · unidade: {unit} · {m.get('n_bouts', 0)} movimentos analisados</p>
{warns_html}
<div class="cards">{''.join(cards)}</div>
{insights_html}
{imgs_html}
<footer>Métricas por movimento (bout), nunca sobre o trace inteiro — ver docs. aimscope v0.1</footer>
</body></html>"""


def write_report(
    session_dir: Path,
    metrics: dict,
    kin: Kinematics | None,
    bouts_raw: list[Bout],
    bouts: list[BoutFeatures],
    tremor: TremorResult | None,
    clicks: np.ndarray,
    html: bool = True,
) -> None:
    (session_dir / "metrics.json").write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    if not html or kin is None:
        return
    images: dict[str, str] = {}
    images["Visão geral"] = fig_overview(kin, bouts_raw, clicks)
    if tremor is not None:
        images["Tremor"] = fig_tremor(tremor, kin.unit)
    if bouts:
        images["Movimentos"] = fig_bouts(bouts, kin.unit)
    (session_dir / "report.html").write_text(
        render_html(metrics, images), encoding="utf-8"
    )
