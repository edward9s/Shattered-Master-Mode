# SMM Debug Console Guide

[正體中文版](debug_console.zh-TW.md)

The SMM debug console is an in-game reflection and editing tool for Shattered Pixel Dungeon-derived builds. It is intended for testing, inspection, quick experiments, and save debugging—not for normal gameplay.

The console is exposed through **ModAnkh**. Use the ModAnkh, choose **Console**, then enter one command at a time.

> The exact classes, fields, and methods available depend on the target SPD fork and version. Commands that refer to game internals can fail when a target uses a different API.

## In-game `help`

Bare `help` shows a complete command index grouped by purpose. Use `help <topic>` for focused help; only that command/topic is shown instead of mixing in unrelated commands.

```text
help
help give
help terrain
help @
help !!
help search
help fuzzy
```

`help @` covers handle creation, inspection, deletion, and result capture. `help !!` covers history replay and inline `!!`. `help search` / `help results` / `help clear` show the Value Search group. An unknown topic only reports that it is unknown and points back to the main `help` index.

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
spawn Rat 123
spawn Rat @cell
spawn Rat x5
@rat spawn Rat
```

Syntax:

```text
spawn <Mob> [cell|@variable|xquantity] [method [args...]]
```

- A single Mob opens the map selector by default; this is the normal interactive form.
- A cell number or numeric `@handle` places the Mob there immediately without opening the selector.
- `xN` uses automatic placement and chooses normal valid respawn cells for each Mob. `x1` is the non-interactive single-Mob form, which is useful in macros.
- An optional method can be called on the newly spawned Mob after its normal debug initialization.

Examples:

```text
@cell cell
spawn Rat @cell
@bee spawn Bee 123
spawn Rat x1
spawn Rat x10
```

Direct/manual Mob placement still follows normal placement safety rules. This is intentionally stricter than `warp`.

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

### Seed a blob: `seed`

`seed` creates a `Blob` on a selected map cell, such as fire, toxic gas, paralysis gas, or another area effect that persists on map cells.

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

Syntax:

```text
seed <Blob> [amount]
```

Select a tile after entering the command. `amount` defaults to `1` and is passed to the target game's `Blob.seed(cell, amount, class)`. It commonly represents initial volume/intensity, but the exact meaning is defined by each Blob class and should not be assumed to mean duration. Blob class names support fuzzy matching as well; for example, `seed toxgas 100` uses the unique best compatible Blob match when one exists.

A handle prefix stores the actual created Blob instance:

```text
@gas seed ToxicGas 100
inspect @gas
```

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
terrain <Terrain|id> [cell|@variable]
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

`chsm` can resolve to `CHASM`, while `lockdoor` can resolve to `LOCKED_DOOR`. If the best match is ambiguous, the console prints `Similar:` candidates and leaves the map unchanged. Names should normally be preferred because they are easier to read and maintain across versions.

If a name does not exist in the target fork, or R8 has removed the field name completely, the target fork's raw terrain ID can be supplied directly:

```text
terrain 0
terrain 123 @cell
```

A purely numeric first argument is treated directly as a terrain ID and bypasses name/fuzzy resolution. The console validates it against the target `Terrain.flags` array length; standard SPD currently has 256 entries, so valid IDs are `0..255`. This lets fork-specific terrain remain usable when only its numeric value is known. Raw IDs are target/fork-specific and must not be assumed to have the same meaning across different forks.

Internally, `terrain` calls the target game's `Level.set(cell, terrain)`, so passable/solid/pit and related flags are updated. It then refreshes the map, recalculates observation, and updates fog.

Android release R8 can remove `Terrain` `public static final int` fields that are only compile-time constants. ModDebug first uses Terrain fields that still exist in the target APK at runtime; if a standard SPD terrain field was shrunk away, it falls back to the canonical Terrain ID from SMM's upstream baseline. This keeps commands such as `terrain chasm` working in minified/injected APKs. A fork-specific custom terrain name whose field name was completely removed by R8 cannot be reconstructed from the APK; if that fork's actual terrain ID is known, use the numeric form instead.

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
- Blank lines and lines beginning with `#` are ignored and can be used as comments.
- `%1` through `%9` are positional arguments.
- Saving an empty macro deletes it.

For example, create a macro named `test` with this body:

```text
give PotionOfHealing x%1
warp %2
```

Then run:

```text
test 10 123
```

which executes:

```text
give PotionOfHealing x10
warp 123
```

Macros can call other macros, up to 8 nested levels. A command that opens an interactive selector must be the final command in a macro because selector completion is asynchronous. For example, `terrain CHASM` opens a selector and must be last, while `terrain CHASM @cell` already has an explicit cell and may appear earlier.

Macros also support standalone `!!` / `!! N` with macro-local history; see the next section for the exact rules.

Macros are persisted separately from normal game saves.

## Repeat the previous command: `!!`

`!!` is history syntax; there is no separate `repeat` command:

```text
use @weapon upgrade
!!
!! 100
```

- `!!` executes the previous command one additional time.
- `!! N` executes the previous command N additional times; N may be from 1 to 1000, so `!! 1` is equivalent to `!!`.
- `!! N` does not itself replace the previous-command entry. Consecutive `!! 100` and `!! 10` therefore still repeat the same real command.

If the previous top-level command is a macro, for example:

```text
prepareBoss
!! 10
```

then the whole `prepareBoss` macro is executed 10 additional times, not merely its final line.

Inside a macro, a standalone `!!` / `!! N` uses **that macro invocation's own previous effective command**, never the Console's outer history. For example, this macro body:

```text
use @weapon upgrade
!! 100
```

executes `use @weapon upgrade` 100 additional times. Blank lines and `#` comments do not affect macro-local history. A macro whose first effective line is `!!` fails because it has no local previous command. If the previous macro line invokes another macro, the whole nested macro is repeated.

Batch history replay will not open a large number of interactive selectors. When N > 1, `!! N` rejects a previous command (or macro) that opens a selector. For example:

```text
terrain CHASM
!! 100
```

is rejected, while an explicit-cell form can be replayed in a batch:

```text
@cell cell
terrain CHASM @cell
!! 100
```

A single `!!` / `!! 1` may still rerun a previous command that opens a selector.

For backward compatibility, when `!!` is not a complete history-command line and instead appears inside other top-level command text, the original ScrollOfDebug-style inline textual expansion remains available. The `!!` token is replaced by the previous top-level command text before the resulting command is parsed. For example:

```text
give PotionOfHealing
!! x10
# expands to: give PotionOfHealing x10

give Longsword
!! +10
# expands to: give Longsword +10
```

This is deliberately different from batch replay. `!! 10` is a complete history command and means "execute the previous command 10 additional times"; `!! x10` and `!! +10` do not match the batch form, so they perform textual expansion and append `x10` / `+10` to the previous command. Inline expansion is only a top-level Console feature; macro-local history recognizes only standalone `!!` / `!! N` lines.

Some commands already have a direct quantity form and should still prefer it:

```text
give PotionOfHealing x100
spawn Rat x100
```

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
