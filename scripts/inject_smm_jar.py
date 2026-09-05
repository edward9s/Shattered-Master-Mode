#!/usr/bin/env python3
"""Inject the complete compiled SMM payload into an SPD-derived desktop JAR.

This migration front-end reuses the existing JAR injector's target-package
rebasing, ModAnkh adaptation, validation and base-JAR preservation while
expanding the controlled payload surface to every compiled com.spd.mod class.

The legacy ModAnkh startup hook is intentionally retained during this first
full-payload validation stage. Menu-hook migration is the next isolated step.
"""
from __future__ import annotations

from typing import Sequence

import inject_jar as injector


injector.MOD_ITEM_CLASS_PREFIX = "com/spd/mod/"


def main(argv: Sequence[str] | None = None) -> int:
    return injector.main(argv)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
