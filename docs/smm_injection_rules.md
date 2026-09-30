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

`--ankh-only` guarantees this core:

- `ModAnkh`
- `ModLastStand`
- Store / Loot / Debug Console dependencies

It also attempts two optional combat buffs:

- `ModInstantKill`: keep the payload whenever its target API is compatible. Prefer the pre-defense `Char.attack()` hook; if that hook cannot be identified safely, retain the buff and use its existing `attackProc()` fallback.
- `ModForceHit` and `ModInstantKill` share one selected hit-check capability. Prefer known direct ABIs in order: `Char.hit(Char, Char, float, boolean)`, then legacy `Char.hit(Char, Char, boolean)`. Only when neither exists, trace terminal `Char.attack()` to a unique static boolean hit-check whose first two parameters are attacker/defender `Char` values and whose result controls the hit branch. Patch the selected method even if its name or additional parameters differ from vanilla.

Both can be applied from the Debug Console with `affect ModInstantKill` or `affect ModForceHit`. Minimal injection still does not install the full SMM menu, Assassinate, or Riposte.

## Injection Kit

The artifact is `SMM-m<version>-InjectKit.zip`. Keep the injector scripts and donor APK/JAR together.

If an injected Java class changes, rebuild the kit so the donors match the source. Injector-only Python changes do not require rebuilding the donors.

## APK signing key

The APK injector creates `smm-inject.keystore` beside `smm-inject-donor.apk` the first time a signing key is needed. Later APK injections reuse the same keystore.

Keep this file if you want later injected APKs to update an already-installed injected APK with the same package name. If the keystore is deleted or a different one is used, the Android package signature changes and the old installed APK normally must be uninstalled before the newly signed APK can be installed.

When upgrading to a newly extracted Injection Kit, copy the existing `smm-inject.keystore` into the new kit directory before running `inject_apk.py` if signature continuity matters. The keystore is local-only and must not be committed to the repository or bundled into public artifacts.

JAR injection does not use this APK signing key.

## Compatibility rules

- Treat the target's compiled API as authoritative; do not assume compatibility from its version number.
- Rebase SPD package references when the fork uses another package name.
- Reject unresolved or ambiguous ABI dependencies instead of forcing the build.
- Do not copy arbitrary donor-only or obfuscated classes to hide compatibility errors.
- Keep `--ankh-only` narrow. ModAnkh + ModLastStand and Store / Loot / Debug Console are the guaranteed core; Instant Kill and Force Hit are optional extras and must never make that core fail.
- Optional does not mean passive. APK and JAR injection must first try the same direct modern/legacy hit ABIs for Instant Kill and Force Hit, then the same structural fallback. Instant Kill may fall back to `attackProc()` if no safe success-branch hook exists; Force Hit is skipped only when no safe selected hit-check exists.
- Optional full-SMM UI bridges must not make an ankh-only target crash. If the UI class itself or any transitive target-UI dependency fails to link at runtime, disable only that optional UI bridge and keep the underlying combat buff active.
- Mod item action text stays in code. On both APK and JAR, if a legacy `WndUseItem` bypasses `Item.actionName()` and calls `Messages.get(...)` directly, adapt that call site to route `ac_*` labels through the target's existing virtual `Item.actionName(action, hero)` method. Do not patch `items*.properties` for ModAnkh labels.
- Last Stand's BuffIndicator behavior is injected directly into the target on both APK and JAR, in both full and ankh-only modes: short click opens the Store window; long click preserves the target's original buff-info action. The old mixed click/tag UI layer is removed. The optional full-SMM edge control is a separate `ModLastStandTag` and is not part of the ankh-only dependency closure.
- Full injection may use the existing SMM menu and the Riposte `Char.attack()` hook. For JAR injection, the injector selects the unique terminal `Char.attack()` overload instead of assuming the vanilla four-parameter wrapper. Instant Kill uses the same pre-defense hit branch when it can be identified safely; otherwise it remains available through its `attackProc()` fallback. Force Hit uses the structurally identified hit-check. Minimal injection must not install Riposte or the full menu, but may install the narrow Instant Kill / Force Hit hooks described above.
- A Debug Console command can expose bugs already present in the target game. Do not patch unrelated target gameplay bugs merely to make a command appear successful.

## Current naming

The assassin buff class is `ModAssassinate`. The old `ModAssassinBuff` class is removed; no compatibility alias is kept.

## Validation

Representative APK/JAR tests are useful, but successful static injection does not guarantee every fork-specific runtime path. Required core ABI that cannot be adapted safely should fail closed. Optional features should first attempt safe structural adaptation and, when available, a documented fallback; only that optional feature should be skipped when neither path is safe.
