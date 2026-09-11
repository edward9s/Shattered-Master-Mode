# Shattered Master Mode Binary Injection Rules

[正體中文版](smm_injection_rules.zh-TW.md)

## Modes

Use the Injection Kit against an already-built SPD-derived APK or JAR.

Full SMM:

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

Minimal injection for older or heavily modified forks:

```bash
python inject_apk.py TARGET.apk --ankh-only
python inject_jar.py TARGET.jar --ankh-only
```

`--ankh-only` contains:

- `ModAnkh`
- `ModLastStand`
- Store / Loot / Console dependencies

It does not install the full SMM menu or unrelated combat features.

## Injection Kit

The artifact is `SMM-m<version>-InjectKit.zip`. Keep the injector scripts and donor APK/JAR together.

If an injected Java class changes, rebuild the kit so the donors match the source. Injector-only Python changes do not require rebuilding the donors.

## Compatibility rules

- Treat the target's compiled API as authoritative; do not assume compatibility from its version number.
- Rebase SPD package references when the fork uses another package name.
- Reject unresolved or ambiguous ABI dependencies instead of forcing the build.
- Do not copy arbitrary donor-only or obfuscated classes to hide compatibility errors.
- Keep `--ankh-only` narrow. New SMM features must not enter that payload unless explicitly intended.
- Full injection may use the existing SMM menu and Riposte hooks; minimal injection must not install unrelated full-SMM hooks.

## Current naming

The assassin buff class is `ModAssassinate`. The old `ModAssassinBuff` class is removed; no compatibility alias is kept.

## Validation

Representative APK/JAR tests are useful, but successful static injection does not guarantee every fork-specific runtime path. A required ABI that cannot be adapted safely should fail closed.
