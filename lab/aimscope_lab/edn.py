"""Leitor/escritor EDN minimo — so o subconjunto usado pelo catalogo do coach.

Cobre: comentarios ';', strings, keywords, numeros, true/false/nil, vetores,
listas, mapas e sets #{}. Sem tagged literals, chars ou metadata — o catalogo
nao usa. Mantido aqui pra nao arrastar dependencia de parser EDN pro lab.
"""

from __future__ import annotations

from typing import Any


class Keyword:
    """Keyword EDN (:foo ou :foo/bar). Igualdade/hash pelo nome."""

    __slots__ = ("name",)

    def __init__(self, name: str):
        self.name = name

    def __repr__(self) -> str:
        return f":{self.name}"

    def __eq__(self, other: object) -> bool:
        return isinstance(other, Keyword) and other.name == self.name

    def __hash__(self) -> int:
        return hash(("edn.Keyword", self.name))


K = Keyword

_DELIMS = set('()[]{}";')
_WS = set(" \t\r\n,")


class _Reader:
    def __init__(self, text: str):
        self.s = text
        self.i = 0
        self.n = len(text)

    def _skip_ws(self) -> None:
        while self.i < self.n:
            c = self.s[self.i]
            if c in _WS:
                self.i += 1
            elif c == ";":
                while self.i < self.n and self.s[self.i] != "\n":
                    self.i += 1
            else:
                return

    def _peek(self) -> str:
        return self.s[self.i] if self.i < self.n else ""

    def read(self) -> Any:
        self._skip_ws()
        if self.i >= self.n:
            raise ValueError("EDN: fim inesperado")
        c = self.s[self.i]
        if c == "(" or c == "[":
            return self._read_seq({"(": ")", "[": "]"}[c])
        if c == "{":
            return self._read_map()
        if c == "#":
            if self.s[self.i : self.i + 2] == "#{":
                self.i += 1  # consome '#', _read_seq ve '{'
                items = self._read_seq("}")
                return set(items)
            raise ValueError(f"EDN: dispatch nao suportado em {self.i}")
        if c == '"':
            return self._read_string()
        if c == ":":
            return self._read_keyword()
        return self._read_atom()

    def _read_seq(self, close: str) -> list:
        self.i += 1  # abre
        out: list[Any] = []
        while True:
            self._skip_ws()
            if self.i >= self.n:
                raise ValueError(f"EDN: '{close}' faltando")
            if self.s[self.i] == close:
                self.i += 1
                return out
            out.append(self.read())

    def _read_map(self) -> dict:
        items = self._read_seq("}")
        if len(items) % 2 != 0:
            raise ValueError("EDN: mapa com numero impar de formas")
        return {items[j]: items[j + 1] for j in range(0, len(items), 2)}

    def _read_string(self) -> str:
        self.i += 1
        out: list[str] = []
        while self.i < self.n:
            c = self.s[self.i]
            if c == "\\":
                esc = self.s[self.i + 1]
                out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}.get(esc, esc))
                self.i += 2
            elif c == '"':
                self.i += 1
                return "".join(out)
            else:
                out.append(c)
                self.i += 1
        raise ValueError("EDN: string sem fechamento")

    def _read_token(self) -> str:
        j = self.i
        while j < self.n and self.s[j] not in _WS and self.s[j] not in _DELIMS:
            j += 1
        tok = self.s[self.i : j]
        self.i = j
        return tok

    def _read_keyword(self) -> Keyword:
        self.i += 1
        return Keyword(self._read_token())

    def _read_atom(self) -> Any:
        tok = self._read_token()
        if tok == "true":
            return True
        if tok == "false":
            return False
        if tok == "nil":
            return None
        try:
            return int(tok)
        except ValueError:
            pass
        try:
            return float(tok)
        except ValueError:
            pass
        return Keyword(tok)  # simbolo: tratamos como keyword (catalogo nao usa)


def loads(text: str) -> Any:
    return _Reader(text).read()


def _dump(obj: Any, out: list[str], indent: int) -> None:
    pad = "  " * indent
    if isinstance(obj, Keyword):
        out.append(repr(obj))
    elif obj is True:
        out.append("true")
    elif obj is False:
        out.append("false")
    elif obj is None:
        out.append("nil")
    elif isinstance(obj, str):
        esc = obj.replace("\\", "\\\\").replace('"', '\\"')
        out.append(f'"{esc}"')
    elif isinstance(obj, float):
        out.append(repr(float(obj)))
    elif isinstance(obj, int):
        out.append(str(obj))
    elif isinstance(obj, dict):
        out.append("{")
        first = True
        for k, v in obj.items():
            if not first:
                out.append(f"\n{pad} ")
            _dump(k, out, indent + 1)
            out.append(" ")
            _dump(v, out, indent + 1)
            first = False
        out.append("}")
    elif isinstance(obj, (list, tuple)):
        out.append("[")
        for j, v in enumerate(obj):
            if j:
                out.append(" ")
            _dump(v, out, indent + 1)
        out.append("]")
    elif isinstance(obj, (set, frozenset)):
        out.append("#{")
        for j, v in enumerate(sorted(obj, key=repr)):
            if j:
                out.append(" ")
            _dump(v, out, indent + 1)
        out.append("}")
    else:
        raise TypeError(f"EDN: tipo nao serializavel {type(obj)}")


def dumps(obj: Any) -> str:
    out: list[str] = []
    _dump(obj, out, 0)
    return "".join(out)
