#!/usr/bin/env python3
"""Inject the complete compiled SMM payload into an SPD-derived APK.

This is the full-payload migration front-end. It deliberately reuses the
existing APK injector's compatibility checks, target-package rebasing, donor
helper handling, untouched-target-DEX layout, manifest patching, and signing.

During this migration stage the legacy ModAnkh startup hook is still retained;
once the complete payload passes representative target validation, the entry
hook can move to the traditional game-menu injection without changing the
payload boundary again.
"""
from __future__ import annotations

from typing import Sequence

import inject_apk as injector


# The legacy injector already routes this predicate through its controlled
# payload collector. Expanding only this root keeps all existing compatibility
# validation intact while making every compiled com.spd.mod class explicit.
injector.MOD_ITEM_DESCRIPTOR_PREFIX = "Lcom/spd/mod/"


def main(argv: Sequence[str] | None = None) -> int:
    return injector.main(argv)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
