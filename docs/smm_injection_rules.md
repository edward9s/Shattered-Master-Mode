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
- `ModLastStand`, including `ModLastStandTag` / runtime tag layout
- Store / Loot / Debug Console dependencies

It also attempts five optional buffs:

- `ModParryRiposte`: include its complete dependency closure when compatible. Parry and Riposte share the selected `Char.hit(...)` helper as their single combat hook. Each hit check first observes and queues Riposte, then Force Hit overrides the Parry result, and only then can Parry force the hit to miss. Normal attacks, Yog/Eye-style direct guaranteed `Char.hit(...)` attacks, and ranged attacks therefore use the same path. The implementation no longer depends on `Char.attack()` entry/completion hooks, Hero/Mob Focus, `Actor.current`, Champion APIs, or fork-specific Focus behavior. After a successful Parry, `defenseVerb()` call sites are presentation-only: they provide the localized `parried` text and `HIT_PARRY` sound while non-SMM misses keep the target's original virtual `defenseVerb()` behavior.
- `ModInstantKill`: depend only on the unique terminal `Char.attack()` overload. Force Hit + Instant Kill resolves at attack entry; otherwise Instant Kill runs only when the native terminal attack is already returning `true`. Native defense side effects therefore run first. There is no `attackProc()` fallback and no hit-success branch analysis.
- `ModForceHit` owns the selected hit-check capability; `ModInstantKill` does not depend on it. Prefer known direct ABIs by method availability: `Char.hit(Char, Char, float, boolean)`, then legacy `Char.hit(Char, Char, boolean)`. Only when neither direct ABI exists, structurally trace terminal `Char.attack()` to one uniquely called static boolean helper whose first two parameters are attacker/defender `Char` values.
- `ModAssassinate`: include its complete runtime UI/combat closure when compatible. Its edge Tag and map-long-press layer attach at runtime and require no extra `GameScene` or `Char` patch.
- `ModEnemySurge`: include its gameplay + configuration-window closure when compatible. It needs no binary spawn hook; the buff uses the target's existing `Level.mobLimit()`, `mobCount()`, `respawnCooldown()`, and `spawnMob(...)` APIs. The richer source-build overlay remains optional and is not part of the minimal payload.

All five can be applied from the Debug Console with `affect ModParryRiposte`, `affect ModInstantKill`, `affect ModForceHit`, `affect ModAssassinate`, or `affect ModEnemySurge`. Minimal injection still does not install the full SMM menu.

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
- Keep `--ankh-only` narrow. ModAnkh + ModLastStand and Store / Loot / Debug Console are the guaranteed core; Parry/Riposte, Instant Kill, Force Hit, Assassinate, and Enemy Surge are optional extras and must never make that core fail.
- Optional does not mean passive. APK and JAR injection use the same unique terminal-attack rule for Riposte and Instant Kill, and the same direct-first selected-hit rule for Parry and Force Hit. Parry/Riposte requires both a safe terminal `Char.attack()` and a safe selected hit-check; Instant Kill requires only the terminal attack; Force Hit requires only the selected hit-check.
- In `--ankh-only`, Parry/Riposte, Instant Kill, Force Hit, Assassinate, and Enemy Surge are validated as complete optional dependency closures, including their configuration windows. If a feature's UI or target-API dependencies are incompatible, skip that optional feature before patching `BuffIndicator`; never emit a target click bridge that references an omitted payload class.
- Mod item action text stays in code. On both APK and JAR, if a legacy `WndUseItem` bypasses `Item.actionName()` and calls `Messages.get(...)` directly, adapt that call site to route `ac_*` labels through the target's existing virtual `Item.actionName(action, hero)` method. Do not patch `items*.properties` for ModAnkh labels.
- Configurable SMM buff clicks use one direct `BuffIndicator` bridge on source builds, APK injection, and JAR injection. Short click calls the buff's own `open()` / `openInfo()` method; long click preserves the target's native buff-info action. Full SMM handles Last Stand, Parry/Riposte, Instant Kill, Force Hit, Assassinate, and Enemy Surge. `--ankh-only` handles Last Stand plus only the optional Parry/Riposte / Instant Kill / Force Hit / Assassinate / Enemy Surge payloads that survived compatibility checks. `ModTotalInfoOverlay` is removed; `ModLastStandTag` is part of the guaranteed narrow Last Stand core.
- Full injection may use the existing SMM menu and the Riposte `Char.attack()` hook. APK and JAR both select the unique terminal `Char.attack()` overload. Instant Kill patches successful boolean returns and adds the Force Hit combination guard at attack entry; it never traces a hit-success branch. Parry and Force Hit share one selected hit-check helper, with Force Hit evaluated first. Riposte uses the stable `attack(Char)` wrapper and therefore keeps normal hit rules unless Force Hit is enabled. Minimal injection must not install the full menu, but may install the narrow Parry/Riposte / Instant Kill / Force Hit hooks described above.
- Save transfer must not hard-link desktop file APIs that vary across SPD generations. Resolve `FileUtils.getFileHandle(...)` / legacy `FileUtils.getDir(...)` and the backing `File` at runtime so an unused desktop path cannot make an APK payload fail compatibility validation.
- A Debug Console command can expose bugs already present in the target game. Do not patch unrelated target gameplay bugs merely to make a command appear successful.

## Current naming

The assassin buff class is `ModAssassinate`. The old `ModAssassinBuff` class is removed; no compatibility alias is kept.

## Validation

Representative APK/JAR tests are useful, but successful static injection does not guarantee every fork-specific runtime path. Required core ABI that cannot be adapted safely should fail closed. Optional features should first attempt safe structural adaptation and, when available, a documented fallback; only that optional feature should be skipped when neither path is safe.
