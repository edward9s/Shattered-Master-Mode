# SMM Mod UI Rules

These rules keep Master Mode UI elements visually distinct from vanilla SPD while preserving binary-injection compatibility across supported forks.

## Reusing vanilla buff icons

- A Mod buff may reuse a vanilla `BuffIndicator` icon when the shape is semantically appropriate.
- Reused vanilla icons must be visually distinguishable from their vanilla use, normally by overriding `tintIcon()`.
- Prefer a tint outside the full color range that the vanilla owner of that icon normally displays.
- Permanent Mod buffs should also remain clearly distinguishable from each other by icon shape, hue, or both.
- `iconTextDisplay()` is a secondary identifier and must not be the only distinction when a reused icon would otherwise look like vanilla.
- If tinting cannot provide reliable distinction, prefer a different icon only when doing so is injection-safe.

## Injection compatibility

- Do not introduce a new target-owned `BuffIndicator.*` constant solely for appearance unless its compiled integer index is known to be stable across every supported injection target.
- `public static final` primitive constants are compile-time inlined by `javac`; after compilation the injector cannot validate that the symbolic field still exists or maps to the same icon in another fork.
- When cross-fork stability is uncertain, keep an icon index already exercised by the supported payload and distinguish the Mod buff with tint and/or text instead.
- Visual-only changes must not add unnecessary target API dependencies.

## Current permanent Mod buff palette

- Assassin: `PREPARATION`, purple `0xB06CFF`.
- Loot: `AMULET`, cool silver-blue `0xDDEEFF`.
- Last Stand: `BERSERK`, pale warm gold `0xFFF0A8` (icon retained for injection compatibility).
- Parry: `DUEL_GUARD`, cyan `0x55CCFF`.
- Riposte: `DUEL_CLEAVE`, pink-red `0xFF5577`.
- Instant Kill: `DUEL_CLEAVE`, vivid green `0x66FF33` plus `K` text.
