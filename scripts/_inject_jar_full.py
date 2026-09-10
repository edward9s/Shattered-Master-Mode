#!/usr/bin/env python3
"""Full-SMM desktop JAR patch/repack helpers."""
from __future__ import annotations

import zipfile
from pathlib import Path
from typing import Callable

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


def patch_classes(injector, original_patch_classes: Callable, java: Path, target: Path,
                  helper_payload: Path, donor_modankh: Path, work: Path,
                  target_game_root: str):
    patched_modankh, _unused_patched_dungeon = original_patch_classes(
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


def rebuild_jar(injector, target: Path, patched_wndgame: Path, patched_char: Path,
                patched_modankh: Path, payload: dict[str, bytes], output: Path,
                dungeon_entry: str) -> None:
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
