#!/usr/bin/env python3
"""Inject the compiled SMM payload into an SPD-derived desktop JAR."""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Sequence

import _inject_jar_core as injector


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


def output_path(target: Path) -> Path:
    return target.with_name(target.stem + "-SMM" + (target.suffix or ".jar"))


def print_help() -> None:
    print(
        "usage: inject_jar.py TARGET.jar [--out OUTPUT.jar] [--keep-work]\n\n"
        "Inject SMM into an SPD-derived desktop JAR using smm-inject-donor.jar beside this script.\n\n"
        "options:\n"
        "  --out PATH    output JAR (default: <target>-SMM.jar)\n"
        "  --keep-work   keep temporary work files\n"
        "  -h, --help    show this help"
    )


injector.patch_classes = patch_full_classes
injector.rebuild_jar = rebuild_full_jar
injector.output_path_for = output_path


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2
    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor JAR not found beside injector: {DEFAULT_DONOR}")
    return injector.main([str(DEFAULT_DONOR), *args])


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except injector.InjectError as exc:
        print(f"\nError: {exc}", file=sys.stderr)
        raise SystemExit(2)