# ModAnkh Injectable Payload Rules

[正體中文版](modankh_payload_rules.zh-TW.md)

## Purpose

ModAnkh and its debug/storage support code are not ordinary application code. They must work in two different environments:

1. as part of Shattered Master Mode itself; and
2. after being extracted from a compiled SMM donor APK/JAR and injected into another SPD-derived build.

A change can compile and run correctly in SMM while still breaking binary injection. Treat every class that can enter the injectable payload as a small compatibility library with stricter rules than normal game code.

The current injector implementations in `scripts/inject_apk.py` and `scripts/inject_jar.py` are the source of truth for the exact payload boundary and compatibility checks.

## 1. Payload boundary

Keep the injectable surface narrow and deliberate.

Current important payload families include:

- `com.spd.mod.items.ModAnkh`
- `com.spd.mod.items.ModAnkhStore` and its `$*` classes
- `com.spd.mod.mechanics.ModDebug` and its `$*` classes
- helper payloads explicitly selected by the JAR injector, such as `ModValueSearch` and `ModSaveTransfer`

Do not assume that an arbitrary class reachable from those classes is safe to copy into a target build.

### Rules

- Prefer self-contained helpers under an existing payload family.
- If a helper must be added, make its inclusion explicit and verify both APK and JAR injector behavior.
- Never make payload code depend on unrelated donor-only application classes.
- Never rely on an obfuscated donor class name being meaningful in the target.
- Do not broaden the payload merely to make a compatibility error disappear.

## 2. R8 / desugaring safety

Binary donors are sensitive to compiler-generated synthetic classes. R8/D8 may merge or share synthetic helpers across otherwise unrelated application code. If the injector follows such a helper as a donor-only dependency, unrelated donor code can be pulled into the debug payload.

This has already produced failures where `ModDebug$Donor_*` helpers unexpectedly referenced donor-specific LibGDX members and unrelated SMM code.

### Avoid in injectable payload code

Prefer not to use constructs that can create shared desugared helpers, especially:

- lambdas (`x -> ...`)
- method references (`Type::method`)
- `Collection.removeIf(...)`
- `Map.computeIfAbsent(...)`
- other Java 8 collection/default-interface convenience methods when a simple explicit loop is practical

### Prefer

- ordinary loops
- explicit `get` / `put` / `containsKey`
- ordinary helper methods
- anonymous inner classes when a callback/comparator is necessary

Anonymous inner classes are acceptable when they remain clearly inside the payload family (for example `ModDebug$Console$1`).

The reason for these restrictions is binary donor isolation, not lack of Java language support.

## 3. Target API compatibility

Do not assume that every SPD fork exposes exactly the same source API as the current SMM donor.

### Rules

- Treat target API differences as normal.
- Use direct calls only for APIs that are sufficiently stable across supported targets.
- For known compatible API variations, adapt them in the injector rather than cloning large sections of target-specific code.
- For optional or unstable APIs, prefer reflection/capability probing when practical.
- Keep reflection focused. Do not accidentally create compile-time dependencies on large unrelated type graphs.
- If a compatibility validator reports a real missing executable reference, fix the payload or add a deliberate adapter. Do not simply weaken the validator.

Example: `Item.setCurrent(Hero)` may be adapted by the injector to older `curUser` / `curItem` fields when the target exposes that older API.

## 4. Debug Console consistency

Debug Console behavior should not depend on how a command reaches the executor.

The following paths should stay behaviorally consistent where applicable:

- direct interactive commands
- macros
- `@handle` result prefixes
- optional methods on commands such as `give`, `spawn`, and `affect`
- reflection-based `get` / `set`
- `use`

### Fuzzy identifier policy

Names that users reasonably need to remember may use fuzzy resolution:

- class names
- field names
- method names
- `Class`-typed method arguments
- enum values when used as named values

Matching order:

1. exact
2. unique prefix
3. unique substring
4. unique fuzzy subsequence
5. ambiguous result: show `Similar:` suggestions and do not guess

Exact names always win. In particular, if an exact method name exists but its arguments are invalid, do not silently redirect to a different fuzzy-matched method.

The following remain exact by design:

- command verbs
- `@handle` names
- numbers, cells, quantities, durations
- ordinary string arguments

## 5. APK injector invariants

Unless a deliberate design change says otherwise, preserve these properties:

- the target APK remains the base artifact
- target package identity remains unchanged
- original target DEX files remain byte-for-byte unchanged and are shifted behind the overlay DEX
- the overlay contains only the patched/injected payload
- target resources remain untouched except for deliberate manifest edits already owned by the injector
- target API compatibility is validated before packaging
- an unresolved/self-containment failure stops injection

Do not solve a payload problem by rebuilding or mutating the whole target unless that becomes an explicit architectural decision.

## 6. JAR injector invariants

The desktop JAR injector follows the same general isolation principle:

- target JAR remains the base
- only explicitly selected ModAnkh/debug payloads are copied
- `Dungeon.init()` is patched at the known anchor
- compatible API differences may be adapted deliberately
- unrelated target entries are preserved
- stale signature/index metadata may be removed as required by repackaging

Changes to payload structure must be checked against both injectors; success on APK alone is not sufficient.

## 7. Validation requirements

Before considering a payload change complete:

1. Compile the changed Java sources.
2. Check injectable payload sources for accidental lambda/method-reference/default-method usage when relevant.
3. Build a fresh SMM donor artifact. An old donor still contains old R8 output.
4. Run the applicable injector against at least one representative target.
5. Ensure no unexpected `ModDebug$Donor_*` or other relocated helpers appear.
6. If relocated helpers do appear, inspect why they entered the dependency closure instead of immediately whitelisting them.
7. Confirm compatibility validation passes without suppressing legitimate errors.
8. Exercise the affected runtime feature in the injected build when possible.

A source-only compile is necessary but not sufficient for binary-injection changes.

## 8. Failure diagnosis

When injection fails, classify the problem before changing code.

### `missing target method/field/type`

Ask whether the reference is:

- a legitimate target API difference that needs an adapter;
- an unstable API that should be reflected/probed; or
- an accidental donor dependency that should not be in the payload at all.

### Many unrelated LibGDX/API failures from `ModDebug$Donor_*`

Suspect a shared R8/desugar synthetic dependency first. Look for newly introduced lambdas, method references, collection default methods, or other compiler-generated helpers in the payload source.

### `donor-only dependency ... is not present`

The payload references a class the donor-closure logic cannot safely carry. Remove the dependency, explicitly redesign the payload boundary, or make the dependency self-contained.

Do not fix these failures by disabling self-containment checks.

## 9. Repository attribution rule

This repository has a strict attribution requirement:

> Any publicly visible attribution produced by repository changes must show only `edward9s`.

This applies to commit authors/committers and any public contributor/activity identity produced by the chosen workflow.

Therefore:

- do not use GitHub Actions/bots to commit or push
- do not use another account or GitHub App identity for repository writes
- do not add `Co-authored-by` metadata for another identity
- avoid PR/merge workflows that introduce another visible attribution unless explicitly allowed later
- use only a write path whose resulting author and committer attribution can be verified as `edward9s`

Force-push or history rewriting is not prohibited merely because it is a force-push. The hard rule is attribution identity.

## 10. Design principle

When choosing between a clever implementation and a slightly more explicit one, prefer the implementation whose compiled dependency graph is obvious.

For ordinary SMM code, source-level elegance may be enough. For injectable ModAnkh payload code, the compiled APK/JAR dependency graph is part of the API.