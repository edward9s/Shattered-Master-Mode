#!/usr/bin/env python3
"""Inject the complete compiled SMM payload into an SPD-derived desktop JAR.

The target JAR remains the base artifact.  This front-end reuses the mature
ModAnkh adapter/validator and payload rebasing from ``inject_jar.py``, expands
the explicit payload surface to every compiled ``com.spd.mod`` class, and
replaces the legacy Dungeon startup hook with the traditional WndGame menu
entry.
"""
from __future__ import annotations

from pathlib import Path
from typing import Sequence

import inject_jar as injector


injector.MOD_ITEM_CLASS_PREFIX = "com/spd/mod/"

_original_patch_classes = injector.patch_classes
_original_rebuild_jar = injector.rebuild_jar

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


def patch_full_classes(
    java: Path,
    target: Path,
    helper_payload: Path,
    donor_modankh: Path,
    work: Path,
    target_game_root: str = injector.SOURCE_GAME_INTERNAL_ROOT,
):
    # Preserve the proven ModAnkh compatibility adaptation/validation. The
    # legacy helper's patched Dungeon output is intentionally discarded below.
    patched_modankh, _unused_patched_dungeon = _original_patch_classes(
        java,
        target,
        helper_payload,
        donor_modankh,
        work,
        target_game_root,
    )

    wnd_game = target_game_root + "/windows/WndGame"
    helper = work / "SmmWndGamePatcher.java"
    helper.write_text(WND_HELPER.replace("__WND_GAME__", wnd_game), encoding="utf-8")
    out_wnd = work / "WndGame.class"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper,
        target,
        out_wnd,
    ])
    if not out_wnd.is_file() or not out_wnd.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("WndGame bytecode helper did not produce a valid class")
    return patched_modankh, out_wnd


def rebuild_full_jar(
    target: Path,
    patched_wndgame: Path,
    patched_modankh: Path,
    store_payload: dict[str, bytes],
    debug_payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str = injector.DUNGEON_ENTRY,
) -> None:
    wnd_entry = dungeon_entry[:-len("Dungeon.class")] + "windows/WndGame.class"
    _original_rebuild_jar(
        target,
        patched_wndgame,
        patched_modankh,
        store_payload,
        debug_payload,
        output,
        wnd_entry,
    )


injector.patch_classes = patch_full_classes
injector.rebuild_jar = rebuild_full_jar


def main(argv: Sequence[str] | None = None) -> int:
    return injector.main(argv)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=__import__("sys").stderr)
        raise SystemExit(2)
