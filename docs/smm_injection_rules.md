# Shattered Master Mode Binary Injection Rules

[正體中文版](smm_injection_rules.zh-TW.md)

## Scope

Binary injection transplants the compiled SMM `com.spd.mod.*` payload into a compatible SPD-derived APK or desktop JAR.

Public entry points:

- `scripts/inject_apk.py`
- `scripts/inject_jar.py`

Required implementation modules:

- `scripts/_inject_apk_core.py`
- `scripts/_inject_jar_core.py`

## Injection Kit

The distributable artifact is named by the SMM version:

```text
SMM-m<version>-InjectKit.zip
```

It contains:

- `smm-inject-donor.apk`
- `smm-inject-donor.jar`
- `inject_apk.py`
- `_inject_apk_core.py`
- `inject_jar.py`
- `_inject_jar_core.py`
- `README.txt`

Use:

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

The injectors resolve their matching donor from the same directory. Default outputs are `<target>-SMM.apk` and `<target>-SMM.jar`; use `--out` to override them.

## Donors

The APK donor is the dedicated non-minified build produced by the Injection Kit workflow. R8/minification can create donor-only obfuscated dependencies that are unsafe to transplant.

The JAR donor is built from the desktop release output.

The SPD source version used to compile the donors is only a build baseline. The InjectKit version is the SMM version.

## Payload and target handling

- The injectable payload is compiled `com.spd.mod.*`.
- The target APK/JAR remains the base artifact.
- SPD package references are rebased to the target fork package when required.
- `com.spd.mod.*` names are preserved.
- The target `WndGame` constructor is patched to call `com.spd.mod.ModGame.installInjectedMenu(Object)`.
- The injector stops on unresolved payload self-containment or target compatibility failures.

### APK

- Target package identity is preserved.
- Original target DEX files are preserved byte-for-byte and shifted behind the injected overlay DEX.
- Target resources are preserved except for injector-owned manifest changes.
- The output APK is rebuilt and signed.

### JAR

- Full `com.spd.mod.*` classes are transplanted from the donor JAR.
- Unrelated target entries are preserved.
- Invalidated signature/index metadata may be removed during repackaging.

## Compatibility rules

- Select compatibility from the target's actual class/member structure, not its version number.
- APK injection builds a target ABI profile and selects `direct`, `rewrite`, `structural`, or `runtime` strategies.
- Prefer exact APIs, then semantic rewrites, then unambiguous structural fallbacks.
- Equivalent semantic APIs with different descriptors are capability variants; for example, the Duelist combo tracker can expose either `addHit()` or `addHit(Char)`.
- Reject ambiguous structural matches instead of guessing.
- Fork- or minifier-sensitive reflection must use type/descriptor/shape constraints when member names are not stable.
- Do not copy arbitrary donor-only or obfuscated classes to bypass compatibility errors.
- Do not weaken validation to ignore missing executable references.
- CI rejects newly introduced direct references to ABI members already classified as fork-sensitive.

## Validation

Injection-sensitive changes are tested using freshly built donors and the packaged Injection Kit layout.

Current CI validates:

- official Shattered Pixel Dungeon 3.3.8 APK/JAR
- official Shattered Pixel Dungeon 4.0 beta APK/JAR
- Rat King Adventure 2.3.3 APK/JAR

Runtime testing is still required for behavior that static packaging checks cannot exercise.
