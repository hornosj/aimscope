"""Download de VODs publicos — yt-dlp com rate polido e cache local OBRIGATORIO.

Mesma regra de etica do cliente da API kovaaks (design §11): cache primeiro,
rate limitado, source_url guardado por observacao pra procedencia. Video de
terceiros NUNCA e redistribuido; o produto so publica ancoras agregadas.
"""

from __future__ import annotations

from pathlib import Path

RATE_LIMIT_BPS = 2_000_000   # polido: nao e um scraper


def video_id_of(url: str) -> str:
    import yt_dlp

    with yt_dlp.YoutubeDL({"quiet": True}) as ydl:
        info = ydl.extract_info(url, download=False)
    return info["id"]


def fetch(url: str, cache_dir: str | Path) -> Path:
    """Baixa (ou reusa do cache) um VOD. Retorna o caminho do arquivo local."""
    import yt_dlp

    cache = Path(cache_dir)
    cache.mkdir(parents=True, exist_ok=True)

    vid = video_id_of(url)
    hits = sorted(cache.glob(f"{vid}.*"))
    if hits:
        return hits[0]

    opts = {
        "outtmpl": str(cache / "%(id)s.%(ext)s"),
        "ratelimit": RATE_LIMIT_BPS,
        # 1080p basta: acima disso so cresce o custo do fluxo optico
        "format": "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
        "quiet": True,
        "noprogress": True,
    }
    with yt_dlp.YoutubeDL(opts) as ydl:
        ydl.download([url])
    hits = sorted(cache.glob(f"{vid}.*"))
    if not hits:
        raise RuntimeError(f"yt-dlp nao produziu arquivo para {url}")
    return hits[0]
