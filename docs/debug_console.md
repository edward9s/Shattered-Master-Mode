# SMM Debug Console Guide

[正體中文版](debug_console.zh-TW.md)

The SMM Debug Console is an in-game reflection and editing tool for Shattered Pixel Dungeon-derived builds. It is intended for testing, inspection, quick experiments, and save debugging rather than normal gameplay.

The console is part of SMM's debug payload and is **not tied to ModAnkh as its implementation**. The exact UI entry point depends on how SMM is integrated into the target build. **ModAnkh -> Console** is one supported entry point and is the important one for `--ankh-only` injection; other/full integrations can expose the same console separately.

> The exact classes, fields, and methods available depend on the target SPD fork and version. Commands that refer to game internals can fail when a target uses a different API.

## In-game `help`

Use `help` as the authoritative command index for the build you are actually running:

```text
help
help give
help spawn
help affect
help terrain
help @
help !!
help search
help fuzzy
```

`help <topic>` shows focused help for one command/topic. This document explains the common workflow, but the in-game help should be preferred when a newer build changes syntax.

## Core model

The four most useful reflection operations are:

```text
inspect @x
get @x field
set @x field value
use @x method args...
```

- `inspect` lists fields and methods.
- `get` reads a field.
- `set` writes a field.
- `use` invokes a method.

`get/set` do not fall back to methods, and `use` does not fall back to fields.

## Handles: `@name`

Handles give temporary names to live Java objects or values:

```text
@
@item inv
@cell cell
@mob char
@obj obj
@hero hero
@level level
@item
@item clear
```

Common forms:

- `@` — list handles.
- `@item inv` — select an inventory Item.
- `@cell cell` — select a map cell and store its integer cell number.
- `@mob char` — select and store a live `Char`.
- `@obj obj` — select an object at a cell, falling back to the cell number when no object is found.
- `@hero hero` — store the current Hero.
- `@level level` — store the current Level.
- `@item` — show a handle.
- `@item clear` — delete a handle.

Handles are process-local strong references. They are not saved. A floor change does not automatically clear them, so a handle can become stale after the underlying object leaves the active game state.

Many commands can store their result directly:

```text
@item give PotionOfHealing
@rat spawn Rat
@buff affect Haste
@blob seed Fire 10
@trap trap AlarmTrap
@child get @object someField
@result use @object someMethod
```

## Inspecting classes and objects

```text
inspect @item
inspect hero
inspect level
inspect RingOfEnergy
inspect @hero buff
inspect @mob attk
```

An optional query filters field and method names. Matching is case-insensitive and supports exact, prefix, substring, and fuzzy subsequence matching.

Class operands used by commands such as `give`, `spawn`, `affect`, `seed`, `trap`, `inspect`, and `use` also support fuzzy assistance. Ambiguous matches are not executed; the console prints `Similar:` candidates instead.

## Reading and writing fields

```text
get @item quantity
get @hero HP
set @item quantity 99
set @hero HP 100
set @object enabled true
set @object ratio 1.5
set @object target @rat
set @object optionalField null
```

Field lookup walks superclasses and can access non-public fields through reflection. Values are converted according to the actual Java field type; incompatible writes fail instead of being guessed.

A returned object can be stored in another handle:

```text
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

## Calling methods with `use`

```text
use @item quantity 99
use @item upgrade
use @rat beckon 123
use hero someMethod
use level someMethod
use SomeClass staticMethod 10
```

Syntax:

```text
use <Class|hero|level|@handle> <method> [args...]
```

Arguments are converted to the method's Java parameter types. Quoted strings, handles, and explicit `new:<Class>` construction are supported:

```text
use @object rename "test object"
use @object setTarget @rat
use @weapon enchant new:Grim
```

## Weapon enchantments and armor glyphs

```text
@weapon inv
@armor inv

enchant @weapon Grim
enchant @weapon random
enchant @weapon none

inscribe @armor Brimstone
inscribe @armor random
inscribe @armor none
```

`random` uses the target game's normal zero-argument operation. `none`/`null` clears the effect. Named classes must be compatible `Weapon.Enchantment` or `Armor.Glyph` subclasses.

## Creating items: `give`

```text
give PotionOfHealing
give ScrollOfUpgrade x10
give Longsword +10
give PotionOfHealing x10 --force
```

Syntax:

```text
give <Item> [+level] [xquantity] [-f|--force] [method [args...]]
```

`--force` uses direct collection instead of normal pickup logic. An optional method can be called on each generated item before pickup.

## Spawning mobs: `spawn`

```text
spawn Rat
spawn Rat 123
spawn Rat @cell
spawn Rat x1
spawn Rat x10
@rat spawn Rat
```

Syntax:

```text
spawn <Mob> [cell|@variable|xquantity] [method [args...]]
```

- A single Mob with no explicit location opens the cell selector.
- A cell number or numeric handle places it immediately.
- `xN` uses automatic valid respawn cells; `x1` is useful for non-interactive macros.
- Manual/explicit placement still keeps normal Mob placement safety checks. This is intentionally stricter than `warp`.

Special Mob classes may receive additional debug initialization when a bare constructor is not sufficient.

## Applying buffs: `affect`

```text
affect Haste
affect Haste 20
@buff affect Haste 20
```

Syntax:

```text
affect <Buff> [duration] [method [args...]]
```

After entering the command, select a character. `affect` now behaves as a **toggle for the exact Buff class**:

- if that exact Buff class is already present, it is detached;
- otherwise it is applied;
- duration/method options are used only when applying.

This makes repeated debug use predictable and matches the Journal Buff toggle model.

Duration handling depends on the target Buff implementation. Simple duration APIs can be handled directly; unusual fork-specific Buffs may require their own initialization method or may retain their normal default duration rather than having internal state guessed unsafely.

Injected builds can also include SMM support Buffs/classes carried by the injection payload. Availability depends on the selected injection mode and target compatibility.

## Blobs: `seed`

```text
seed Fire
seed Fire 20
@gas seed ToxicGas 100
```

Syntax:

```text
seed <Blob> [amount]
```

Select a target cell after entering the command. `amount` defaults to `1` and is passed to the target game's blob seeding API; its precise meaning is defined by the Blob class and should not be assumed to be duration.

After the Blob is seeded and added to the scene, the current console implementation immediately invokes the Blob's `act()` once. This is intentional so effects such as fire/gases can affect the selected cell immediately instead of waiting for the next game turn.

A handle prefix stores the created Blob instance.

## Traps: `trap`

```text
trap AlarmTrap
trap alarm
@trap trap RockfallTrap
```

`trap` constructs the Trap, assigns its cell, reveals it, installs it into the current Level, changes the tile to `Terrain.TRAP`, and refreshes map/visibility state.

## Terrain: `terrain`

```text
terrain LOCKED_DOOR
terrain CHASM
terrain WATER
terrain WALL
terrain CHASM @cell
terrain WALL 123
```

Syntax:

```text
terrain <Terrain|id> [cell|@variable]
```

Names are case-insensitive and support fuzzy matching. A raw numeric terrain ID can be used when a fork-specific name is unavailable or has been removed by R8.

`terrain` calls the target Level's terrain setter, then refreshes the map, observation, and fog. Ordinary terrain therefore updates immediately. Features that require extra objects/state—such as traps, transitions, scripted gates, or special rooms—still need their own supporting state.

## Movement

Same-floor debug teleport:

```text
warp
warp 123
warp @cell
```

`warp` deliberately allows unusual in-map terrain such as walls or pits for debugging, while still rejecting invalid cells or cells occupied by another character.

Floor/branch transition:

```text
goto 10
goto 10 0
where
```

`goto <depth> [branch]` uses the target game's interlevel transition machinery. `where` shows the current depth and branch.

## Numeric value search

Value Search is separate from object-field `get/set`. Search results use `#id`; object handles use `@name`.

```text
search 100
results
search changed
search increased
search decreased
search unchanged
search 80
results #12
get #12
set #12 999
clear
```

Searches scan reachable game-model objects and numeric fields with traversal limits. Results are session-local and can become stale when their owner objects disappear.

## Macros

```text
macro
macro test
```

- `macro` lists saved macros.
- `macro name` opens the editor for that macro.
- Put one debug command per line.
- Blank lines and lines beginning with `#` are ignored.
- `%1` through `%9` are positional arguments.
- Saving an empty macro deletes it.

Example macro body:

```text
give PotionOfHealing x%1
warp %2
```

Run it with:

```text
test 10 123
```

Macros can call other macros up to the implementation's recursion limit. A command that opens an interactive selector must be the final command unless an explicit cell/handle prevents the selector from opening.

Macros persist separately from normal game saves. On Android they use app-private storage; clearing app data or uninstalling removes them.

## Previous-command history: `!!`

```text
use @weapon upgrade
!!
!! 100
```

- `!!` executes the previous command one additional time.
- `!! N` executes it N additional times, up to the in-game limit.
- Batch replay rejects commands/macros that would open multiple interactive selectors.

Top-level inline ScrollOfDebug-style expansion also remains available when `!!` is embedded in other command text:

```text
give PotionOfHealing
!! x10

give Longsword
!! +10
```

## Save transfer

```text
save
load
```

On Android, `save` exports app save files to `Download/<package>` and `load` imports them back before restarting the app. Storage behavior and permissions vary by Android version and target package.

## Important limitations

- Handles are in-memory references and can become stale.
- Reflection can bypass game invariants; a type-correct value can still create an impossible game state.
- Bosses, scripted NPCs, special floors, and fork-specific classes may depend on hidden state that a generic debug command cannot reconstruct.
- Class/member names and APIs differ across SPD versions and forks. Use `inspect` and in-game `help` instead of assuming another build's API is identical.
- `warp` deliberately allows unusual terrain; manual/explicit `spawn` placement deliberately retains normal Mob placement checks.

Use a disposable save or export a backup before destructive experiments.

## Acknowledgements

SMM's Debug Console was inspired in part by [Zrp200's ScrollOfDebug](https://github.com/Zrp200/ScrollOfDebug), whose reflection-driven command interface and in-game debugging tools provided important inspiration for this style of developer tooling. Thanks to Zrp200 and the ScrollOfDebug contributors for their work and contributions.
