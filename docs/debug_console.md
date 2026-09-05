# SMM Debug Console Guide

[正體中文版](debug_console.zh-TW.md)

The SMM debug console is an in-game reflection and editing tool for Shattered Pixel Dungeon-derived builds. It is intended for testing, inspection, quick experiments, and save debugging—not for normal gameplay.

The console is exposed through **ModAnkh**. Use the ModAnkh, choose **Console**, then enter one command at a time.

> The exact classes, fields, and methods available depend on the target SPD fork and version. Commands that refer to game internals can fail when a target uses a different API.

## The basic model

There are four ideas worth learning first:

```text
inspect @x
get @x field
set @x field value
use @x method args...
```

- `inspect` shows the fields and methods of an object or class.
- `get` reads a field directly.
- `set` writes a field directly.
- `use` calls a method.

`get/set` never fall back to methods, and `use` never falls back to fields. This makes commands predictable even when a class has both a field and a method with similar names.

## Handles: `@name`

A handle gives a temporary name to a live Java object or value. Handles exist only for the current game process; they are not stored in the save file.

### Create or inspect a handle

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

What they do:

- `@` — list all handles.
- `@item inv` — open the inventory selector and store the selected Item.
- `@cell cell` — select a map cell and store its integer cell number.
- `@mob char` — select a cell containing a character and store that exact live `Char` object.
- `@obj obj` — select a cell and try to store the character or another object at that cell; if no object is found, the cell number is stored.
- `@hero hero` — store the current Hero object.
- `@level level` — store the current Level object.
- `@item` — display the current handle value.
- `@item clear` — delete the handle.

A handle is especially useful when you need to operate on one specific live instance. For example, `use Rat ...` may operate on a class/new instance, while `use @rat ...` operates on the exact rat you selected on the map.

Handles can also capture objects returned by commands:

```text
@item give PotionOfHealing
@rat spawn Rat -p
@buff affect Haste
@blob seed Fire 10
@trap trap AlarmTrap
@child get @object someField
@result use @object someMethod
```

A result is stored only when the command actually returns a non-null object.

## Inspecting objects and classes

```text
inspect @item
inspect hero
inspect level
inspect RingOfEnergy
```

`inspect` lists fields and methods found on the target, including inherited members. This is normally the best first step when you do not know the exact field or method name.

You can also add a query to filter both field and method names:

```text
inspect @item quan
inspect @hero buff
inspect @mob attk
```

The query is case-insensitive. Matches are ordered by quality: exact match, prefix match, substring match, then fuzzy subsequence match. Fuzzy matching only requires the query characters to appear in order, so `attk` can match names such as `attack`. Results are not capped; if a query is too broad, enter a more specific one.

Without a query, `inspect` keeps its normal full-list behavior.

Class names may be simple names such as `Rat` or `RingOfEnergy`, or fully qualified Java class names.

### Fuzzy class-name assistance

Interactive class operands in `give`, `spawn`, `affect`, `seed`, `trap`, `inspect`, and `use` also accept case-insensitive fuzzy names. Exact names are still preferred. If exact resolution fails, the console tries a unique prefix match, then a unique substring match, then a fuzzy subsequence match.

For example:

```text
give potheal
spawn goem
inspect ringenergy
```

If one best match is unambiguous, the console uses it and reports the chosen class, for example `Using PotionOfHealing for potheal`. If the best match is ambiguous, the command is not executed and the console prints `Similar:` suggestions instead.

Suggestions are type-aware. `give` searches only `Item` classes, `spawn` only `Mob`, `affect` only `Buff`, and `seed`/`trap` only their matching base types. `inspect` and `use` search the general class index.

## Reading and writing fields

### Read a field

```text
get @item quantity
get @hero HP
```

The field lookup walks through superclasses and can access non-public fields through reflection.

You can save a non-null field value into another handle:

```text
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

If the field value is `null`, the destination handle is not replaced.

### Write a field

```text
set @item quantity 99
set @hero HP 100
set @object enabled true
set @object ratio 1.5
set @object target @rat
set @object optionalField null
```

The value is converted according to the field's actual Java type. Supported conversions include common primitive/boxed numbers, booleans, characters, strings, enums, `null` for reference fields, and another compatible `@handle`.

A write fails instead of guessing when the value is incompatible with the field type. Some fields may also be effectively read-only or unsafe to change even if reflection can reach them.

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

`new:<Class>` resolves the requested class against the method's actual parameter type before constructing an instance. For example, `new:Grim` succeeds only where that parameter can accept a `Grim` instance.

If no compatible method exists, the command fails. `use` does **not** silently treat the name as a field.

## Weapon enchantments and armor glyphs

First store the live weapon or armor from the inventory:

```text
@weapon inv
@armor inv
```

Apply or remove a weapon enchantment:

```text
enchant @weapon Grim
enchant @weapon Vampiric
enchant @weapon random
enchant @weapon none
```

Apply or remove an armor glyph:

```text
inscribe @armor Brimstone
inscribe @armor Thorns
inscribe @armor random
inscribe @armor none
```

`random` calls the target game's normal zero-argument `enchant()` / `inscribe()` method. `none` (or `null`) clears the current effect. Named classes are restricted to compatible `Weapon.Enchantment` or `Armor.Glyph` subclasses.

The same operations can be expressed with generic `use` plus `new:<Class>`:

```text
use @weapon enchant new:Grim
use @armor inscribe new:Brimstone
```

## Creating items: `give`

```text
give PotionOfHealing
give ScrollOfUpgrade x10
give Weapon +5
give PotionOfHealing x10 --force
```

Syntax:

```text
give <Item> [+level] [xquantity] [-f|--force] [method [args...]]
```

- `+5` / `-2` sets the item's level when applicable.
- `x10` creates multiple items.
- `--force` uses direct collection instead of normal pickup logic.
- An optional method can be invoked on each generated item before pickup.

Example:

```text
give Longsword +10
@item inv
set @item quantity 2
```

For an existing stack, selecting it with `@item inv` and then using `set @item quantity 99` directly changes the quantity field. Whether an unusual value behaves sensibly still depends on the Item class.

## Spawning mobs: `spawn`

```text
spawn Rat
spawn Rat x5
spawn Rat -p
@rat spawn Rat -p
```

Syntax:

```text
spawn <Mob> [xquantity|-p|--place] [method [args...]]
```

- Without `-p`, the game chooses normal respawn cells.
- `-p` lets you manually choose one valid mob placement cell.
- An optional method can be called on the newly spawned Mob.

Manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`.

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

After the command, select the character that should receive the buff. Duration is especially useful for `FlavourBuff`-style temporary effects. The command can also invoke a compatible setup method on the created buff.

## Blobs and traps

### Seed a blob

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

Syntax:

```text
seed <Blob> [amount]
```

Select a tile after entering the command.

### Place a trap

```text
trap AlarmTrap
trap alarm
@trap trap RockfallTrap
```

Syntax:

```text
trap <Trap>
```

`trap` is already a complete dedicated placement command: it constructs the Trap instance, assigns its cell, reveals it, installs it into the current Level, and changes the tile to `Terrain.TRAP`. Trap class names use the same fuzzy resolution as other class-based commands. If the best fuzzy match is ambiguous, the console prints `Similar:` candidates and does not place anything. The map and visibility are refreshed after placement.

## Editing terrain: `terrain`

The convenient form is to name a terrain and then select a map cell:

```text
terrain LOCKED_DOOR
terrain CHASM
terrain WATER
terrain WALL
```

Syntax:

```text
terrain <Terrain> [cell|@variable]
```

Omit the cell to open the map selector, or provide a cell number / saved handle directly:

```text
@cell cell
terrain CHASM @cell
terrain WALL 123
```

Terrain names are case-insensitive and support prefix, substring, and subsequence fuzzy matching. For example:

```text
terrain chsm
terrain lockdoor
```

`chsm` can resolve to `CHASM`, while `lockdoor` can resolve to `LOCKED_DOOR`. If the best match is ambiguous, the console prints `Similar:` candidates and leaves the map unchanged. Prefer names over hard-coded numeric terrain IDs for cross-fork/version compatibility.

Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog.

For a locked-door test:

```text
terrain LOCKED_DOOR
give IronKey
```

Not every map feature is represented by terrain alone. Traps also require a live `Trap` object, so use `trap` for them. Entrances/exits normally involve `LevelTransition`, and scripted gates or special rooms may carry additional state.

## Movement

### `warp`: move anywhere on the current map

```text
warp
warp 123
warp @cell
```

`warp` is a same-floor debug teleport. With no argument it opens a cell selector.

Unlike the normal Scroll of Teleportation, `warp` does not require the destination terrain to be passable. Walls, obstacles, pits, and other in-map terrain can be entered deliberately for debugging. It still rejects cells outside the map and cells occupied by another character.

### `goto`: change floor/branch

```text
goto 10
goto 10 0
```

Syntax:

```text
goto <depth> [branch]
```

The branch defaults to `0`. This uses the target game's interlevel transition machinery and therefore depends more heavily on the target fork's internals.

### Show the current floor

```text
where
```

Shows the current depth and branch.

## Numeric value search

The value-search commands are separate from object-field `get/set`. Search results use `#id`; object handles use `@name`.

Start by searching for the current value:

```text
search 100
results
```

Change something in the game, then narrow the candidates:

```text
search changed
search increased
search decreased
search unchanged
```

You can also refine to another exact number:

```text
search 80
```

Inspect or modify a result:

```text
results #12
get #12
set #12 999
```

Clear the search session:

```text
clear
```

Typical workflow:

```text
search 20
# change the value in-game
search decreased
# change it again
search decreased
results
get #7
set #7 999
```

The search scans reachable game-model objects and numeric fields with limits to avoid unbounded traversal. Results are session-local and may expire when their owning objects disappear.

## Macros

```text
macro
macro test
```

- `macro` lists saved macros.
- `macro name` opens an editor for that macro.
- Put one debug command per line.
- `%1` through `%9` are positional arguments.
- Saving an empty macro deletes it.

Example macro body:

```text
give PotionOfHealing x%1
warp %2
```

Then run:

```text
test 10 123
```

Macros can call other macros, with a recursion limit. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous.

Macros are persisted separately from normal game saves.

## Repeat the previous command: `!!`

```text
!!
```

`!!` expands to the previous debug command. It can also appear inside a command line where repeating the previous text makes sense.

## Save transfer

```text
save
load
```

On Android:

- `save` exports the app's save files to `Download/<package>`.
- `load` imports the save files back and then restarts the app.

These commands are primarily intended for testing and transfer of development saves. Storage behavior and permissions vary by Android version and target package.

## Useful workflows

### Edit an item directly

```text
@item inv
inspect @item
get @item quantity
set @item quantity 99
use @item identify
```

### Work with one exact Mob instance

```text
@rat char
inspect @rat
get @rat HP
set @rat HP 1
use @rat someMethod
```

### Follow an object graph

```text
@hero hero
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

### Store a selected cell and warp back to it

```text
@home cell
warp @home
```

## Important limitations

- Handles are in-memory references. They are not serialized, and a handle may become stale if the underlying game object is removed or replaced.
- Reflection can bypass normal game invariants. Setting a field to a type-correct but logically impossible value can still break gameplay or a save.
- Bosses, scripted NPCs, special floors, and fork-specific classes can depend on hidden state that a debug command cannot reconstruct.
- Class and member names differ between SPD versions/forks. Use `inspect` and prefer actual target API names instead of assuming a command from another build will work.
- `warp` deliberately allows unusual terrain; `spawn -p` deliberately does not.

When experimenting with destructive field edits, use a disposable save or export it first.

## Acknowledgements

SMM's Debug Console was inspired in part by [Zrp200's ScrollOfDebug](https://github.com/Zrp200/ScrollOfDebug), whose reflection-driven command interface and in-game debugging tools provided important inspiration for this style of developer tooling. Thanks to Zrp200 and the ScrollOfDebug contributors for their work and contributions.
