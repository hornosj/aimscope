"""Observações FACTUAIS por sessão (decisão do grill: prescrição é monopólio
do coach — este módulo APONTA fatos, nunca aconselha).

Cada observação diz o que o número É e o que ele significa mecanicamente.
Nada de "treine X", nada de "mude a sens": isso vem do coach (plan/narrativa),
que conhece o perfil de objetivo do jogador.

Severidades: "atencao" (número fora da faixa esperada), "info", "positivo".
Limiares PROVISORIOS como sempre (design-coach.md §2).
"""

from __future__ import annotations


def _get(m: dict, *path, default=None):
    cur = m
    for p in path:
        if not isinstance(cur, dict) or p not in cur:
            return default
        cur = cur[p]
    return cur


def generate(m: dict) -> list[dict]:
    out: list[dict] = []
    n_bouts = m.get("n_bouts", 0)

    def add(severity, title, body):
        out.append({"severity": severity, "title": title, "body": body})

    if n_bouts < 30:
        add(
            "info",
            "Amostra pequena",
            f"Apenas {n_bouts} movimentos analisados — números com pouca "
            "confiança estatística nesta sessão.",
        )
        if n_bouts < 5:
            return out

    if m.get("unit") != "deg":
        add(
            "info",
            "Métricas em counts",
            "Sem DPI/sens configurados as métricas ficam em counts de mouse — "
            "não comparáveis entre sessões com sens diferente.",
        )

    ov = _get(m, "bouts_summary", "overshoot_ratio", "median")
    if ov is not None and ov > 1.05:
        add(
            "atencao" if ov > 1.10 else "info",
            "Overshoot acima do neutro",
            f"Mediana de {100*(ov-1):.0f}% além do alvo no eixo principal do "
            "movimento — cada overshoot custa uma correção de volta.",
        )

    corr = _get(m, "bouts_summary", "n_corrections", "median")
    corr_mean = _get(m, "bouts_summary", "n_corrections", "mean")
    if corr is not None and corr >= 2:
        add(
            "atencao",
            "Múltiplas correções por movimento",
            f"Mediana de {corr:.0f} submovimentos corretivos por flick "
            f"(média {corr_mean:.1f}). O movimento balístico inicial está "
            "terminando longe do alvo.",
        )

    eff = _get(m, "bouts_summary", "efficiency", "median")
    if eff is not None and eff < 0.85:
        add(
            "atencao",
            "Trajeto pouco eficiente",
            f"Eficiência mediana {eff:.2f} (1.0 = linha reta): o mouse percorre "
            f"~{100*(1/max(eff,0.01)-1):.0f}% mais caminho que o necessário.",
        )

    tr_ratio = _get(m, "tremor", "band_power_ratio")
    tr_peak = _get(m, "tremor", "peak_freq_hz")
    if tr_ratio is not None and tr_ratio > 0.35 and tr_peak is not None and 7.0 <= tr_peak <= 13.0:
        add(
            "atencao",
            "Energia de tremor elevada na mira parada",
            f"{100*tr_ratio:.0f}% da potência em janelas quietas está na banda "
            f"8–12 Hz (pico em {tr_peak:.1f} Hz) — assinatura de tremor "
            "fisiológico/tensão.",
        )

    rx = _get(m, "reaction", "rt_median_ms")
    if rx is not None:
        ant = _get(m, "reaction", "anticipation_rate", default=0.0)
        body = f"Reação visual mediana de {rx:.0f} ms ({_get(m, 'reaction', 'n_matched')} amostras)."
        if ant > 0.15:
            body += f" {100*ant:.0f}% dos movimentos partiram antes de 80 ms (antecipação/chute)."
        add("info" if rx < 300 else "atencao", "Reação visual", body)

    wd = _get(m, "reaction_choice", "wrong_direction_rate")
    if wd is not None and wd > 0.15:
        add(
            "atencao",
            "Direção inicial errada frequente",
            f"{100*wd:.0f}% dos primeiros movimentos pós-spawn saíram na direção "
            f"errada (erro angular mediano "
            f"{_get(m, 'reaction_choice', 'median_angle_err_deg', default=0):.0f}°).",
        )

    loss = _get(m, "pursuit", "loss_rate")
    if loss is not None and loss > 0.3:
        add(
            "atencao",
            "Perda de movimento em inversões",
            f"Em {100*loss:.0f}% das inversões do alvo o realinhamento levou "
            f">{350} ms (mediana "
            f"{_get(m, 'pursuit', 'realign_median_ms', default=0):.0f} ms).",
        )

    acq = _get(m, "kill_analysis", "acquisition_ms", "median")
    if acq is not None:
        add(
            "info",
            "Aquisição por kill",
            f"Mediana de {acq:.0f} ms do início do movimento ao clique da kill "
            f"({_get(m, 'kill_analysis', 'n_with_acquisition_bout', default=0)} kills).",
        )

    if not any(i["severity"] == "atencao" for i in out):
        add(
            "positivo",
            "Sessão dentro das faixas esperadas",
            "Nenhuma métrica fora da faixa nesta sessão. Diagnóstico e plano "
            "de treino ficam com o coach (histórico entre sessões).",
        )

    return out
