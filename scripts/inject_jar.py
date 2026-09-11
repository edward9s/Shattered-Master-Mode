#!/usr/bin/env python3
"""Inject SMM into an SPD-derived desktop JAR."""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Sequence

import _inject_jar_core as injector

# ModAssassinBuff was renamed without a compatibility alias. Retarget the
# existing full-injection family checks to the new compiled class name.
injector.MOD_ASSASSIN_BUFF_PREFIX = "com/spd/mod/mechanics/ModAssassinate"
injector.MOD_ASSASSIN_BUFF_ENTRY = "com/spd/mod/mechanics/ModAssassinate.class"


_original_ensure_java = injector.ensure_java


def _ensure_java() -> Path:
    if injector.platform.system().lower() != "android":
        return _original_ensure_java()

    java = injector.shutil.which("java")
    if java:
        return Path(java).resolve()

    pkg = injector.shutil.which("pkg")
    if pkg is None:
        raise injector.InjectError(
            "Android host detected, but Termux package manager 'pkg' was not found"
        )

    injector.step("Installing minimal Termux dependencies")
    injector.run([pkg, "install", "-y", "openjdk-21"])
    java = injector.shutil.which("java")
    if not java:
        raise injector.InjectError(
            "Termux installed openjdk-21 but java is still unavailable on PATH"
        )
    return Path(java).resolve()


injector.ensure_java = _ensure_java

DEFAULT_DONOR = Path(__file__).resolve().with_name("smm-inject-donor.jar")
FULL_SMM_CLASS_PREFIX = "com/spd/mod/"
injector.MOD_ITEM_CLASS_PREFIX = FULL_SMM_CLASS_PREFIX
_original_patch_classes = injector.patch_classes

# The core JAR helper originally registered only ModAnkhStore. Both current
# full injection and Ankh-only dependency-closure validation need to resolve
# references against every com.spd.mod class supplied in the helper payload.
injector.JAVA_HELPER = injector.JAVA_HELPER.replace(
    '''    static boolean isStoreClass(String name) {
        return MOD_ANKH_STORE.equals(name) || name.startsWith(MOD_ANKH_STORE_PREFIX);
    }
''',
    '''    static boolean isStoreClass(String name) {
        return name.startsWith("com/spd/mod/");
    }
''',
).replace(
    '''        if (!classes.containsKey(MOD_ANKH_STORE)) {
            throw new IllegalStateException("Donor JAR has no ModAnkhStore class");
        }
''',
    '',
).replace(
    'System.out.println("ModAnkhStore payload classes registered: " + storeClassCount);',
    'System.out.println("SMM helper payload classes registered: " + storeClassCount);',
)


# ---------------------------------------------------------------------------
# Full-SMM JAR mode
# ---------------------------------------------------------------------------

WND_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmWndGamePatcher {
    static final int API = Opcodes.ASM8;
    static final String WND_GAME = "__WND_GAME__";
    static final String MOD_GAME = "com/spd/mod/ModGame";

    static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IOException("Missing JAR entry: " + entryName);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static byte[] patch(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        final String[] superName = {null};
        final int[] constructors = {0};
        final int[] anchors = {0};
        final boolean[] alreadyInjected = {false};

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String parent, String[] interfaces) {
                if (!WND_GAME.equals(name)) {
                    throw new IllegalStateException("Target class is not WndGame: " + name);
                }
                superName[0] = parent;
                super.visit(version, access, name, signature, parent, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"<init>".equals(name) || !"()V".equals(desc)) return base;
                constructors[0]++;
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (MOD_GAME.equals(owner)
                                && "installInjectedMenu".equals(methodName)
                                && "(Ljava/lang/Object;)V".equals(methodDesc)) {
                            alreadyInjected[0] = true;
                        }

                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);

                        if (opcode == Opcodes.INVOKESPECIAL
                                && "<init>".equals(methodName)
                                && "()V".equals(methodDesc)
                                && owner.equals(superName[0])) {
                            anchors[0]++;
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_GAME,
                                    "installInjectedMenu",
                                    "(Ljava/lang/Object;)V",
                                    false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + 1, maxLocals);
                    }
                };
            }
        };
        reader.accept(visitor, 0);

        if (alreadyInjected[0]) {
            throw new IllegalStateException("WndGame already contains SMM menu injection");
        }
        if (constructors[0] != 1) {
            throw new IllegalStateException(
                    "Expected one WndGame() constructor, found " + constructors[0]);
        }
        if (anchors[0] != 1) {
            throw new IllegalStateException(
                    "Expected one WndGame super() anchor, found " + anchors[0]);
        }
        System.out.println("WndGame menu patch: OK");
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: SmmWndGamePatcher <target.jar> <out-WndGame.class>");
        }
        Path target = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        byte[] original = readJarEntry(target, WND_GAME + ".class");
        Files.write(output, patch(original));
    }
}
'''

CHAR_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmCharAttackPatcher {
    static final int API = Opcodes.ASM8;
    static final String CHAR = "__CHAR__";
    static final String MOD_PARRY_RIPOSTE = "com/spd/mod/mechanics/ModParryRiposte";
    static final String ATTACK_DESC = "(L" + CHAR + ";FFF)Z";
    static final String INCOMING_DESC = "(L" + CHAR + ";L" + CHAR + ";)V";

    static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IOException("Missing JAR entry: " + entryName);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static void validateHook(Path payloadJar) throws IOException {
        byte[] bytes = readJarEntry(payloadJar, MOD_PARRY_RIPOSTE + ".class");
        final int[] hooks = {0};
        final int[] validHooks = {0};
        new ClassReader(bytes).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if ("onIncomingAttack".equals(name) && INCOMING_DESC.equals(desc)) {
                    hooks[0]++;
                    if ((access & Opcodes.ACC_PUBLIC) != 0
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        validHooks[0]++;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (hooks[0] != 1 || validHooks[0] != 1) {
            throw new IllegalStateException(
                    "SMM donor ModParryRiposte lacks public static onIncomingAttack(Char, Char)");
        }
        System.out.println("ModParryRiposte incoming-attack hook API: OK");
    }

    static byte[] patch(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        final int[] attackMethods = {0};
        final boolean[] alreadyInjected = {false};

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String parent, String[] interfaces) {
                if (!CHAR.equals(name)) {
                    throw new IllegalStateException("Target class is not Char: " + name);
                }
                super.visit(version, access, name, signature, parent, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"attack".equals(name)
                        || !ATTACK_DESC.equals(desc)
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return base;
                }
                attackMethods[0]++;
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                MOD_PARRY_RIPOSTE,
                                "onIncomingAttack",
                                INCOMING_DESC,
                                false);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (MOD_PARRY_RIPOSTE.equals(owner)
                                && "onIncomingAttack".equals(methodName)
                                && INCOMING_DESC.equals(methodDesc)) {
                            alreadyInjected[0] = true;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + 2, maxLocals);
                    }
                };
            }
        };
        reader.accept(visitor, 0);

        if (alreadyInjected[0]) {
            throw new IllegalStateException("Char.attack already contains SMM incoming-attack hook");
        }
        if (attackMethods[0] != 1) {
            throw new IllegalStateException(
                    "Expected one Char.attack(Char,float,float,float), found " + attackMethods[0]);
        }
        System.out.println("Char.attack incoming-attack patch: OK");
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SmmCharAttackPatcher <target.jar> <payload.jar> <out-Char.class>");
        }
        Path target = Paths.get(args[0]);
        Path payload = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        validateHook(payload);
        byte[] original = readJarEntry(target, CHAR + ".class");
        Files.write(output, patch(original));
    }
}
'''


def patch_full_classes(
    java: Path,
    target: Path,
    helper_payload: Path,
    donor_modankh: Path,
    work: Path,
    target_game_root: str,
):
    patched_modankh, _unused_patched_dungeon = _original_patch_classes(
        java, target, helper_payload, donor_modankh, work, target_game_root
    )

    wnd_game = target_game_root + "/windows/WndGame"
    helper = work / "SmmWndGamePatcher.java"
    helper.write_text(WND_HELPER.replace("__WND_GAME__", wnd_game), encoding="utf-8")
    out_wnd = work / "WndGame.class"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper, target, out_wnd,
    ])
    if not out_wnd.is_file() or not out_wnd.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("WndGame bytecode helper did not produce a valid class")

    char_name = target_game_root + "/actors/Char"
    char_helper = work / "SmmCharAttackPatcher.java"
    char_helper.write_text(CHAR_HELPER.replace("__CHAR__", char_name), encoding="utf-8")
    out_char = work / "Char.class"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        char_helper, target, helper_payload, out_char,
    ])
    if not out_char.is_file() or not out_char.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Char bytecode helper did not produce a valid class")
    return patched_modankh, out_wnd, out_char


def rebuild_full_jar(
    target: Path,
    patched_wndgame: Path,
    patched_char: Path,
    patched_modankh: Path,
    payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str,
) -> None:
    root = dungeon_entry[:-len("Dungeon.class")]
    wnd_entry = root + "windows/WndGame.class"
    char_entry = root + "actors/Char.class"
    wnd_bytes = patched_wndgame.read_bytes()
    char_bytes = patched_char.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not wnd_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched WndGame.class is invalid")
    if not char_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Char.class is invalid")
    if not modankh_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched ModAnkh.class is invalid")
    for name, data in payload.items():
        if not data.startswith(injector.CLASS_MAGIC):
            raise injector.InjectError(f"Invalid SMM payload class: {name}")

    with zipfile.ZipFile(target, "r") as zin:
        names = zin.namelist()
        if wnd_entry not in names:
            raise injector.InjectError(f"Target JAR has no {wnd_entry}")
        if char_entry not in names:
            raise injector.InjectError(f"Target JAR has no {char_entry}")
        if injector.MOD_ANKH_ENTRY in names:
            raise injector.InjectError("Target JAR already contains ModAnkh; refusing a second injection")

        injected_names = set(payload)
        injected_names.add(injector.MOD_ANKH_ENTRY)
        collisions = sorted(name for name in injected_names if name in names)
        if collisions:
            raise injector.InjectError(
                "Target JAR already contains injected payload classes: " + ", ".join(collisions)
            )

        wnd_info = next(info for info in zin.infolist() if info.filename == wnd_entry)
        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                name = info.filename
                if injector.stale_meta_entry(name):
                    continue
                if name == wnd_entry:
                    data = wnd_bytes
                elif name == char_entry:
                    data = char_bytes
                else:
                    data = zin.read(name)
                zout.writestr(injector.clone_zipinfo(info), data)

            zout.writestr(
                injector.clone_zipinfo(wnd_info, injector.MOD_ANKH_ENTRY), modankh_bytes
            )
            for name in sorted(payload):
                zout.writestr(injector.clone_zipinfo(wnd_info, name), payload[name])


# ---------------------------------------------------------------------------
# ModAnkh-only JAR mode
# ---------------------------------------------------------------------------

ANKH_REQUIRED_ROOTS = {
    "com/spd/mod/items/WndModLoot.class",
    "com/spd/mod/mechanics/ModLootStorage.class",
    "com/spd/mod/mechanics/ModLoot.class",
    "com/spd/mod/mechanics/ModDebug.class",
    "com/spd/mod/mechanics/ModDebug$Console.class",
    "com/spd/mod/mechanics/ModLegacyCompat.class",
    "com/spd/mod/mechanics/ModItemCompat.class",
}

ANKH_OPTIONAL_ROOTS = (
    "com/spd/mod/mechanics/ModLastStand.class",
)

_ACTION_MESSAGE_BUNDLE_RE = re.compile(
    r"^assets/messages/items/items(?:_[^/]+)?\.properties$"
)
_ACTION_MESSAGES = (
    (b"com.spd.mod.items.modankh.ac_store", b"Store"),
    (b"com.spd.mod.items.modankh.ac_loot", b"Loot"),
    (b"com.spd.mod.items.modankh.ac_console", b"Console"),
    (b"com.spd.mod.items.modankh.ac_unbless", b"Unbless"),
)


def _append_action_messages(data: bytes) -> tuple[bytes, int]:
    missing = []
    for key, value in _ACTION_MESSAGES:
        if re.search(rb"(?m)^" + re.escape(key) + rb"\s*=", data) is None:
            missing.append((key, value))
    if not missing:
        return data, 0

    out = bytearray(data)
    if out and not out.endswith((b"\n", b"\r")):
        out.extend(b"\n")
    out.extend(b"\n# SMM ModAnkh injected action labels\n")
    for key, value in missing:
        out.extend(key + b"=" + value + b"\n")
    return bytes(out), len(missing)


def _class_utf8_strings(data: bytes) -> list[str]:
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise injector.InjectError("Invalid class file while building ModAnkh dependency closure")

    cp_count = int.from_bytes(data[8:10], "big")
    offset = 10
    index = 1
    strings: list[str] = []
    fixed = {
        3: 4, 4: 4,
        7: 2, 8: 2, 16: 2, 19: 2, 20: 2,
        9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4,
        15: 3,
    }

    while index < cp_count:
        if offset >= len(data):
            raise injector.InjectError("Truncated class constant pool")
        tag = data[offset]
        offset += 1
        if tag == 1:
            if offset + 2 > len(data):
                raise injector.InjectError("Truncated class UTF-8 length")
            size = int.from_bytes(data[offset:offset + 2], "big")
            offset += 2
            raw = data[offset:offset + size]
            if len(raw) != size:
                raise injector.InjectError("Truncated class UTF-8 value")
            offset += size
            strings.append(raw.decode("utf-8", errors="replace"))
        elif tag in (5, 6):
            offset += 8
            index += 1
        else:
            size = fixed.get(tag)
            if size is None:
                raise injector.InjectError(f"Unsupported class constant-pool tag: {tag}")
            offset += size
        index += 1
    return strings


def _smm_dependencies(data: bytes, available: set[str]) -> set[str]:
    deps: set[str] = set()
    for text in _class_utf8_strings(data):
        for internal in re.findall(r"com/spd/mod/[A-Za-z0-9_$/.]+", text):
            candidate = internal.rstrip(".;") + ".class"
            if candidate in available:
                deps.add(candidate)
        for dotted in re.findall(r"com\.spd\.mod\.[A-Za-z0-9_$.]+", text):
            candidate = dotted.replace(".", "/").rstrip(";") + ".class"
            if candidate in available:
                deps.add(candidate)
    return deps


def build_ankh_payload(
    donor: zipfile.ZipFile,
    target_game_root: str,
) -> dict[str, bytes]:
    available = {
        name for name in donor.namelist()
        if name.startswith(FULL_SMM_CLASS_PREFIX) and name.endswith(".class")
    }
    root = injector.MOD_ANKH_ENTRY
    if root not in available:
        raise injector.InjectError("SMM donor JAR is missing ModAnkh")

    closure: set[str] = set()
    queue = list(_smm_dependencies(donor.read(root), available))
    included_optional = []
    for extra in ANKH_OPTIONAL_ROOTS:
        if extra in available:
            queue.append(extra)
            included_optional.append(extra.rsplit("/", 1)[-1][:-6])

    while queue:
        name = queue.pop()
        if name == root or name in closure:
            continue
        closure.add(name)
        for dep in _smm_dependencies(donor.read(name), available):
            if dep != root and dep not in closure:
                queue.append(dep)

    missing = sorted(ANKH_REQUIRED_ROOTS - closure)
    if missing:
        raise injector.InjectError(
            "SMM donor is too old for --ankh-only JAR injection; rebuild the Injection Kit. "
            "Missing ModAnkh dependency root(s): " + ", ".join(missing)
        )

    features = " + ".join(included_optional)
    injector.log(
        f"ModAnkh{(' + ' + features) if features else ''} dependency closure: "
        f"{len(closure)} class(es) (Store + Loot + Console)"
    )
    return {
        name: injector.rebase_class_bytes(donor.read(name), target_game_root)
        for name in sorted(closure)
    }


ANKH_LISTENER_ADAPTER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmAnkhPayloadAdapter {
    static final int API = Opcodes.ASM8;
    static final String LISTENER = "__LISTENER__";
    static final String OBJECT = "java/lang/Object";

    static boolean targetListenerIsInterface(Path target) throws IOException {
        try (JarFile jar = new JarFile(target.toFile())) {
            JarEntry entry = jar.getJarEntry(LISTENER + ".class");
            if (entry == null) return false;
            try (InputStream in = jar.getInputStream(entry)) {
                return (new ClassReader(in).getAccess() & Opcodes.ACC_INTERFACE) != 0;
            }
        }
    }

    static byte[] adapt(byte[] input, int[] changedClasses) {
        ClassReader reader = new ClassReader(input);
        final boolean rewrite = LISTENER.equals(reader.getSuperName());
        if (!rewrite) return input;

        ClassWriter writer = new ClassWriter(0);
        final int[] ctorCalls = {0};
        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                LinkedHashSet<String> out = new LinkedHashSet<>();
                if (interfaces != null) out.addAll(Arrays.asList(interfaces));
                out.add(LISTENER);
                super.visit(version, access, name, signature, OBJECT,
                        out.toArray(new String[0]));
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESPECIAL
                                && LISTENER.equals(owner)
                                && "<init>".equals(methodName)
                                && "()V".equals(methodDesc)) {
                            ctorCalls[0]++;
                            super.visitMethodInsn(opcode, OBJECT, methodName, methodDesc, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (ctorCalls[0] == 0) {
            throw new IllegalStateException(
                    "Legacy CellSelector.Listener subclass has no super constructor call: "
                            + reader.getClassName());
        }
        changedClasses[0]++;
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SmmAnkhPayloadAdapter <target.jar> <payload-in.jar> <payload-out.jar>");
        }
        Path target = Paths.get(args[0]);
        Path input = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        boolean legacy = targetListenerIsInterface(target);
        int[] changed = {0};

        try (JarFile jar = new JarFile(input.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                byte[] data;
                try (InputStream in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (legacy && entry.getName().endsWith(".class")) {
                    data = adapt(data, changed);
                }
                JarEntry copy = new JarEntry(entry.getName());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("CellSelector.Listener JAR adaptation: " + changed[0] + " class(es)");
    }
}
'''


def adapt_ankh_payload(
    java: Path,
    target: Path,
    payload_jar: Path,
    work: Path,
    target_game_root: str,
) -> tuple[Path, dict[str, bytes]]:
    helper = work / "SmmAnkhPayloadAdapter.java"
    helper.write_text(
        ANKH_LISTENER_ADAPTER.replace(
            "__LISTENER__", target_game_root + "/scenes/CellSelector$Listener"
        ),
        encoding="utf-8",
    )
    adapted = work / "adapted-ankh-payload.jar"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper, target, payload_jar, adapted,
    ])
    injector.validate_jar(adapted)
    with zipfile.ZipFile(adapted) as zf:
        payload = {
            name: zf.read(name)
            for name in zf.namelist()
            if name.startswith(FULL_SMM_CLASS_PREFIX) and name.endswith(".class")
        }
    return adapted, payload


def rebuild_ankh_jar(
    target: Path,
    patched_dungeon: Path,
    patched_modankh: Path,
    payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str,
) -> None:
    dungeon_bytes = patched_dungeon.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not dungeon_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Dungeon.class is invalid")
    if not modankh_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched ModAnkh.class is invalid")
    for name, data in payload.items():
        if not data.startswith(injector.CLASS_MAGIC):
            raise injector.InjectError(f"Invalid ModAnkh dependency class: {name}")

    matched_bundles = 0
    added_labels = 0
    with zipfile.ZipFile(target, "r") as zin:
        names = set(zin.namelist())
        if dungeon_entry not in names:
            raise injector.InjectError(f"Target JAR has no {dungeon_entry}")
        if injector.MOD_ANKH_ENTRY in names:
            raise injector.InjectError("Target JAR already contains ModAnkh; refusing a second injection")
        collisions = sorted((set(payload) | {injector.MOD_ANKH_ENTRY}) & names)
        if collisions:
            raise injector.InjectError(
                "Target JAR already contains injected payload classes: " + ", ".join(collisions)
            )

        dungeon_info = next(i for i in zin.infolist() if i.filename == dungeon_entry)
        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                if injector.stale_meta_entry(info.filename):
                    continue
                data = dungeon_bytes if info.filename == dungeon_entry else zin.read(info.filename)
                if _ACTION_MESSAGE_BUNDLE_RE.fullmatch(info.filename):
                    matched_bundles += 1
                    data, count = _append_action_messages(data)
                    added_labels += count
                zout.writestr(injector.clone_zipinfo(info), data)

            zout.writestr(
                injector.clone_zipinfo(dungeon_info, injector.MOD_ANKH_ENTRY), modankh_bytes
            )
            for name in sorted(payload):
                zout.writestr(injector.clone_zipinfo(dungeon_info, name), payload[name])

    if matched_bundles == 0:
        injector.log(
            "No standard SPD item message bundle found; "
            "ModAnkh action labels rely on target actionName() support"
        )
    elif added_labels:
        injector.log(
            f"Injected ModAnkh action labels into {matched_bundles} item message bundle(s)"
        )
    else:
        injector.log("ModAnkh action labels already present in target message bundles")


def run_ankh_only(
    source: Path,
    target: Path,
    output: Path,
    java: Path,
    work: Path,
    target_game_root: str,
    dungeon_entry: str,
) -> int:
    donor_modankh = work / "donor-ModAnkh.class"
    with zipfile.ZipFile(source) as donor:
        donor_modankh.write_bytes(
            injector.rebase_class_bytes(
                donor.read(injector.MOD_ANKH_ENTRY), target_game_root
            )
        )
        payload = build_ankh_payload(donor, target_game_root)

    raw_payload_jar = work / "rebased-ankh-payload.jar"
    injector.write_helper_payload_jar(raw_payload_jar, payload)
    helper_payload, payload = adapt_ankh_payload(
        java, target, raw_payload_jar, work, target_game_root
    )

    injector.step("Adapting and validating donor ModAnkh against target JAR")
    patched_modankh, patched_dungeon = _original_patch_classes(
        java, target, helper_payload, donor_modankh, work, target_game_root
    )

    injector.step("Repacking target JAR")
    output.parent.mkdir(parents=True, exist_ok=True)
    tmp = work / "output-ankh.jar"
    rebuild_ankh_jar(
        target, patched_dungeon, patched_modankh, payload, tmp, dungeon_entry
    )
    injector.validate_jar(
        tmp, [dungeon_entry, injector.MOD_ANKH_ENTRY, *sorted(payload)]
    )
    shutil.copy2(tmp, output)

    injector.step("Done")
    injector.log(f"Output : {output}")
    injector.log(f"SHA-256: {injector.sha256(output)}")
    injector.log(
        f"Injected: ModAnkh + Last Stand "
        f"(Store + Loot + Console; {len(payload)} dependency classes)"
    )
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def output_path(target: Path, ankh_only: bool = False) -> Path:
    suffix = "-SMM-Ankh" if ankh_only else "-SMM"
    return target.with_name(target.stem + suffix + (target.suffix or ".jar"))


def print_help() -> None:
    print(
        "usage: inject_jar.py TARGET.jar [--ankh-only] [--out OUTPUT.jar] [--keep-work]\n\n"
        "Inject SMM into an SPD-derived desktop JAR using smm-inject-donor.jar beside this script.\n\n"
        "modes:\n"
        "  default       inject the full supported SMM payload\n"
        "  --ankh-only   inject ModAnkh + ModLastStand + Store + Loot + Console\n\n"
        "options:\n"
        "  --out PATH    output JAR (default: <target>-SMM.jar or <target>-SMM-Ankh.jar)\n"
        "  --keep-work   keep temporary work files\n"
        "  -h, --help    show this help"
    )


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2
    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor JAR not found beside injector: {DEFAULT_DONOR}")

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("target")
    parser.add_argument("--ankh-only", action="store_true")
    parser.add_argument("--out")
    parser.add_argument("--keep-work", action="store_true")
    parsed = parser.parse_args(args)

    source = DEFAULT_DONOR.resolve()
    target = Path(os.path.expanduser(parsed.target)).resolve()
    output = (
        Path(os.path.expanduser(parsed.out)).resolve()
        if parsed.out
        else output_path(target, parsed.ankh_only)
    )

    if not target.is_file():
        raise injector.InjectError(f"Target JAR not found: {target}")
    if output in {source, target}:
        raise injector.InjectError("Refusing to overwrite an input JAR")

    injector.validate_jar(source, [injector.MOD_ANKH_ENTRY])
    injector.validate_jar(target)
    with zipfile.ZipFile(target) as target_zip:
        target_game_root = injector.detect_target_game_root(target_zip.namelist())
    dungeon_entry = target_game_root + "/Dungeon.class"
    wnd_entry = target_game_root + "/windows/WndGame.class"
    char_entry = target_game_root + "/actors/Char.class"
    injector.log("Target SPD-family package: " + target_game_root.replace("/", "."))
    injector.log(
        "Injection mode: "
        + (
            "ModAnkh + ModLastStand (Store + Loot + Console)"
            if parsed.ankh_only else "full SMM"
        )
    )
    java = injector.ensure_java()

    if parsed.keep_work:
        work = Path(tempfile.mkdtemp(prefix="smm-jar-inject-"))
        cleanup = False
    else:
        temp = tempfile.TemporaryDirectory(prefix="smm-jar-inject-")
        work = Path(temp.name)
        cleanup = True
    injector.log(f"Working directory: {work}")

    try:
        if parsed.ankh_only:
            result = run_ankh_only(
                source,
                target,
                output,
                java,
                work,
                target_game_root,
                dungeon_entry,
            )
            if parsed.keep_work:
                injector.log(f"Work files kept at: {work}")
            return result

        donor_modankh = work / "donor-ModAnkh.class"
        with zipfile.ZipFile(source) as zf:
            donor_modankh.write_bytes(
                injector.rebase_class_bytes(
                    zf.read(injector.MOD_ANKH_ENTRY), target_game_root
                )
            )
            payload_names = sorted(
                name for name in zf.namelist()
                if name.startswith(FULL_SMM_CLASS_PREFIX)
                and name.endswith(".class")
                and name != injector.MOD_ANKH_ENTRY
            )
            if not payload_names:
                raise injector.InjectError("Donor JAR contains no SMM payload classes")
            payload = {
                name: injector.rebase_class_bytes(zf.read(name), target_game_root)
                for name in payload_names
            }

        helper_payload = work / "rebased-smm-payload.jar"
        injector.write_helper_payload_jar(helper_payload, payload)

        injector.step("Adapting and validating donor ModAnkh against target JAR")
        patched_modankh, patched_wndgame, patched_char = patch_full_classes(
            java,
            target,
            helper_payload,
            donor_modankh,
            work,
            target_game_root,
        )

        injector.step("Repacking target JAR")
        output.parent.mkdir(parents=True, exist_ok=True)
        unsigned_tmp = work / "output.jar"
        rebuild_full_jar(
            target,
            patched_wndgame,
            patched_char,
            patched_modankh,
            payload,
            unsigned_tmp,
            dungeon_entry,
        )
        injector.validate_jar(
            unsigned_tmp,
            [wnd_entry, char_entry, injector.MOD_ANKH_ENTRY, *payload_names],
        )
        shutil.copy2(unsigned_tmp, output)

        injector.step("Done")
        injector.log(f"Output : {output}")
        injector.log(f"SHA-256: {injector.sha256(output)}")
        injector.log(
            f"Injected: ModAnkh + full SMM payload ({len(payload)} additional classes)"
        )
        if parsed.keep_work:
            injector.log(f"Work files kept at: {work}")
        return 0
    finally:
        if cleanup:
            temp.cleanup()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
