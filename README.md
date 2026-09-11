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

## Binary injection

Use the `SMM-m<version>-InjectKit.zip` artifact when you need to inject SMM into an already-built SPD fork.

### Full SMM

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

Default outputs are `TARGET-SMM.apk` and `TARGET-SMM.jar`.

### Minimal injection

For older or heavily modified forks:

```bash
python inject_apk.py TARGET.apk --ankh-only
python inject_jar.py TARGET.jar --ankh-only
```

`--ankh-only` injects **ModAnkh**, **ModLastStand**, and the Store / Loot / Console support they need. It does not install the full SMM menu or unrelated combat features.

Default outputs are `TARGET-SMM-Ankh.apk` and `TARGET-SMM-Ankh.jar`. Use `--out` to choose another path.

If Java classes included in the payload change, rebuild the Injection Kit so the donor APK/JAR matches the source.

See [Binary injection rules](docs/smm_injection_rules.md) | [正體中文](docs/smm_injection_rules.zh-TW.md).

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
