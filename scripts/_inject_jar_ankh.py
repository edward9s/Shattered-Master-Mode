#!/usr/bin/env python3
"""ModAnkh + Store/Loot/Console mode for desktop JAR injection."""
from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Callable

SMM_PREFIX = "com/spd/mod/"
REQUIRED_ROOTS = {
    "com/spd/mod/items/WndModLoot.class",
    "com/spd/mod/mechanics/ModLootStorage.class",
    "com/spd/mod/mechanics/ModLoot.class",
    "com/spd/mod/mechanics/ModDebug.class",
    "com/spd/mod/mechanics/ModDebug$Console.class",
    "com/spd/mod/mechanics/ModLegacyCompat.class",
    "com/spd/mod/mechanics/ModItemCompat.class",
}

_ACTION_MESSAGE_BUNDLE_RE = re.compile(
    r"^assets/messages/items/items(?:_[^/]+)?\.properties$"
)
_ACTION_MESSAGES = (
    (b"com.spd.mod.items.modankh.ac_store", b"Store"),
    (b"com.spd.mod.items.modankh.ac_loot", b"Loot"),
    (b"com.spd.mod.items.modankh.ac_console", b"Console"),
    (b"com.spd.mod.items.modankh.ac_unbless", b"Unbless"),
)


def output_path(target: Path) -> Path:
    return target.with_name(target.stem + "-SMM-Ankh" + (target.suffix or ".jar"))


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
    """Return CONSTANT_Utf8 strings without depending on a classfile library."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("invalid class file")

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
            raise ValueError("truncated constant pool")
        tag = data[offset]
        offset += 1
        if tag == 1:
            if offset + 2 > len(data):
                raise ValueError("truncated UTF-8 length")
            size = int.from_bytes(data[offset:offset + 2], "big")
            offset += 2
            raw = data[offset:offset + size]
            if len(raw) != size:
                raise ValueError("truncated UTF-8 value")
            offset += size
            strings.append(raw.decode("utf-8", errors="replace"))
        elif tag in (5, 6):
            offset += 8
            index += 1
        else:
            size = fixed.get(tag)
            if size is None:
                raise ValueError(f"unsupported constant-pool tag {tag}")
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


def build_payload(injector, donor: zipfile.ZipFile, target_game_root: str) -> dict[str, bytes]:
    available = {
        name for name in donor.namelist()
        if name.startswith(SMM_PREFIX) and name.endswith(".class")
    }
    root = injector.MOD_ANKH_ENTRY
    if root not in available:
        raise injector.InjectError("SMM donor JAR is missing ModAnkh")

    closure: set[str] = set()
    queue = list(_smm_dependencies(donor.read(root), available))
    while queue:
        name = queue.pop()
        if name == root or name in closure:
            continue
        closure.add(name)
        for dep in _smm_dependencies(donor.read(name), available):
            if dep != root and dep not in closure:
                queue.append(dep)

    missing = sorted(REQUIRED_ROOTS - closure)
    if missing:
        raise injector.InjectError(
            "SMM donor is too old for --ankh-only JAR injection; rebuild the Injection Kit. "
            "Missing ModAnkh dependency root(s): " + ", ".join(missing)
        )

    injector.log(
        f"ModAnkh dependency closure: {len(closure)} class(es) (Store + Loot + Console)"
    )
    return {
        name: injector.rebase_class_bytes(donor.read(name), target_game_root)
        for name in sorted(closure)
    }


LISTENER_ADAPTER = r'''
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


def adapt_payload(injector, java: Path, target: Path, payload_jar: Path,
                  work: Path, target_game_root: str) -> tuple[Path, dict[str, bytes]]:
    helper = work / "SmmAnkhPayloadAdapter.java"
    helper.write_text(
        LISTENER_ADAPTER.replace(
            "__LISTENER__", target_game_root + "/scenes/CellSelector$Listener"
        ),
        encoding="utf-8",
    )
    adapted = work / "adapted-ankh-payload.jar"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper,
        target,
        payload_jar,
        adapted,
    ])
    injector.validate_jar(adapted)
    with zipfile.ZipFile(adapted) as zf:
        payload = {
            name: zf.read(name)
            for name in zf.namelist()
            if name.startswith(SMM_PREFIX) and name.endswith(".class")
        }
    return adapted, payload


def rebuild_jar(injector, target: Path, patched_dungeon: Path,
                patched_modankh: Path, payload: dict[str, bytes], output: Path,
                dungeon_entry: str) -> None:
    dungeon_bytes = patched_dungeon.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not dungeon_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Dungeon.class is invalid")
    if not modankh_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched ModAnkh.class is invalid")

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
                injector.clone_zipinfo(dungeon_info, injector.MOD_ANKH_ENTRY),
                modankh_bytes,
            )
            for name in sorted(payload):
                zout.writestr(injector.clone_zipinfo(dungeon_info, name), payload[name])

    if matched_bundles == 0:
        injector.log(
            "No standard SPD item message bundle found; ModAnkh action labels rely on target actionName() support"
        )
    elif added_labels:
        injector.log(
            f"Injected ModAnkh action labels into {matched_bundles} item message bundle(s)"
        )


def run(*, injector, patch_classes: Callable, source: Path, target: Path,
        output: Path, java: Path, work: Path, target_game_root: str,
        dungeon_entry: str) -> int:
    donor_modankh = work / "donor-ModAnkh.class"
    with zipfile.ZipFile(source) as donor:
        donor_modankh.write_bytes(
            injector.rebase_class_bytes(donor.read(injector.MOD_ANKH_ENTRY), target_game_root)
        )
        payload = build_payload(injector, donor, target_game_root)

    raw_payload_jar = work / "rebased-ankh-payload.jar"
    injector.write_helper_payload_jar(raw_payload_jar, payload)
    helper_payload, payload = adapt_payload(
        injector, java, target, raw_payload_jar, work, target_game_root
    )

    injector.step("Adapting and validating donor ModAnkh against target JAR")
    patched_modankh, patched_dungeon = patch_classes(
        java, target, helper_payload, donor_modankh, work, target_game_root
    )

    injector.step("Repacking target JAR")
    output.parent.mkdir(parents=True, exist_ok=True)
    tmp = work / "output-ankh.jar"
    rebuild_jar(
        injector, target, patched_dungeon, patched_modankh, payload, tmp, dungeon_entry
    )
    injector.validate_jar(
        tmp, [dungeon_entry, injector.MOD_ANKH_ENTRY, *sorted(payload)]
    )
    import shutil
    shutil.copy2(tmp, output)

    injector.step("Done")
    injector.log(f"Output : {output}")
    injector.log(f"SHA-256: {injector.sha256(output)}")
    injector.log(
        f"Injected: ModAnkh only (Store + Loot + Console; {len(payload)} dependency classes)"
    )
    return 0
