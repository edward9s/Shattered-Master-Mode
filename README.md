# Shattered-Master-Mode
**In-game Editor** (In-game sandbox tester and editor) for [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon).

## Core Features
 * Designed for in-game sandbox testing, rapid editing, and easily creating meme images.
 * **Preserves Vanilla Mechanics:** This editor strictly does not alter any underlying logic of the official vanilla game.

**⚠️ Known Limitations & Warnings**
 * **Boss Floor Binding (High Crash Risk):** Bosses with multi-stage transformations (e.g., Tengu, DM-300) have their scripts deeply bound to their specific floors. Forcing them to spawn on non-designated floors will immediately crash the game.
 * **Event NPC Spawning:** Spawning event characters (e.g., Troll Blacksmith) on non-quest floors will not advance their quests or trigger events (at most, you can grab an early pickaxe).
 * **Special Rooms Disabled:** Special rooms (e.g., Sacrificial Fire) rely on global generation mechanics. The editor currently does not support manually spawning special terrains or rooms with fully functioning logic.

**⚠️ Mod Items Save Upgrade Warning**
Mod items are development aids and not official in-game items. If future updates modify the underlying structure of Mod Items, old save files containing them will fail to load.
 * **Proper Upgrade Procedure:** You **only** need to use the **Tools -> Alchemize** tool to completely remove all Mod Items from your inventory and the map **IF** the new version explicitly modifies Mod Items.

## Full SMM binary injection

Use the `SMM-m<version>-InjectKit.zip` artifact produced by **Build SMM Injection Kit**.

```bash
python inject_apk.py smm-inject-donor.apk TARGET.apk --out TARGET-SMM.apk
python inject_jar.py smm-inject-donor.jar TARGET.jar --out TARGET-SMM.jar
```

The kit includes dedicated APK/JAR donors, the public injector scripts, and their required `_inject_*_core.py` modules. Use the included non-minified APK donor for APK injection.

See [Binary injection rules](docs/smm_injection_rules.md) | [正體中文](docs/smm_injection_rules.zh-TW.md).

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
