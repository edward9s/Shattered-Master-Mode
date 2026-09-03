#!/usr/bin/env python3
"""Inject SMM's compiled ModAnkh into an SPD-derived desktop JAR.

Usage:
    python scripts/inject_jar.py <source-smm.jar> <target.jar> [--out output.jar]

The source JAR is a compiled SMM desktop JAR containing ModAnkh.class.
The target JAR remains the base. The injector:
  * copies ModAnkh plus the controlled ModDebug payload from the donor;
  * adapts Item.setCurrent(Hero) when the target exposes the older
    curUser/curItem fields instead;
  * validates ModAnkh's executable SPD API references against the target JAR;
  * patches Dungeon.init() immediately after HeroClass.initHero(Hero);
  * preserves every other target JAR entry byte-for-byte at the uncompressed
    data level and removes stale JAR signature/index metadata.

No source recompilation or whole-JAR decompilation/rebuild is performed.
Java 17+ is recommended. The helper uses the ASM bundled inside the JDK, so no
third-party Python or Java package is required.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Sequence

MOD_ANKH_ENTRY = "com/spd/mod/items/ModAnkh.class"
MOD_DEBUG_PREFIX = "com/spd/mod/mechanics/ModDebug"
MOD_DEBUG_ENTRY = "com/spd/mod/mechanics/ModDebug.class"
MOD_VALUE_SEARCH_PREFIX = "com/spd/mod/mechanics/ModValueSearch"
MOD_VALUE_SEARCH_ENTRY = "com/spd/mod/mechanics/ModValueSearch.class"
DUNGEON_ENTRY = "com/shatteredpixel/shatteredpixeldungeon/Dungeon.class"
CLASS_MAGIC = b"\xca\xfe\xba\xbe"


class InjectError(RuntimeError):
    pass


def log(message: str = "") -> None:
    print(message, flush=True)


def step(message: str) -> None:
    log(f"\n==> {message}")


def command_string(cmd: Sequence[object]) -> str:
    return subprocess.list2cmdline([str(x) for x in cmd])


def run(cmd: Sequence[object], *, capture: bool = False) -> str:
    cmd = [str(x) for x in cmd]
    log("$ " + command_string(cmd))
    result = subprocess.run(
        cmd,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        check=False,
    )
    if result.returncode:
        output = result.stdout or ""
        raise InjectError(
            f"Command failed ({result.returncode}): {command_string(cmd)}"
            + (f"\n{output}" if output else "")
        )
    return result.stdout or ""


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_java() -> Path:
    java_home = os.environ.get("JAVA_HOME")
    candidates: list[Path] = []
    if java_home:
        candidates.append(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    found = shutil.which("java")
    if found:
        candidates.append(Path(found))
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    raise InjectError("Java not found. Install/use a JDK and ensure java is on PATH or JAVA_HOME is set.")


def validate_jar(path: Path, required: Sequence[str] = ()) -> None:
    if not zipfile.is_zipfile(path):
        raise InjectError(f"Not a valid JAR/ZIP file: {path}")
    with zipfile.ZipFile(path) as zf:
        bad = zf.testzip()
        if bad:
            raise InjectError(f"Corrupt JAR entry: {bad}")
        names = set(zf.namelist())
        for entry in required:
            if entry not in names:
                raise InjectError(f"JAR is missing required class: {entry}")
            if not zf.read(entry).startswith(CLASS_MAGIC):
                raise InjectError(f"Invalid class file: {entry}")


def clone_zipinfo(info: zipfile.ZipInfo, name: str | None = None) -> zipfile.ZipInfo:
    out = zipfile.ZipInfo(name or info.filename, date_time=info.date_time)
    out.compress_type = info.compress_type
    out.comment = info.comment
    out.extra = info.extra
    out.internal_attr = info.internal_attr
    out.external_attr = info.external_attr
    out.create_system = info.create_system
    out.flag_bits = info.flag_bits
    return out


def stale_meta_entry(name: str) -> bool:
    upper = name.upper()
    if upper == "META-INF/INDEX.LIST":
        return True
    if not upper.startswith("META-INF/"):
        return False
    leaf = upper.rsplit("/", 1)[-1]
    return leaf.startswith("SIG-") or leaf.endswith((".SF", ".RSA", ".DSA", ".EC"))


def rebuild_jar(
    target: Path,
    patched_dungeon: Path,
    patched_modankh: Path,
    debug_payload: dict[str, bytes],
    output: Path,
) -> None:
    dungeon_bytes = patched_dungeon.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not dungeon_bytes.startswith(CLASS_MAGIC):
        raise InjectError("Patched Dungeon.class is invalid")
    if not modankh_bytes.startswith(CLASS_MAGIC):
        raise InjectError("Patched ModAnkh.class is invalid")
    if MOD_DEBUG_ENTRY not in debug_payload:
        raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModDebug")
    if MOD_VALUE_SEARCH_ENTRY not in debug_payload:
        raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModValueSearch")
    for name, data in debug_payload.items():
        if not data.startswith(CLASS_MAGIC):
            raise InjectError(f"Invalid debug payload class: {name}")

    with zipfile.ZipFile(target, "r") as zin:
        names = zin.namelist()
        if DUNGEON_ENTRY not in names:
            raise InjectError(f"Target JAR has no {DUNGEON_ENTRY}")
        if MOD_ANKH_ENTRY in names:
            raise InjectError("Target JAR already contains ModAnkh; refusing a second injection")

        collisions = sorted(name for name in debug_payload if name in names)
        if collisions:
            raise InjectError(
                "Target JAR already contains injected debug classes: "
                + ", ".join(collisions)
            )

        dungeon_count = sum(1 for name in names if name == DUNGEON_ENTRY)
        if dungeon_count != 1:
            raise InjectError(f"Expected one Dungeon.class entry, found {dungeon_count}")

        dungeon_info = next(
            info for info in zin.infolist()
            if info.filename == DUNGEON_ENTRY
        )

        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                name = info.filename
                if stale_meta_entry(name):
                    continue
                data = dungeon_bytes if name == DUNGEON_ENTRY else zin.read(name)
                zout.writestr(clone_zipinfo(info), data)

            zout.writestr(
                clone_zipinfo(dungeon_info, MOD_ANKH_ENTRY),
                modankh_bytes,
            )
            for name in sorted(debug_payload):
                zout.writestr(
                    clone_zipinfo(dungeon_info, name),
                    debug_payload[name],
                )


JAVA_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class JarInjectorHelper {
    static final int API = Opcodes.ASM8;

    static final String MOD_ANKH = "com/spd/mod/items/ModAnkh";
    static final String MOD_DEBUG_PREFIX = "com/spd/mod/mechanics/ModDebug";
    static final String DUNGEON = "com/shatteredpixel/shatteredpixeldungeon/Dungeon";
    static final String HERO_CLASS = "com/shatteredpixel/shatteredpixeldungeon/actors/hero/HeroClass";
    static final String HERO = "com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero";
    static final String ITEM = "com/shatteredpixel/shatteredpixeldungeon/items/Item";
    static final String ANKH = "com/shatteredpixel/shatteredpixeldungeon/items/Ankh";
    static final String HERO_DESC = "L" + HERO + ";";
    static final String ITEM_DESC = "L" + ITEM + ";";
    static final String SET_CURRENT_DESC = "(" + HERO_DESC + ")V";

    static class MemberInfo {
        final String owner;
        final int access;
        MemberInfo(String owner, int access) {
            this.owner = owner;
            this.access = access;
        }
    }

    static class ClassInfo {
        String name;
        String superName;
        int access;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Integer> methods = new HashMap<>();
        final Map<String, Integer> fields = new HashMap<>();
    }

    static final Map<String, ClassInfo> classes = new HashMap<>();
    static final LinkedHashSet<String> errors = new LinkedHashSet<>();

    static String key(String name, String desc) {
        return name + "\u0000" + desc;
    }

    static boolean isJdk(String name) {
        return name.startsWith("java/") || name.startsWith("javax/")
                || name.startsWith("jdk/") || name.startsWith("sun/")
                || name.startsWith("org/w3c/") || name.startsWith("org/xml/");
    }

    static String packageName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    static ClassInfo parseClass(byte[] bytes) {
        ClassInfo info = new ClassInfo();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(API) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                info.name = name;
                info.superName = superName;
                info.access = access;
                if (interfaces != null) info.interfaces.addAll(Arrays.asList(interfaces));
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String signature, Object value) {
                info.fields.put(key(name, desc), access);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                info.methods.put(key(name, desc), access);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return info;
    }

    static void loadTargetClasses(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    ClassInfo info = parseClass(bytes);
                    classes.putIfAbsent(info.name, info);
                }
            }
        }
    }

    static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IOException("Missing JAR entry: " + entryName);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static MemberInfo resolveMethod(String owner, String name, String desc) {
        return resolveMethod(owner, name, desc, new HashSet<>());
    }

    static MemberInfo resolveMethod(String owner, String name, String desc, Set<String> seen) {
        if (!seen.add(owner)) return null;
        ClassInfo info = classes.get(owner);
        if (info == null) return null;
        Integer access = info.methods.get(key(name, desc));
        if (access != null) return new MemberInfo(owner, access);
        if ("<init>".equals(name)) return null;
        if (info.superName != null) {
            MemberInfo hit = resolveMethod(info.superName, name, desc, seen);
            if (hit != null) return hit;
        }
        for (String iface : info.interfaces) {
            MemberInfo hit = resolveMethod(iface, name, desc, seen);
            if (hit != null) return hit;
        }
        return null;
    }

    static MemberInfo resolveField(String owner, String name, String desc) {
        return resolveField(owner, name, desc, new HashSet<>());
    }

    static MemberInfo resolveField(String owner, String name, String desc, Set<String> seen) {
        if (!seen.add(owner)) return null;
        ClassInfo info = classes.get(owner);
        if (info == null) return null;
        Integer access = info.fields.get(key(name, desc));
        if (access != null) return new MemberInfo(owner, access);
        for (String iface : info.interfaces) {
            MemberInfo hit = resolveField(iface, name, desc, seen);
            if (hit != null) return hit;
        }
        if (info.superName != null) {
            MemberInfo hit = resolveField(info.superName, name, desc, seen);
            if (hit != null) return hit;
        }
        return null;
    }

    static boolean isSubclass(String child, String parent) {
        if (child.equals(parent)) return true;
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(child);
        while (!queue.isEmpty()) {
            String name = queue.removeFirst();
            if (!seen.add(name)) continue;
            ClassInfo info = classes.get(name);
            if (info == null) continue;
            if (parent.equals(info.superName)) return true;
            if (info.superName != null) queue.add(info.superName);
            for (String iface : info.interfaces) {
                if (parent.equals(iface)) return true;
                queue.add(iface);
            }
        }
        return false;
    }

    static boolean memberAccessible(String from, MemberInfo member) {
        int access = member.access;
        if ((access & Opcodes.ACC_PUBLIC) != 0) return true;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return from.equals(member.owner);
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return packageName(from).equals(packageName(member.owner)) || isSubclass(from, member.owner);
        }
        return packageName(from).equals(packageName(member.owner));
    }

    static void requireFallbackField(String name, String desc) {
        MemberInfo member = resolveField(ITEM, name, desc);
        if (member == null) {
            throw new IllegalStateException("Target lacks fallback field Item." + name + ":" + desc);
        }
        if ((member.access & Opcodes.ACC_STATIC) == 0) {
            throw new IllegalStateException("Fallback field Item." + name + " is not static");
        }
        if (!memberAccessible(MOD_ANKH, member)) {
            throw new IllegalStateException("Fallback field Item." + name + " is not accessible to ModAnkh");
        }
    }

    static byte[] adaptModAnkh(byte[] donorBytes) {
        MemberInfo setCurrent = resolveMethod(ITEM, "setCurrent", SET_CURRENT_DESC);
        if (setCurrent != null && memberAccessible(MOD_ANKH, setCurrent)) {
            System.out.println("ModAnkh adaptation: Item.setCurrent(Hero) available");
            return donorBytes;
        }

        requireFallbackField("curUser", HERO_DESC);
        requireFallbackField("curItem", ITEM_DESC);

        ClassReader reader = new ClassReader(donorBytes);
        ClassWriter writer = new ClassWriter(0);
        final int[] patched = {0};
        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        boolean setCurrentCall = "setCurrent".equals(methodName)
                                && SET_CURRENT_DESC.equals(methodDesc)
                                && (ITEM.equals(owner) || ANKH.equals(owner) || MOD_ANKH.equals(owner));
                        if (setCurrentCall) {
                            super.visitFieldInsn(Opcodes.PUTSTATIC, ITEM, "curUser", HERO_DESC);
                            super.visitFieldInsn(Opcodes.PUTSTATIC, ITEM, "curItem", ITEM_DESC);
                            patched[0]++;
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (patched[0] != 2) {
            throw new IllegalStateException(
                    "Expected two ModAnkh setCurrent(Hero) calls, found " + patched[0]);
        }
        System.out.println("ModAnkh adaptation: replaced Item.setCurrent(Hero) via curUser/curItem");
        return writer.toByteArray();
    }

    static void checkClass(String name, String context) {
        if (name == null || name.equals(MOD_ANKH)
                || name.startsWith(MOD_DEBUG_PREFIX) || isJdk(name)) return;
        ClassInfo info = classes.get(name);
        if (info == null) {
            errors.add(context + ": missing class " + name);
            return;
        }
        if ((info.access & Opcodes.ACC_PUBLIC) == 0
                && !packageName(MOD_ANKH).equals(packageName(name))) {
            errors.add(context + ": class is not public " + name);
        }
    }

    static void checkType(Type type, String context) {
        if (type == null) return;
        while (type.getSort() == Type.ARRAY) type = type.getElementType();
        if (type.getSort() == Type.OBJECT) checkClass(type.getInternalName(), context);
    }

    static void checkDescriptor(String desc, String context) {
        if (desc.startsWith("(")) {
            for (Type type : Type.getArgumentTypes(desc)) checkType(type, context);
            checkType(Type.getReturnType(desc), context);
        } else {
            checkType(Type.getType(desc), context);
        }
    }

    static void validateModAnkh(byte[] bytes) {
        ClassInfo own = parseClass(bytes);
        if (!MOD_ANKH.equals(own.name)) {
            throw new IllegalStateException("Donor class is not " + MOD_ANKH + ": " + own.name);
        }
        classes.put(MOD_ANKH, own);

        checkClass(own.superName, "ModAnkh superclass");
        for (String iface : own.interfaces) checkClass(iface, "ModAnkh interface");
        for (String descKey : own.fields.keySet()) {
            String desc = descKey.substring(descKey.indexOf('\u0000') + 1);
            checkDescriptor(desc, "ModAnkh field descriptor");
        }
        for (String descKey : own.methods.keySet()) {
            String desc = descKey.substring(descKey.indexOf('\u0000') + 1);
            checkDescriptor(desc, "ModAnkh method descriptor");
        }

        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDesc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(API) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (type.startsWith("[")) checkType(Type.getType(type), methodName);
                        else checkClass(type, methodName);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                        checkClass(owner, methodName + ": field owner");
                        checkDescriptor(desc, methodName + ": field descriptor");
                        if (isJdk(owner) || owner.startsWith(MOD_DEBUG_PREFIX)) return;
                        MemberInfo member = resolveField(owner, name, desc);
                        if (member == null) {
                            errors.add(methodName + ": missing field " + owner + "." + name + ":" + desc);
                            return;
                        }
                        boolean staticInsn = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                        boolean staticField = (member.access & Opcodes.ACC_STATIC) != 0;
                        if (staticInsn != staticField) {
                            errors.add(methodName + ": static/instance field mismatch " + owner + "." + name + ":" + desc);
                        }
                        if (!memberAccessible(MOD_ANKH, member)) {
                            errors.add(methodName + ": inaccessible field " + member.owner + "." + name + ":" + desc);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String desc, boolean isInterface) {
                        checkClass(owner, methodName + ": method owner");
                        checkDescriptor(desc, methodName + ": method descriptor");
                        if (isJdk(owner) || owner.startsWith(MOD_DEBUG_PREFIX)) return;
                        MemberInfo member = resolveMethod(owner, name, desc);
                        if (member == null) {
                            errors.add(methodName + ": missing method " + owner + "." + name + desc);
                            return;
                        }
                        boolean staticInsn = opcode == Opcodes.INVOKESTATIC;
                        boolean staticMethod = (member.access & Opcodes.ACC_STATIC) != 0;
                        if (staticInsn != staticMethod) {
                            errors.add(methodName + ": static/instance method mismatch " + owner + "." + name + desc);
                        }
                        if (!memberAccessible(MOD_ANKH, member)) {
                            errors.add(methodName + ": inaccessible method " + member.owner + "." + name + desc);
                        }
                        if (opcode == Opcodes.INVOKEINTERFACE) {
                            ClassInfo ownerInfo = classes.get(owner);
                            if (ownerInfo != null && (ownerInfo.access & Opcodes.ACC_INTERFACE) == 0) {
                                errors.add(methodName + ": invokeinterface owner is not interface " + owner);
                            }
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type) checkType((Type) value, methodName + ": ldc type");
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String desc, int dims) {
                        checkType(Type.getType(desc), methodName + ": multianewarray");
                    }

                    @Override
                    public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                        if (type != null) checkClass(type, methodName + ": catch type");
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                        checkDescriptor(desc, methodName + ": invokedynamic descriptor");
                        if (bsm != null) checkClass(bsm.getOwner(), methodName + ": bootstrap owner");
                        if (args != null) {
                            for (Object arg : args) {
                                if (arg instanceof Type) checkType((Type) arg, methodName + ": bootstrap type");
                                if (arg instanceof Handle) checkClass(((Handle) arg).getOwner(), methodName + ": bootstrap handle owner");
                            }
                        }
                    }
                };
            }
        }, 0);

        if (!errors.isEmpty()) {
            System.err.println("ModAnkh target API errors:");
            for (String error : errors) System.err.println("  - " + error);
            throw new IllegalStateException("Found " + errors.size() + " incompatible ModAnkh reference(s)");
        }
        System.out.println("ModAnkh target API check: OK");
    }

    static byte[] patchDungeon(byte[] original) {
        MemberInfo collect = resolveMethod(ITEM, "collect", "()Z");
        if (collect == null || !memberAccessible(MOD_ANKH, collect)) {
            throw new IllegalStateException("Target Item.collect()Z is missing or inaccessible");
        }

        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        final int[] initMethods = {0};
        final int[] anchors = {0};
        final boolean[] alreadyInjected = {false};

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"init".equals(name) || !"()V".equals(desc) || (access & Opcodes.ACC_STATIC) == 0) {
                    return base;
                }
                initMethods[0]++;
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && MOD_ANKH.equals(type)) alreadyInjected[0] = true;
                        super.visitTypeInsn(opcode, type);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                        boolean anchor = HERO_CLASS.equals(owner)
                                && "initHero".equals(methodName)
                                && ("(L" + HERO + ";)V").equals(methodDesc);
                        if (!anchor) return;
                        anchors[0]++;
                        super.visitTypeInsn(Opcodes.NEW, MOD_ANKH);
                        super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, MOD_ANKH, "<init>", "()V", false);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "collect", "()Z", false);
                        super.visitInsn(Opcodes.POP);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack + 2, maxLocals);
                    }
                };
            }
        };
        reader.accept(visitor, 0);

        if (alreadyInjected[0]) throw new IllegalStateException("Dungeon.init() already creates ModAnkh");
        if (initMethods[0] != 1) {
            throw new IllegalStateException("Expected one static Dungeon.init()V, found " + initMethods[0]);
        }
        if (anchors[0] != 1) {
            throw new IllegalStateException("Expected one HeroClass.initHero(Hero) anchor, found " + anchors[0]);
        }
        System.out.println("Dungeon.init() patch: OK");
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: JarInjectorHelper <target.jar> <donor-ModAnkh.class> <out-ModAnkh.class> <out-Dungeon.class>");
        }
        Path targetJar = Paths.get(args[0]);
        Path donorModAnkh = Paths.get(args[1]);
        Path outModAnkh = Paths.get(args[2]);
        Path outDungeon = Paths.get(args[3]);

        loadTargetClasses(targetJar);
        if (!classes.containsKey(DUNGEON)) throw new IllegalStateException("Target has no Dungeon class");
        if (!classes.containsKey(ITEM)) throw new IllegalStateException("Target has no Item class");
        if (!classes.containsKey(HERO_CLASS)) throw new IllegalStateException("Target has no HeroClass class");
        if (!classes.containsKey(HERO)) throw new IllegalStateException("Target has no Hero class");

        byte[] donorBytes = Files.readAllBytes(donorModAnkh);
        ClassInfo donorInfo = parseClass(donorBytes);
        if (!MOD_ANKH.equals(donorInfo.name)) {
            throw new IllegalStateException("Donor class is not " + MOD_ANKH + ": " + donorInfo.name);
        }
        classes.put(MOD_ANKH, donorInfo);
        byte[] modAnkh = adaptModAnkh(donorBytes);
        validateModAnkh(modAnkh);

        byte[] dungeon = readJarEntry(targetJar, DUNGEON + ".class");
        byte[] patchedDungeon = patchDungeon(dungeon);

        Files.write(outModAnkh, modAnkh);
        Files.write(outDungeon, patchedDungeon);
    }
}
'''


def patch_classes(java: Path, target: Path, donor_modankh: Path, work: Path) -> tuple[Path, Path]:
    helper = work / "JarInjectorHelper.java"
    helper.write_text(JAVA_HELPER, encoding="utf-8")
    out_modankh = work / "ModAnkh.class"
    out_dungeon = work / "Dungeon.class"
    run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper,
        target,
        donor_modankh,
        out_modankh,
        out_dungeon,
    ])
    if not out_modankh.is_file() or not out_dungeon.is_file():
        raise InjectError("Bytecode helper did not produce patched class files")
    return out_modankh, out_dungeon


def output_path_for(target: Path) -> Path:
    return target.with_name(target.stem + "-ModAnkh" + (target.suffix or ".jar"))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Inject compiled SMM ModAnkh into an SPD-derived desktop JAR"
    )
    parser.add_argument("source_jar", help="compiled SMM donor JAR containing ModAnkh")
    parser.add_argument("target_jar", help="SPD-derived target JAR")
    parser.add_argument("--out", help="output JAR (default: <target>-ModAnkh.jar)")
    parser.add_argument("--keep-work", action="store_true", help="keep temporary patched class files")
    args = parser.parse_args(argv)

    source = Path(os.path.expanduser(args.source_jar)).resolve()
    target = Path(os.path.expanduser(args.target_jar)).resolve()
    output = Path(os.path.expanduser(args.out)).resolve() if args.out else output_path_for(target)

    if not source.is_file():
        raise InjectError(f"Source JAR not found: {source}")
    if not target.is_file():
        raise InjectError(f"Target JAR not found: {target}")
    if source == target:
        raise InjectError("Source and target JAR must be different")
    if output in {source, target}:
        raise InjectError("Refusing to overwrite an input JAR")

    validate_jar(source, [MOD_ANKH_ENTRY])
    validate_jar(target, [DUNGEON_ENTRY])
    java = ensure_java()

    if args.keep_work:
        work = Path(tempfile.mkdtemp(prefix="modankh-jar-inject-"))
        cleanup = False
    else:
        temp = tempfile.TemporaryDirectory(prefix="modankh-jar-inject-")
        work = Path(temp.name)
        cleanup = True
    log(f"Working directory: {work}")

    try:
        donor_modankh = work / "donor-ModAnkh.class"
        with zipfile.ZipFile(source) as zf:
            donor_modankh.write_bytes(zf.read(MOD_ANKH_ENTRY))
            debug_names = sorted(
                name for name in zf.namelist()
                if (
                    name == MOD_DEBUG_ENTRY
                    or (
                        name.startswith(MOD_DEBUG_PREFIX + "$")
                        and name.endswith(".class")
                    )
                    or name == MOD_VALUE_SEARCH_ENTRY
                    or (
                        name.startswith(MOD_VALUE_SEARCH_PREFIX + "$")
                        and name.endswith(".class")
                    )
                )
            )
            if MOD_DEBUG_ENTRY not in debug_names:
                raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModDebug")
            if MOD_VALUE_SEARCH_ENTRY not in debug_names:
                raise InjectError("Donor JAR is missing com.spd.mod.mechanics.ModValueSearch")
            debug_payload = {
                name: zf.read(name) for name in debug_names
            }

        step("Adapting and validating donor ModAnkh against target JAR")
        patched_modankh, patched_dungeon = patch_classes(java, target, donor_modankh, work)

        step("Repacking target JAR")
        output.parent.mkdir(parents=True, exist_ok=True)
        unsigned_tmp = work / "output.jar"
        rebuild_jar(
            target,
            patched_dungeon,
            patched_modankh,
            debug_payload,
            unsigned_tmp,
        )
        validate_jar(
            unsigned_tmp,
            [
                DUNGEON_ENTRY,
                MOD_ANKH_ENTRY,
                MOD_DEBUG_ENTRY,
                MOD_VALUE_SEARCH_ENTRY,
            ],
        )
        shutil.copy2(unsigned_tmp, output)

        step("Done")
        log(f"Output : {output}")
        log(f"SHA-256: {sha256(output)}")
        log(
            f"Injected: ModAnkh + debug console "
            f"({len(debug_payload)} debug classes)"
        )
        if args.keep_work:
            log(f"Work files kept at: {work}")
        return 0
    finally:
        if cleanup:
            temp.cleanup()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)
