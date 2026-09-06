# Shattered Master Mode Binary Injection Rules

[正體中文版](smm_injection_rules.zh-TW.md)

## Purpose

Shattered Master Mode (SMM) must work in two different environments:

1. as a normal source-built SMM game; and
2. after its compiled payload is transplanted into another compatible SPD-derived APK or desktop JAR.

Binary injection is stricter than source integration. A change may compile and run correctly in SMM while still failing after package rebasing, target-API adaptation, DEX/JAR overlaying, or execution on another SPD fork.

The public injector entry points are:

- `scripts/inject_apk.py`
- `scripts/inject_jar.py`

The `_inject_apk_core.py` and `_inject_jar_core.py` modules are private implementation modules, not alternate injection modes.

## 1. Full-SMM payload boundary

Full injection treats compiled `com.spd.mod.*` classes as the SMM payload.

Cross-references inside `com.spd.mod` are expected because the complete SMM payload is transplanted together. This does not make arbitrary donor dependencies safe.

Rules:

- `com.spd.mod.*` may depend on other `com.spd.mod.*` classes.
- Do not introduce dependencies on unrelated donor-only application classes.
- Never assume a donor-obfuscated class name has the same meaning in the target.
- Do not expand the payload to arbitrary donor dependency closure merely to silence a compatibility error.
- Prefer stable SPD APIs across forks.
- For optional or fork-sensitive APIs, use a deliberate adapter or narrow reflection/capability probing where practical.

The injectors detect the target SPD-family package root from the target class structure. References compiled against `com.shatteredpixel.shatteredpixeldungeon` are rebased to the target fork package when needed. `com.spd.mod.*` class names are never rebased.

## 2. Injection Kit and donor artifacts

The distributable kit is named by the SMM version:

```text
SMM-m<version>-InjectKit.zip
```

For example:

```text
SMM-m0.3.0-InjectKit.zip
```

The SPD version used to compile the donor is a build baseline, not the public InjectKit version. Compatibility is decided against the actual target during injection rather than by matching the kit filename to a target SPD version.

The kit contains dedicated donor binaries:

- `smm-inject-donor.apk`
- `smm-inject-donor.jar`

and the public/private injector pairs:

- `inject_apk.py`
- `_inject_apk_core.py`
- `inject_jar.py`
- `_inject_jar_core.py`

## 3. APK donor must be non-minified

The APK donor must be the dedicated **non-minified debug APK** produced by the injection-kit build workflow.

Do not substitute the normal release APK. R8/minification can rename, merge, outline, or rebind executable code into donor-only obfuscated helpers. A `com.spd.mod` class can then contain references to short obfuscated owners that do not exist, or mean something different, in the target APK.

This is why full SMM APK injection uses `android:assembleDebug` as its donor source.

The normal playable SMM APK remains a release/minified build. The debug donor exists only for binary transplantation.

## 4. Public commands

Use only these public commands from the kit:

```bash
python inject_apk.py smm-inject-donor.apk TARGET.apk --out TARGET-SMM.apk
python inject_jar.py smm-inject-donor.jar TARGET.jar --out TARGET-SMM.jar
```

There are no separate `inject_smm_apk.py` or `inject_smm_jar.py` user-facing modes. Full-SMM injection is now the default behavior of `inject_apk.py` and `inject_jar.py`.

The private core files must stay beside their public scripts because the public entry points import them.

## 5. Menu integration

Full binary injection uses the normal SMM game-menu entry instead of giving the Hero a startup ModAnkh.

Both public injectors patch the target `WndGame` no-argument constructor and insert a call to:

```text
com.spd.mod.ModGame.installInjectedMenu(Object)
```

`installInjectedMenu` locates the target's single-`RedButton` insertion method by method signature rather than by private method name. This is intentional because R8 may rename private methods.

The old `Dungeon.init()` / startup-ModAnkh hook is not the full-SMM entry path.

## 6. Target API compatibility

Do not assume every SPD-derived fork exposes the same binary API.

Rules:

- Compare compiled descriptors, not only Java source signatures.
- Treat API differences between forks as normal compatibility work.
- Prefer APIs that are stable across supported targets.
- Use reflection/capability checks when an API is optional or unstable and a direct dependency would reduce portability.
- When an injector reports a real missing executable reference, fix the payload or add a deliberate adapter. Do not weaken validation merely to continue packaging.

Examples already encountered include differences around `Char.buff(Class)`, optional enum constants, `LevelTransition.Type`, Sheep initialization, and older `Item.setCurrent(Hero)` behavior.

## 7. APK invariants

Unless the architecture is deliberately changed:

- the target APK remains the base artifact;
- the target package identity remains unchanged;
- original target DEX files remain byte-for-byte unchanged and are shifted behind the injected overlay DEX;
- the overlay contains the injected/patched SMM payload;
- target resources remain untouched except for deliberate manifest changes owned by the injector;
- unresolved self-containment or compatibility failures stop injection;
- the resulting APK is rebuilt and signed without rebuilding the target game from source.

Do not solve a payload problem by broadly rebuilding or mutating the target APK.

## 8. JAR invariants

The desktop JAR injector follows the same target-as-base principle:

- the target JAR remains the base;
- full `com.spd.mod` payload classes are transplanted from the donor JAR;
- target package references are rebased where required;
- the target `WndGame` entry is patched;
- known compatibility adaptations are applied deliberately;
- unrelated target entries are preserved;
- stale signature/index metadata may be removed when required by repackaging.

The JAR path currently reuses the mature legacy bytecode adaptation/validation machinery for the compatibility cases it knows about, while the representative-target CI provides additional integration coverage. Do not assume that passing one target proves universal compatibility with all forks.

## 9. Validation requirements

Before considering an injection-sensitive change complete:

1. Build fresh donor artifacts from the current SMM source.
2. For APK injection, confirm the donor is the non-minified debug APK, not the release APK.
3. Run packaging from the actual Injection Kit layout, not only directly from repository scripts.
4. Check that payload dependencies do not escape into unrelated donor-only classes.
5. Test both APK and JAR paths when the changed code is shared.
6. Test at least one official SPD target and one materially different SPD fork when practical.
7. Do not suppress genuine compatibility failures.
8. Exercise the affected runtime feature in an injected build when practical.

The current CI representative set includes official Shattered Pixel Dungeon and Rat King Adventure for both APK and JAR injection.

## 10. Failure diagnosis

### Missing target method, field, or type

Determine whether it is:

- a legitimate target API difference needing an adapter;
- an unstable API that should use reflection/capability probing; or
- an accidental donor dependency that should not exist.

### Donor-only obfuscated dependency

If an APK payload unexpectedly references short obfuscated owners or unrelated donor classes, first verify that the dedicated non-minified debug donor was used. Do not make the injector copy arbitrary obfuscated donor classes as a shortcut.

### Injection succeeds but runtime behavior fails

Static packaging success is not sufficient. Inspect the actual executed path for fork-sensitive method descriptors, fields, enum constants, reflection assumptions, and target UI structure.

## 11. Repository write rules

Repository changes must preserve the project's public-attribution rule: publicly visible repository attribution should show only `edward9s`.

Do not create branches, PRs, throwaway refs, bot commits, or alternate public author identities unless explicitly requested.

## 12. Design principle

Prefer implementations whose compiled dependency graph and compatibility assumptions are explicit and auditable.

For ordinary SMM source code, source-level correctness may be enough. For full binary injection, the compiled APK/JAR shape, donor build mode, target ABI, and injection pipeline are all part of the compatibility contract.
