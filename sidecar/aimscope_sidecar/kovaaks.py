"""Parser defensivo do CSV de stats do KovaaK's.

Formato tipico (varia entre versoes — parseie permissivamente):

    Kill #,Timestamp,Bot,Weapon,TTK,Shots,Hits,Accuracy,Damage Done,...
    1,13:31:04.153,bot_name,weapon,0.856s,3,2,0.667,...
    ...
    Weapon,Shots,Hits,Damage Done,Damage Possible
    weapon,57,41,...
    ...
    Kills:,15
    Score:,123.4
    Scenario:,1wall 6targets small
    Sens Scale:,Valorant
    Horiz Sens:,0.4
    ...

Nome do arquivo: "<Cenario> - <Modo> - YYYY.MM.DD-HH.MM.SS Stats.csv"
"""

from __future__ import annotations

import csv
import io
import re
from dataclasses import dataclass, field
from datetime import date, datetime, time
from pathlib import Path

FILENAME_RE = re.compile(
    r"(?P<date>\d{4})\.(?P<month>\d{2})\.(?P<day>\d{2})-(?P<h>\d{2})\.(?P<m>\d{2})\.(?P<s>\d{2})\s+Stats\.csv$",
    re.IGNORECASE,
)


@dataclass
class Kill:
    index: int
    time: datetime | None      # combinado com a data do arquivo
    bot: str = ""
    weapon: str = ""
    ttk_s: float | None = None
    shots: int | None = None
    hits: int | None = None
    accuracy: float | None = None


@dataclass
class KovaaksStats:
    path: Path
    scenario: str = ""
    file_date: date | None = None
    kills: list[Kill] = field(default_factory=list)
    summary: dict = field(default_factory=dict)  # kv cru do rodape

    @property
    def sens_scale(self) -> str | None:
        return self.summary.get("Sens Scale")

    @property
    def horiz_sens(self) -> float | None:
        v = self.summary.get("Horiz Sens")
        try:
            return float(v)
        except (TypeError, ValueError):
            return None

    @property
    def score(self) -> float | None:
        try:
            return float(self.summary.get("Score"))
        except (TypeError, ValueError):
            return None

    @property
    def overall_accuracy(self) -> float | None:
        shots = _to_int(self.summary.get("Shots"))
        hits = _to_int(self.summary.get("Hits"))
        if shots and hits is not None and shots > 0:
            return hits / shots
        accs = [k.accuracy for k in self.kills if k.accuracy is not None]
        return sum(accs) / len(accs) if accs else None


def _to_int(v) -> int | None:
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None


def _to_float(v) -> float | None:
    if v is None:
        return None
    s = str(v).strip().rstrip("s").rstrip("%")
    try:
        return float(s)
    except ValueError:
        return None


def _parse_kill_time(s: str, file_date: date | None) -> datetime | None:
    s = s.strip()
    for fmt in ("%H:%M:%S.%f", "%H:%M:%S"):
        try:
            t = datetime.strptime(s, fmt).time()
            return datetime.combine(file_date or date.today(), t)
        except ValueError:
            continue
    return None


def parse_stats_csv(path: str | Path) -> KovaaksStats:
    path = Path(path)
    out = KovaaksStats(path=path)

    m = FILENAME_RE.search(path.name)
    if m:
        out.file_date = date(int(m["date"]), int(m["month"]), int(m["day"]))
        out.scenario = path.name[: m.start()].rstrip(" -")

    text = path.read_text(encoding="utf-8-sig", errors="replace")
    lines = text.splitlines()

    kill_header: list[str] | None = None
    for raw in lines:
        line = raw.rstrip("\r\n")
        if not line.strip():
            kill_header = None
            continue

        cells = next(csv.reader(io.StringIO(line)))
        first = cells[0].strip() if cells else ""

        if first.lower().startswith("kill #"):
            kill_header = [c.strip() for c in cells]
            continue

        if kill_header and first.isdigit():
            row = dict(zip(kill_header, cells))
            out.kills.append(Kill(
                index=int(first),
                time=_parse_kill_time(row.get("Timestamp", ""), out.file_date),
                bot=row.get("Bot", ""),
                weapon=row.get("Weapon", ""),
                ttk_s=_to_float(row.get("TTK")),
                shots=_to_int(row.get("Shots")),
                hits=_to_int(row.get("Hits")),
                accuracy=_to_float(row.get("Accuracy")),
            ))
            continue

        # linhas kv do rodape: "Chave:,Valor"
        if len(cells) >= 2 and first.endswith(":"):
            out.summary[first.rstrip(":")] = cells[1].strip()
            if first.rstrip(":").lower() == "scenario" and cells[1].strip():
                out.scenario = cells[1].strip()
            continue

    return out
