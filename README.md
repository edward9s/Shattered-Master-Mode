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

SMM can be injected into compatible SPD-derived APK/JAR builds without rebuilding the target from source. The target remains the base artifact; the injector adds the compiled `com.spd.mod` payload, rebases SPD package references when necessary, validates compatible target APIs, and patches the target `WndGame` menu so SMM opens through the normal game menu.

Use the `SMM-m<version>-InjectKit.zip` artifact produced by **Build SMM Injection Kit**. The kit contains dedicated donor binaries and the public injector scripts:

```bash
python inject_apk.py smm-inject-donor.apk TARGET.apk --out TARGET-SMM.apk
python inject_jar.py smm-inject-donor.jar TARGET.jar --out TARGET-SMM.jar
```

`inject_apk.py` and `inject_jar.py` are the only public injection entry points. Their `_inject_apk_core.py` and `_inject_jar_core.py` files are internal implementation modules and must stay beside the public scripts.

The APK donor is intentionally a **non-minified debug APK**. Do not replace it with the normal release APK: R8 may outline or rebind SMM bytecode into donor-only obfuscated helpers that are unsafe to transplant. The donor's SPD source version is only a build baseline; the InjectKit is versioned by SMM itself, e.g. `SMM-m0.3.0-InjectKit.zip`.

See [Binary injection rules](docs/modankh_payload_rules.md) | [正體中文](docs/modankh_payload_rules.zh-TW.md).

Debug Console documentation: [English](docs/debug_console.md) | [正體中文](docs/debug_console.zh-TW.md)

## Acknowledgements

Shattered-Master-Mode is built on [Shattered Pixel Dungeon](https://github.com/00-Evan/shattered-pixel-dungeon). Thanks to Evan Debenham and all Shattered Pixel Dungeon contributors for the game and source code that make this project possible.

Thanks also to the authors and contributors of other Shattered Pixel Dungeon-derived projects. Their ideas, experiments, fixes, and shared work have helped the wider SPD modding ecosystem.
