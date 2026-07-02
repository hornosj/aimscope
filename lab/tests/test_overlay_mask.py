"""Regressao do caso real que travou os primeiros VODs: em cena de BAIXA
textura (paredes lisas do KovaaK's + compressao do YouTube), os cantos mais
fortes sao do HUD estatico — sem mascara de overlay o estimador rastreia o HUD
parado e reporta movimento ~zero com confianca alta. A mascara por variancia
temporal (video.static_overlay_mask) tem que recuperar o pan injetado."""

import cv2
import numpy as np
import pytest

from aimscope_lab import camera, video

FPS = 30.0
SIZE = (640, 360)


def _weak_texture(rng):
    """Fundo de baixo contraste (paredes lisas sob compressao): gradientes
    ~15 niveis — cantos ordens de grandeza mais fracos que o texto do HUD
    (Shi-Tomasi escala com gradiente²), mas com std temporal mensuravel."""
    tex = rng.normal(120, 6, size=(1400, 2000)).astype(np.float32)
    tex = cv2.GaussianBlur(tex, (0, 0), 8.0)
    for _ in range(180):
        x, y = int(rng.uniform(0, 2000)), int(rng.uniform(0, 1400))
        cv2.circle(tex, (x, y), int(rng.uniform(15, 50)), float(rng.uniform(105, 140)), -1)
    return cv2.GaussianBlur(tex, (0, 0), 2.0).clip(0, 255).astype(np.uint8)


def _draw_hud(frame):
    """Painel tipo 'SESSION' + timer: cantos FORTES e estaticos, fora das
    margens de borda (5%) — exatamente o que enganou o estimador."""
    f = frame.copy()
    cv2.rectangle(f, (40, 30), (220, 110), 15, -1)
    for i in range(5):
        cv2.putText(f, "SESSION 00:33", (50, 52 + i * 12),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, 240, 1)
    cv2.rectangle(f, (500, 30), (610, 70), 20, -1)
    cv2.putText(f, "60 FPS", (510, 55), cv2.FONT_HERSHEY_SIMPLEX, 0.5, 235, 1)
    return f


@pytest.fixture(scope="module")
def hud_video(tmp_path_factory):
    """mp4 real com pan senoidal conhecido + HUD estatico."""
    rng = np.random.default_rng(9)
    tex = _weak_texture(rng)
    n = 120
    t = np.arange(n)
    cx = 1000 + 45 * np.sin(2 * np.pi * t / 40)
    cy = 700 + 18 * np.cos(2 * np.pi * t / 55)

    path = tmp_path_factory.mktemp("vod") / "hud.mp4"
    wr = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), FPS, SIZE)
    assert wr.isOpened(), "VideoWriter sem codec mp4v"
    for x, y in zip(cx, cy):
        frame = cv2.getRectSubPix(tex, SIZE, (float(x), float(y)))
        frame = _draw_hud(frame)
        wr.write(cv2.cvtColor(frame, cv2.COLOR_GRAY2BGR))
    wr.release()
    return path, np.diff(cx), np.diff(cy)


def test_sem_mascara_trava_no_hud(hud_video):
    """Documenta o modo de falha: sem a mascara, o movimento some (por isso a
    mascara existe). Se este teste falhar porque o estimador passou a acertar
    SEM mascara, otimo — reavaliar se ela ainda e necessaria."""
    path, inj_dx, _ = hud_video
    ego = camera.estimate((t, f) for t, f in video.frames(path))
    amp_ratio = np.std(ego.dx) / np.std(inj_dx)
    assert amp_ratio < 0.5, "estimador sem mascara nao trava mais no HUD?"


def test_mascara_de_overlay_recupera_o_pan(hud_video):
    path, inj_dx, inj_dy = hud_video
    roi = video.detect_game_roi(path)
    overlay = video.static_overlay_mask(path, roi)
    # a mascara achou o HUD (exclui os paineis, mantem fundo utilizavel)
    assert overlay[60, 120] == 0          # dentro do painel SESSION
    assert overlay.mean() > 100           # maior parte do fundo continua valida

    ego = camera.estimate(((t, roi.crop(f)) for t, f in video.frames(path)),
                          extra_mask=overlay)
    assert np.corrcoef(ego.dx, inj_dx)[0, 1] > 0.95
    assert np.corrcoef(ego.dy, inj_dy)[0, 1] > 0.95
    assert np.median(np.abs(ego.dx - inj_dx)) < 0.5
