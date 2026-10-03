from __future__ import annotations

INVALID_PATH_CHARS = '<>:"/\\|?*'


class AppNameError(ValueError):
    pass


def validate_app_name(name: str) -> str:
    if not isinstance(name, str) or not name:
        raise AppNameError("application name is unavailable")
    if name != name.strip() or name in {".", ".."} or name.endswith("."):
        raise AppNameError(f"invalid application name: {name!r}")
    if any(ord(ch) < 32 or ch in INVALID_PATH_CHARS for ch in name):
        raise AppNameError(f"invalid application name: {name!r}")
    return name


def smm_app_name(name: str) -> str:
    name = validate_app_name(name)
    return f"[{name[0]}]{name[1:]}"
