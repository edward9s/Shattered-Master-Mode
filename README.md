# Shattered-Master-Mode
**In-game Editor** (In-game sandbox tester and editor) for [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon).

**Core Features**
 * Designed for in-game sandbox testing, rapid editing, and easily creating meme images.
 * **Preserves Vanilla Mechanics:** This editor strictly does not alter any underlying logic of the official vanilla game.

**⚠️ Known Limitations & Warnings**
 * **Boss Floor Binding (High Crash Risk):** Bosses with multi-stage transformations (e.g., Tengu, DM-300) have their scripts deeply bound to their specific floors. Forcing them to spawn on non-designated floors will immediately crash the game.
 * **Event NPC Spawning:** Spawning event characters (e.g., Troll Blacksmith) on non-quest floors will not advance their quests or trigger events (at most, you can grab an early pickaxe).
 * **Special Rooms Disabled:** Special rooms (e.g., Sacrificial Fire) rely on global generation mechanics. The editor currently does not support manually spawning special terrains or rooms with fully functioning logic.

**⚠️ Mod Items Save Upgrade Warning**
Mod items are development aids and not official in-game items. If future updates modify the underlying structure of Mod Items, old save files containing them will fail to load.
 * **Proper Upgrade Procedure:** You **only** need to use the **Tools -> Alchemize** tool to completely remove all Mod Items from your inventory and the map **IF** the new version explicitly modifies Mod Items.

## Additional utilities

The binary injectors are a small extra tool for compatible SPD-derived builds. They inject only ModAnkh, ModAnkhStore, and the Debug Console payload; they do **not** turn another fork into a full Shattered-Master-Mode build.

```bash
python scripts/inject_apk.py <current-smm.apk> <target.apk> [--out output.apk]
python scripts/inject_jar.py <current-smm.jar> <target.jar> [--out output.jar]
```

Use a current SMM build as the donor and the SPD-derived APK/JAR you want to modify as the target. Run either script with `--help` for its additional options.

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)
