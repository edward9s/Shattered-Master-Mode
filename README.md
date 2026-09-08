# Shattered-Master-Mode
**In-game Editor** (In-game sandbox tester and editor) for [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon).

## Core Features
 * Designed for in-game sandbox testing, rapid editing, and easily creating meme images.
 * **Preserves Vanilla Mechanics:** SMM does not replace or rewrite vanilla gameplay mechanics. Vanilla behavior remains unchanged unless an SMM feature is explicitly enabled.
 * **Minimal Integration:** SMM uses existing vanilla extension points whenever possible. The only gameplay-level invasive hook is `Char.attack()`, used by Riposte; no additional gameplay hooks should be added.

**⚠️ Known Limitations & Warnings**
 * **Boss Floor Binding (High Crash Risk):** Bosses with multi-stage transformations (e.g., Tengu, DM-300) have their scripts deeply bound to their specific floors. Forcing them to spawn on non-designated floors will immediately crash the game.
 * **Event NPC Spawning:** Spawning event characters (e.g., Troll Blacksmith) on non-quest floors will not advance their quests or trigger events (at most, you can grab an early pickaxe).
 * **Special Rooms Disabled:** Special rooms (e.g., Sacrificial Fire) rely on global generation mechanics. The editor currently does not support manually spawning special terrains or rooms with fully functioning logic.

**⚠️ Mod Items and Buffs Save Upgrade Warning (VERY IMPORTANT!)**
Mod items and Mod buffs are development aids and not official in-game items. If future updates modify the underlying structure of Mod Items, old save files containing them will fail to load.
 * **Proper Upgrade Procedure:** You **only** need to use the **Tools -> Alchemize** tool to completely remove all Mod Items from your inventory and the map **IF** the new version explicitly modifies Mod Items.
 * **Removing Mod Buffs:** If the new version explicitly modifies Mod Buffs, use **ModAnkh -> Console**, run `@buff affect <ModBuffClass>`, select the character that already has that buff, then run `use @buff detach`. The `affect` command reuses the existing buff instance when one is already present, so this safely calls the buff's normal `detach()` cleanup path.

## How to build from source

SMM is an overlay for Shattered Pixel Dungeon rather than a standalone project. You will need Git, Python 3, JDK 17, and the Android SDK to build the APK.

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

Binary injection is a **fallback method** for SPD-derived builds whose source code is unavailable or cannot reasonably be rebuilt. If compatible source code is available, integrating SMM at source level and rebuilding the project is the preferred and more reliable approach.

The injector modifies an already-built APK or JAR and therefore has to work against the target's compiled classes and ABI. Forks may rename, remove, or change classes, methods, fields, resources, or game behavior in ways the injector cannot safely infer. Compatibility checks and runtime adapters cover known variations, but **successful injection is not guaranteed**, and an APK/JAR that is accepted by the injector may still expose target-specific runtime problems that cannot be detected statically.

Use the `SMM-m<version>-InjectKit.zip` artifact produced by **Build SMM Injection Kit** only when binary injection is appropriate.

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

The default outputs are `TARGET-SMM.apk` and `TARGET-SMM.jar`. Use `--out` to choose another path.

Keep the kit files together. APK injection uses the included non-minified donor. If the injector reports an unsupported or unresolved target ABI, do not assume the target can be made compatible by forcing the injection; source-level integration is preferable when possible.

See [Binary injection rules](docs/smm_injection_rules.md) | [正體中文](docs/smm_injection_rules.zh-TW.md).

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
