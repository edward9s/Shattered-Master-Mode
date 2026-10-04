# Shattered-Master-Mode

**In-game Editor** for [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon).

SMM is mainly for sandbox testing, debugging, quick experiments, and creating unusual game situations without manually editing saves.

## Core Features

- **In-game editing:** Spawn enemies and items, change terrain, apply buffs, inspect objects, and modify game state.
- **Debug Console:** Run commands for testing, inspection, spawning, teleporting, save transfer, and other developer tasks.
- **Optional combat buffs:** Parry/Riposte, Instant Kill, Force Hit, Assassinate, Enemy Surge, and Last Stand.
- **Preserves vanilla behavior:** SMM only changes gameplay when an SMM feature is actively used.
- **Save transfer:** Export and import full save snapshots under `Documents/spd_saves/<app name>/` on Android and Desktop.

## Known Limitations & Warnings

- **Boss Floor Binding (High Crash Risk):** Bosses with multi-stage transformations, such as Tengu and DM-300, are closely tied to their own floors. Spawning them elsewhere may crash the game.
- **Event NPC Spawning:** Spawning event characters such as the Troll Blacksmith outside their normal quest flow will not automatically advance the related quest.
- **Special Rooms:** Some rooms and terrain depend on hidden generation state. Creating only the visible terrain may not reproduce the original room behavior.

> [!CAUTION]
> **Mod Items and Buffs Save Upgrade Warning**
>
> Mod items and Mod buffs are development aids, not normal game content. If a future SMM update changes their internal structure, old saves containing them may fail to load.
>
> - **Mod Items:** When a release specifically requires it, use **Tools -> Alchemize** to remove all Mod Items before upgrading.
> - **Mod Buffs:** When a release specifically requires it, use **Tools -> Journal -> Buff** and toggle the affected Mod Buff off before upgrading.

## Build from source

SMM is an overlay for Shattered Pixel Dungeon rather than a standalone game. You need Git, Python 3, JDK 17, and the Android SDK.

```bash
git clone https://github.com/edward9s/Shattered-Master-Mode.git mod
git clone https://github.com/00-Evan/shattered-pixel-dungeon.git spd_src

python mod/scripts/rebase_source.py spd_src mod
python mod/scripts/patch_android.py spd_src
python mod/scripts/inject_mod.py spd_src
cp -a mod/core spd_src/

cd spd_src
./gradlew android:assembleDebug :desktop:release
```

The build scripts adapt SMM to the target SPD-family source tree and stop when the target is not compatible enough to patch safely.

The Android APK is produced under `android/build/outputs/apk/`, and the Desktop JAR under `desktop/build/libs/`.

## Binary injection

Use the `SMM-m<version>-InjectKit.zip` release artifact to add SMM to an already-built SPD-derived APK or JAR.

### Full SMM

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

Default outputs:

```text
TARGET-SMM.apk
TARGET-SMM.jar
```

### Minimal injection

For older or heavily modified forks:

```bash
python inject_apk.py TARGET.apk --ankh-only
python inject_jar.py TARGET.jar --ankh-only
```

`--ankh-only` always includes the **ModAnkh + ModLastStand** core, including Store, Loot, Debug Console, and the Last Stand Tag.

When the target is compatible, it also adds these optional buffs:

- Parry/Riposte
- Instant Kill
- Force Hit
- Assassinate
- Enemy Surge

If one of those optional features is incompatible with the target fork, the injector skips that feature instead of failing the entire minimal injection.

Configurable SMM buff icons can be tapped to open their settings. Long-press keeps the game's normal buff information behavior.

Default outputs:

```text
TARGET-SMM-Ankh.apk
TARGET-SMM-Ankh.jar
```

Use `--out` to choose another output path.

### APK signing

The APK injector creates `smm-inject.keystore` the first time it signs an injected APK.

Keep that file if you want future injected APKs with the same package name to update an already-installed injected version. A different signing key normally requires uninstalling the old APK first.

When moving to a newly extracted Injection Kit, copy your existing `smm-inject.keystore` into the new kit directory if signature continuity matters.

Do not commit the keystore to GitHub.

### Injection details

Implementation details, compatibility rules, hook selection, and target ABI behavior are documented separately:

[Binary injection rules](docs/smm_injection_rules.md) | [正體中文](docs/smm_injection_rules.zh-TW.md)

Debug Console documentation:

[English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
