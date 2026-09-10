# Shattered-Master-Mode

**In-game Editor** (in-game sandbox tester and editor) for [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon).

## Core Features

- Designed for in-game sandbox testing, rapid editing, and easily creating meme images.
- **Preserves Vanilla Mechanics:** SMM does not replace or rewrite vanilla gameplay mechanics. Vanilla behavior remains unchanged unless an SMM feature is explicitly enabled.
- **Minimal Integration:** SMM uses existing vanilla extension points whenever possible. The only gameplay-level invasive hook is `Char.attack()`, used by Riposte; no additional gameplay hooks should be added.

## Known Limitations & Warnings

- **Boss Floor Binding (High Crash Risk):** Bosses with multi-stage transformations (for example Tengu and DM-300) have scripts deeply bound to their specific floors. Forcing them to spawn on non-designated floors may immediately crash the game.
- **Event NPC Spawning:** Spawning event characters such as the Troll Blacksmith on non-quest floors will not advance their quests or trigger the corresponding events.
- **Special Rooms Disabled:** Special rooms such as Sacrificial Fire rely on global generation mechanics. The editor currently does not support manually spawning special terrains or rooms with fully functioning logic.

> [!CAUTION]
> **Mod Items and Buffs Save Upgrade Warning**
>
> Mod items and Mod buffs are development aids and not official in-game items. If a future SMM update changes their underlying structure, old saves containing them may fail to load.
>
> - **Mod Items:** Use **Tools -> Alchemize** to completely remove all Mod Items from your inventory and the map only when the new version explicitly changes Mod Items.
> - **Mod Buffs:** If a new version explicitly changes Mod Buffs, use **Tools -> Journal -> Buff**, select the Mod Buff, and then select the character currently carrying it. Buff entries toggle attach/detach, so selecting a character that already has the buff removes it.

## Build from source

SMM is an overlay for Shattered Pixel Dungeon rather than a standalone project. You need Git, Python 3, JDK 17, and the Android SDK.

```bash
git clone https://github.com/edward9s/Shattered-Master-Mode.git mod
git clone https://github.com/00-Evan/shattered-pixel-dungeon.git spd_src

python mod/scripts/patch_android.py
python mod/scripts/inject_mod.py spd_src/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndGame.java
cp -a mod/core spd_src/

cd spd_src
./gradlew android:assembleDebug :desktop:release
```

The Android APK is produced under `android/build/outputs/apk/`, and the desktop JAR under `desktop/build/libs/`.

## Binary injection for prebuilt APK/JAR files

Binary injection is a **fallback method** for SPD-derived builds whose source is unavailable or cannot reasonably be rebuilt. If compatible source is available, source-level integration and rebuilding remain the preferred and more reliable approach.

The injector works against already-compiled classes and ABI. SPD forks may rename, remove, or change classes, methods, fields, resources, inheritance relationships, or behavior. SMM includes compatibility checks and several runtime/structural adapters, but **successful injection does not guarantee that every target-specific runtime path is compatible**.

Use the `SMM-m<version>-InjectKit.zip` artifact produced by **Build SMM Injection Kit**. Keep all files from the kit together; APK injection uses the included non-minified donor.

### Full SMM injection

Full injection is the default mode and attempts to install the complete supported SMM payload.

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

Default outputs:

```text
TARGET-SMM.apk
TARGET-SMM.jar
```

Use `--out` to choose another output path.

Full injection validates the target ABI before rebuilding. If the target is too old or has diverged too far from the donor ABI, the injector fails rather than silently removing features or producing a knowingly incomplete build.

### ModAnkh-only APK injection

For older or heavily diverged SPD forks that cannot accept the full SMM payload, APK injection also provides a narrower compatibility mode:

```bash
python inject_apk.py TARGET.apk --ankh-only
```

`--ankh-only` injects only the ModAnkh dependency closure required for:

- **ModAnkh**
- **Store**
- **Loot**
- **Console**
- Small helper classes required by those features

It intentionally does **not** inject unrelated full-SMM features such as Journal, Assassin, Force Hit, Riposte, Enemy Surge, Last Stand, or their gameplay hooks.

The default output is:

```text
TARGET-SMM-Ankh.apk
```

You can still use `--out`:

```bash
python inject_apk.py TARGET.apk --ankh-only --out TARGET-ModAnkh.apk
```

Ankh-only mode uses its own reduced ABI checks and does not install full-SMM combat/menu hooks that the reduced payload does not need. It can also adapt several known legacy SPD ABI differences used by Store, Loot, and Console. This makes it useful for forks where the basic Item/Ankh/inventory infrastructure is compatible but newer SMM UI or combat APIs are absent.

`--ankh-only` is **not an automatic fallback**. Running `python inject_apk.py TARGET.apk` always means full SMM injection. If full injection is incompatible, rerun explicitly with `--ankh-only` when the reduced feature set is acceptable.

Ankh-only is currently an **APK mode only**; `inject_jar.py` does not provide an equivalent `--ankh-only` option.

### Compatibility notes

Binary injection should fail closed when a required ABI cannot be resolved reliably. Do not bypass compatibility errors simply to force an APK to build: unresolved symbolic references can survive packaging and fail later as `NoSuchMethodError`, `NoSuchFieldError`, `NoClassDefFoundError`, or `IncompatibleClassChangeError` at runtime.

Older forks may expose methods with the same Java-level purpose but different bytecode descriptors, or may represent a type as a class in one generation and an interface in another. The injector handles known cases where an equivalent adaptation can be made safely, but target-specific behavior can still require additional compatibility work.

If a source change modifies Java classes included in the injection payload, rebuild the Injection Kit so that `smm-inject-donor.apk` matches the current source. Changes that only modify injector Python code do not require rebuilding the donor.

See [Binary injection rules](docs/smm_injection_rules.md) | [正體中文](docs/smm_injection_rules.zh-TW.md).

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
