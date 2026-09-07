#!/usr/bin/env python3
"""Inject the compiled SMM payload into an SPD-derived desktop JAR."""
from __future__ import annotations

import argparse
import os
import shutil
import sys
import tempfile
import zipfile
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
FULL_SMM_CLASS_PREFIX = "com/spd/mod/"
injector.MOD_ITEM_CLASS_PREFIX = FULL_SMM_CLASS_PREFIX

_original_patch_classes = injector.patch_classes

# The legacy core validator used to load only ModAnkhStore and its inner classes.
# SMM is now injected as one payload, so validate ModAnkh against every injected
# com.spd.mod class instead of preserving a fake standalone-store boundary.
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
    payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str = injector.DUNGEON_ENTRY,
) -> None:
    wnd_entry = dungeon_entry[:-len("Dungeon.class")] + "windows/WndGame.class"
    wnd_bytes = patched_wndgame.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not wnd_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched WndGame.class is invalid")
    if not modankh_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched ModAnkh.class is invalid")
    for name, data in payload.items():
        if not data.startswith(injector.CLASS_MAGIC):
            raise injector.InjectError(f"Invalid SMM payload class: {name}")

    with zipfile.ZipFile(target, "r") as zin:
        names = zin.namelist()
        if wnd_entry not in names:
            raise injector.InjectError(f"Target JAR has no {wnd_entry}")
        if injector.MOD_ANKH_ENTRY in names:
            raise injector.InjectError("Target JAR already contains ModAnkh; refusing a second injection")

        injected_names = set(payload)
        injected_names.add(injector.MOD_ANKH_ENTRY)
        collisions = sorted(name for name in injected_names if name in names)
        if collisions:
            raise injector.InjectError(
                "Target JAR already contains injected payload classes: "
                + ", ".join(collisions)
            )

        wnd_info = next(info for info in zin.infolist() if info.filename == wnd_entry)

        with zipfile.ZipFile(output, "w", allowZip64=True) as zout:
            for info in zin.infolist():
                name = info.filename
                if injector.stale_meta_entry(name):
                    continue
                data = wnd_bytes if name == wnd_entry else zin.read(name)
                zout.writestr(injector.clone_zipinfo(info), data)

            zout.writestr(
                injector.clone_zipinfo(wnd_info, injector.MOD_ANKH_ENTRY),
                modankh_bytes,
            )
            for name in sorted(payload):
                zout.writestr(injector.clone_zipinfo(wnd_info, name), payload[name])


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


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or "-h" in args or "--help" in args:
        print_help()
        return 0 if args else 2
    if not DEFAULT_DONOR.is_file():
        raise injector.InjectError(f"SMM donor JAR not found beside injector: {DEFAULT_DONOR}")

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("target")
    parser.add_argument("--out")
    parser.add_argument("--keep-work", action="store_true")
    parsed = parser.parse_args(args)

    source = DEFAULT_DONOR.resolve()
    target = Path(os.path.expanduser(parsed.target)).resolve()
    output = Path(os.path.expanduser(parsed.out)).resolve() if parsed.out else output_path(target)

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
    injector.log("Target SPD-family package: " + target_game_root.replace("/", "."))
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
        donor_modankh = work / "donor-ModAnkh.class"
        with zipfile.ZipFile(source) as zf:
            donor_modankh.write_bytes(
                injector.rebase_class_bytes(
                    zf.read(injector.MOD_ANKH_ENTRY),
                    target_game_root,
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
        patched_modankh, patched_wndgame = patch_full_classes(
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
            patched_modankh,
            payload,
            unsigned_tmp,
            dungeon_entry,
        )
        injector.validate_jar(
            unsigned_tmp,
            [wnd_entry, injector.MOD_ANKH_ENTRY, *payload_names],
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