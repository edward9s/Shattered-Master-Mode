from __future__ import annotations

from collections.abc import Hashable, Iterable, Mapping
from typing import TypeVar

NodeT = TypeVar("NodeT", bound=Hashable)


def select_unique_terminal(
    nodes: Iterable[NodeT],
    edges: Mapping[NodeT, Iterable[NodeT]],
) -> tuple[NodeT | None, str]:
    """Select the sole terminal in an acyclic delegation graph.

    Callers are responsible for parsing their representation (Java source, smali,
    etc.) into nodes and same-family edges. This function owns the shared graph
    semantics: empty families, cycles, and multiple terminals all fail closed.
    """
    ordered = list(nodes)
    if not ordered:
        return None, "no candidates are available"

    family = set(ordered)
    normalized = {
        node: {callee for callee in edges.get(node, ()) if callee in family}
        for node in ordered
    }

    state: dict[NodeT, int] = {}

    def visit(node: NodeT) -> bool:
        marker = state.get(node, 0)
        if marker == 1:
            return False
        if marker == 2:
            return True
        state[node] = 1
        for callee in normalized[node]:
            if not visit(callee):
                return False
        state[node] = 2
        return True

    if not all(visit(node) for node in ordered):
        return None, "delegation graph contains a cycle"

    terminals = [node for node in ordered if not normalized[node]]
    if len(terminals) != 1:
        found = ", ".join(str(node) for node in terminals) if terminals else "none"
        return None, f"expected exactly one terminal, found {len(terminals)}: {found}"

    terminal = terminals[0]
    return terminal, f"unique terminal {terminal} selected from {len(ordered)} candidate(s)"
