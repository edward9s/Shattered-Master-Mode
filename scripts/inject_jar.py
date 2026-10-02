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
    static final String MOD_FORCE_HIT = "com/spd/mod/mechanics/ModForceHit";
    static final String MOD_INSTANT_KILL = "com/spd/mod/mechanics/ModInstantKill";
    static final String FORCE_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";)Z";
    static final String INSTANT_KILL_DESC = "(L" + CHAR + ";L" + CHAR + ";)Z";
    static final String INCOMING_DESC = "(L" + CHAR + ";L" + CHAR + ";)V";
    static final String FINISH_ATTACK_DESC = "(Z)V";
    static final String DEFENSE_FEEDBACK_DESC = "(L" + CHAR + ";)Ljava/lang/String;";
    static final String MODERN_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";FZ)Z";
    static final String LEGACY_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";Z)Z";

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
        validatePublicStatic(
                payloadJar,
                MOD_PARRY_RIPOSTE,
                "onHitCheck",
                FORCE_HIT_DESC,
                "ModParryRiposte unified hit-check hook API");
        validatePublicStatic(
                payloadJar,
                MOD_PARRY_RIPOSTE,
                "defenseVerb",
                DEFENSE_FEEDBACK_DESC,
                "ModParryRiposte defense-feedback hook API");
        validatePublicStatic(
                payloadJar,
                MOD_FORCE_HIT,
                "forceHitCheck",
                FORCE_HIT_DESC,
                "ModForceHit hit-check hook API");
        validatePublicStatic(
                payloadJar,
                MOD_INSTANT_KILL,
                "resolveSuccessfulAttack",
                INSTANT_KILL_DESC,
                "ModInstantKill successful-hit hook API");
        validatePublicStatic(
                payloadJar,
                MOD_INSTANT_KILL,
                "beginAttack",
                INCOMING_DESC,
                "ModInstantKill attack-context entry API");
        validatePublicStatic(
                payloadJar,
                MOD_INSTANT_KILL,
                "finishAttack",
                FINISH_ATTACK_DESC,
                "ModInstantKill attack-context completion API");
    }

    static void validatePublicStatic(
            Path payloadJar,
            String owner,
            String methodName,
            String methodDesc,
            String label) throws IOException {
        byte[] bytes = readJarEntry(payloadJar, owner + ".class");
        final int[] matches = {0};
        final int[] valid = {0};
        new ClassReader(bytes).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (methodName.equals(name) && methodDesc.equals(desc)) {
                    matches[0]++;
                    if ((access & Opcodes.ACC_PUBLIC) != 0
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        valid[0]++;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (matches[0] != 1 || valid[0] != 1) {
            throw new IllegalStateException(
                    "SMM donor lacks public static " + owner + "." + methodName + methodDesc);
        }
        System.out.println(label + ": OK");
    }

    static boolean isAttack(String name, String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return "attack".equals(name)
                && (access & Opcodes.ACC_STATIC) == 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 1
                && ("L" + CHAR + ";").equals(args[0].getDescriptor());
    }

    static boolean isStructuralHit(String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return (access & Opcodes.ACC_STATIC) != 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 2
                && ("L" + CHAR + ";").equals(args[0].getDescriptor())
                && ("L" + CHAR + ";").equals(args[1].getDescriptor());
    }

    static final class Plan {
        String terminalAttackDesc;
        String hitMethod;
        String hitDesc;
        String hitDetail;
    }

    static Plan analyze(byte[] original) {
        Plan plan = new Plan();
        LinkedHashSet<String> attacks = new LinkedHashSet<>();
        LinkedHashSet<String> structuralHits = new LinkedHashSet<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackEdges = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackHitCalls = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> hitEdges = new LinkedHashMap<>();

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String parent, String[] interfaces) {
                if (!CHAR.equals(name)) {
                    throw new IllegalStateException("Target class is not Char: " + name);
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (isAttack(name, desc, access)) {
                    attacks.add(desc);
                    attackEdges.put(desc, new LinkedHashSet<>());
                    attackHitCalls.put(desc, new LinkedHashSet<>());
                }
                if (isStructuralHit(desc, access)) {
                    structuralHits.add(name + "\n" + desc);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (!isAttack(name, desc, access)) return null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (!CHAR.equals(owner)) return;
                        if ("attack".equals(methodName) && attacks.contains(methodDesc)) {
                            attackEdges.get(desc).add(methodDesc);
                        }
                        String key = methodName + "\n" + methodDesc;
                        if (opcode == Opcodes.INVOKESTATIC && structuralHits.contains(key)) {
                            attackHitCalls.get(desc).add(key);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        LinkedHashSet<String> terminals = new LinkedHashSet<>();
        for (String desc : attacks) {
            if (attackEdges.get(desc).isEmpty()) terminals.add(desc);
        }
        if (terminals.size() != 1) {
            throw new IllegalStateException(
                    "Expected one terminal Char.attack overload, found " + terminals.size()
                            + " among " + attacks.size() + " overload(s)");
        }
        plan.terminalAttackDesc = terminals.iterator().next();

        LinkedHashSet<String> calledHits = attackHitCalls.get(plan.terminalAttackDesc);
        String modernDirect = "hit\n" + MODERN_HIT_DESC;
        String legacyDirect = "hit\n" + LEGACY_HIT_DESC;
        String selected = structuralHits.contains(modernDirect)
                ? modernDirect
                : (structuralHits.contains(legacyDirect) ? legacyDirect : null);

        if (selected != null) {
            plan.hitDetail = "direct hit-check ";
        } else {
            if (calledHits.size() != 1) {
                throw new IllegalStateException(
                        "Expected one callable hit-check in terminal Char.attack"
                                + plan.terminalAttackDesc + ", found " + calledHits.size()
                                + ": " + calledHits);
            }
            selected = calledHits.iterator().next();
            plan.hitDetail = "structural hit-check ";
        }

        int split = selected.indexOf('\n');
        plan.hitMethod = selected.substring(0, split);
        plan.hitDesc = selected.substring(split + 1);
        plan.hitDetail += plan.hitMethod + plan.hitDesc;

        System.out.println("Terminal Char.attack selected: " + plan.terminalAttackDesc);
        System.out.println("Shared hit-check selected: " + plan.hitDetail);
        return plan;
    }

    static byte[] patch(byte[] original) {
        Plan plan = analyze(original);
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);

        final int[] terminalAttackMethods = {0};
        final int[] hitMethods = {0};
        final boolean[] alreadyRiposte = {false};
        final boolean[] alreadyForce = {false};
        final boolean[] alreadyInstant = {false};

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);

                if ("attack".equals(name)
                        && plan.terminalAttackDesc.equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    base = new MethodVisitor(API, base) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && CHAR.equals(owner)
                                    && "defenseVerb".equals(methodName)
                                    && "()Ljava/lang/String;".equals(methodDesc)) {
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_PARRY_RIPOSTE,
                                        "defenseVerb",
                                        DEFENSE_FEEDBACK_DESC,
                                        false);
                                return;
                            }
                            super.visitMethodInsn(
                                    opcode, owner, methodName, methodDesc, isInterface);
                        }
                    };
                    terminalAttackMethods[0]++;
                    return new MethodVisitor(API, base) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_INSTANT_KILL,
                                    "beginAttack",
                                    INCOMING_DESC,
                                    false);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean isInterface) {
                            if (MOD_INSTANT_KILL.equals(owner)
                                    && ("beginAttack".equals(methodName)
                                        || "finishAttack".equals(methodName)
                                        || "resolveSuccessfulAttack".equals(methodName))) {
                                alreadyInstant[0] = true;
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_INSTANT_KILL,
                                        "finishAttack",
                                        FINISH_ATTACK_DESC,
                                        false);
                            }
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 2, maxLocals);
                        }
                    };
                }

                if (plan.hitMethod.equals(name)
                        && plan.hitDesc.equals(desc)
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    hitMethods[0]++;
                    return new MethodVisitor(API, base) {
                        @Override
                        public void visitCode() {
                            super.visitCode();

                            Label noParry = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_PARRY_RIPOSTE,
                                    "onHitCheck",
                                    FORCE_HIT_DESC,
                                    false);
                            super.visitJumpInsn(Opcodes.IFEQ, noParry);

                            Label parryMiss = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_FORCE_HIT,
                                    "forceHitCheck",
                                    FORCE_HIT_DESC,
                                    false);
                            super.visitJumpInsn(Opcodes.IFEQ, parryMiss);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(parryMiss);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitInsn(Opcodes.IRETURN);

                            super.visitLabel(noParry);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            Label nativeHit = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_FORCE_HIT,
                                    "forceHitCheck",
                                    FORCE_HIT_DESC,
                                    false);
                            super.visitJumpInsn(Opcodes.IFEQ, nativeHit);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(nativeHit);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean isInterface) {
                            if (MOD_FORCE_HIT.equals(owner)
                                    && "forceHitCheck".equals(methodName)
                                    && FORCE_HIT_DESC.equals(methodDesc)) {
                                alreadyForce[0] = true;
                            }
                            if (MOD_PARRY_RIPOSTE.equals(owner)
                                    && "onHitCheck".equals(methodName)
                                    && FORCE_HIT_DESC.equals(methodDesc)) {
                                alreadyRiposte[0] = true;
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 2, maxLocals);
                        }
                    };
                }

                return base;
            }
        };
        reader.accept(visitor, 0);

        if (alreadyRiposte[0]) {
            throw new IllegalStateException(
                    "Selected hit-check already contains SMM Parry/Riposte hook");
        }
        if (alreadyForce[0]) {
            throw new IllegalStateException(
                    "Selected hit-check already contains SMM Force Hit hook");
        }
        if (alreadyInstant[0]) {
            throw new IllegalStateException(
                    "Terminal Char.attack already contains SMM Instant Kill hook");
        }
        if (terminalAttackMethods[0] != 1) {
            throw new IllegalStateException(
                    "Expected one selected terminal Char.attack, found "
                            + terminalAttackMethods[0]);
        }
        if (hitMethods[0] != 1) {
            throw new IllegalStateException(
                    "Expected one selected hit-check method, found " + hitMethods[0]);
        }

        System.out.println(
                "Char.attack Instant Kill attack-context return patch: OK ("
                        + plan.terminalAttackDesc + ")");
        System.out.println(
                "Unified Force Hit + Parry/Riposte hit patch: OK ("
                        + plan.hitMethod + plan.hitDesc + ")");
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



PARRY_FEEDBACK_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmParryFeedbackPatcher {
    static final int API = Opcodes.ASM8;
    static final String CHAR = "__CHAR__";
    static final String GAME_ROOT = "__GAME_ROOT__";
    static final String MOD_PARRY_RIPOSTE = "com/spd/mod/mechanics/ModParryRiposte";
    static final String DEFENSE_FEEDBACK_DESC = "(L" + CHAR + ";)Ljava/lang/String;";
    static final String MODERN_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";FZ)Z";
    static final String LEGACY_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";Z)Z";

    static String methodKey(String name, String desc) {
        return name + "\n" + desc;
    }

    static boolean isCharType(String type, Map<String, String> parents) {
        HashSet<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            if (CHAR.equals(current)) return true;
            current = parents.get(current);
        }
        return false;
    }

    static boolean isAttack(String name, String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return "attack".equals(name)
                && (access & Opcodes.ACC_STATIC) == 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 1
                && ("L" + CHAR + ";").equals(args[0].getDescriptor());
    }

    static boolean isStructuralHit(String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return (access & Opcodes.ACC_STATIC) != 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 2
                && ("L" + CHAR + ";").equals(args[0].getDescriptor())
                && ("L" + CHAR + ";").equals(args[1].getDescriptor());
    }

    static final class Plan {
        String terminalAttackDesc;
        String hitMethod;
        String hitDesc;
        final LinkedHashSet<String> hitAliases = new LinkedHashSet<>();
    }

    static Plan analyzeChar(byte[] original) {
        Plan plan = new Plan();
        LinkedHashSet<String> attacks = new LinkedHashSet<>();
        LinkedHashSet<String> structuralHits = new LinkedHashSet<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackEdges = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackHitCalls = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> hitEdges = new LinkedHashMap<>();

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (isAttack(name, desc, access)) {
                    attacks.add(desc);
                    attackEdges.put(desc, new LinkedHashSet<>());
                    attackHitCalls.put(desc, new LinkedHashSet<>());
                }
                if (isStructuralHit(desc, access)) {
                    String key = methodKey(name, desc);
                    structuralHits.add(key);
                    hitEdges.put(key, new LinkedHashSet<>());
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                boolean attack = isAttack(name, desc, access);
                boolean structuralHit = isStructuralHit(desc, access);
                if (!attack && !structuralHit) return null;
                String callerKey = structuralHit ? methodKey(name, desc) : null;
                return new MethodVisitor(API) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (!CHAR.equals(owner)) return;
                        if (attack
                                && "attack".equals(methodName)
                                && attacks.contains(methodDesc)) {
                            attackEdges.get(desc).add(methodDesc);
                        }
                        String key = methodKey(methodName, methodDesc);
                        if (opcode == Opcodes.INVOKESTATIC && structuralHits.contains(key)) {
                            if (attack) {
                                attackHitCalls.get(desc).add(key);
                            }
                            if (structuralHit) {
                                hitEdges.get(callerKey).add(key);
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        LinkedHashSet<String> terminals = new LinkedHashSet<>();
        for (String desc : attacks) {
            if (attackEdges.get(desc).isEmpty()) terminals.add(desc);
        }
        if (terminals.size() != 1) {
            throw new IllegalStateException(
                    "Expected one terminal Char.attack overload, found "
                            + terminals.size());
        }
        plan.terminalAttackDesc = terminals.iterator().next();

        String selected = structuralHits.contains(methodKey("hit", MODERN_HIT_DESC))
                ? methodKey("hit", MODERN_HIT_DESC)
                : (structuralHits.contains(methodKey("hit", LEGACY_HIT_DESC))
                    ? methodKey("hit", LEGACY_HIT_DESC)
                    : null);
        if (selected == null) {
            LinkedHashSet<String> calledHits = attackHitCalls.get(plan.terminalAttackDesc);
            if (calledHits == null || calledHits.size() != 1) {
                throw new IllegalStateException(
                        "Expected one selected Char hit-check, found "
                                + (calledHits == null ? 0 : calledHits.size()));
            }
            selected = calledHits.iterator().next();
        }

        int split = selected.indexOf('\n');
        plan.hitMethod = selected.substring(0, split);
        plan.hitDesc = selected.substring(split + 1);
        plan.hitAliases.add(selected);

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String candidate : structuralHits) {
                if (plan.hitAliases.contains(candidate)) continue;
                LinkedHashSet<String> calls = hitEdges.get(candidate);
                if (calls == null) continue;
                for (String called : calls) {
                    if (plan.hitAliases.contains(called)) {
                        plan.hitAliases.add(candidate);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return plan;
    }

    static boolean resolvesToSelectedHit(
            String owner,
            String methodName,
            String methodDesc,
            Plan plan,
            Map<String, String> parents,
            Map<String, Map<String, Integer>> declarations) {
        String key = methodKey(methodName, methodDesc);
        if (!plan.hitAliases.contains(key)) {
            return false;
        }
        HashSet<String> seen = new HashSet<>();
        String current = owner;
        while (current != null && seen.add(current)) {
            Map<String, Integer> methods = declarations.get(current);
            Integer access = methods == null ? null : methods.get(key);
            if (access != null) {
                return CHAR.equals(current)
                        && (access & Opcodes.ACC_STATIC) != 0;
            }
            current = parents.get(current);
        }
        return false;
    }

    static Set<String> hitCallerMethods(
            byte[] original,
            Plan plan,
            Map<String, String> parents,
            Map<String, Map<String, Integer>> declarations) {
        LinkedHashSet<String> callers = new LinkedHashSet<>();
        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                String callerKey = methodKey(name, desc);
                return new MethodVisitor(API) {
                    boolean selectedHit;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && resolvesToSelectedHit(
                                        owner,
                                        methodName,
                                        methodDesc,
                                        plan,
                                        parents,
                                        declarations)) {
                            selectedHit = true;
                        }
                    }

                    @Override
                    public void visitEnd() {
                        if (selectedHit) callers.add(callerKey);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return callers;
    }

    static byte[] patch(
            byte[] original,
            Set<String> hitCallers,
            Map<String, String> parents,
            int[] changed) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                if (!hitCallers.contains(methodKey(name, desc))) {
                    return base;
                }
                return new MethodVisitor(API, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && "defenseVerb".equals(methodName)
                                && "()Ljava/lang/String;".equals(methodDesc)
                                && isCharType(owner, parents)) {
                            changed[0]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    MOD_PARRY_RIPOSTE,
                                    "defenseVerb",
                                    DEFENSE_FEEDBACK_DESC,
                                    false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, isInterface);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: SmmParryFeedbackPatcher <target.jar> <out-patches.jar>");
        }
        Path target = Paths.get(args[0]);
        Path output = Paths.get(args[1]);

        LinkedHashMap<String, byte[]> classes = new LinkedHashMap<>();
        HashMap<String, String> parents = new HashMap<>();
        HashMap<String, Map<String, Integer>> declarations = new HashMap<>();

        try (JarFile jar = new JarFile(target.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().startsWith(GAME_ROOT + "/")
                        || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] data;
                try (InputStream in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                ClassReader reader = new ClassReader(data);
                String className = reader.getClassName();
                classes.put(className, data);
                parents.put(className, reader.getSuperName());

                LinkedHashMap<String, Integer> methods = new LinkedHashMap<>();
                reader.accept(new ClassVisitor(API) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc,
                            String signature, String[] exceptions) {
                        methods.put(methodKey(name, desc), access);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                declarations.put(className, methods);
            }
        }

        byte[] charBytes = classes.get(CHAR);
        if (charBytes == null) {
            throw new IllegalStateException("Target JAR is missing Char");
        }
        Plan plan = analyzeChar(charBytes);

        int patchedClasses = 0;
        int patchedCalls = 0;
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> item : classes.entrySet()) {
                String name = item.getKey();
                if (CHAR.equals(name)) continue;

                Set<String> hitCallers = hitCallerMethods(
                        item.getValue(), plan, parents, declarations);
                if (hitCallers.isEmpty()) continue;

                int[] changed = {0};
                byte[] patched = patch(
                        item.getValue(), hitCallers, parents, changed);
                if (changed[0] == 0) continue;

                JarEntry entry = new JarEntry(name + ".class");
                out.putNextEntry(entry);
                out.write(patched);
                out.closeEntry();
                patchedClasses++;
                patchedCalls += changed[0];
            }
        }

        System.out.println(
                "Parry hit-caller feedback JAR patch: "
                        + patchedCalls + " call(s) across "
                        + patchedClasses + " class(es)");
    }
}
'''


def patch_parry_feedback_classes(
    java: Path,
    target: Path,
    work: Path,
    target_game_root: str,
) -> dict[str, bytes]:
    helper = work / "SmmParryFeedbackPatcher.java"
    helper.write_text(
        PARRY_FEEDBACK_HELPER
        .replace("__CHAR__", target_game_root + "/actors/Char")
        .replace("__GAME_ROOT__", target_game_root),
        encoding="utf-8",
    )
    output = work / "parry-feedback-patches.jar"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper,
        target,
        output,
    ])
    if not output.is_file():
        raise injector.InjectError(
            "Parry feedback bytecode helper did not produce a patch JAR"
        )

    patches: dict[str, bytes] = {}
    with zipfile.ZipFile(output) as zf:
        for name in zf.namelist():
            if not name.endswith(".class"):
                continue
            data = zf.read(name)
            if not data.startswith(injector.CLASS_MAGIC):
                raise injector.InjectError(
                    "Invalid Parry feedback patched class: " + name
                )
            patches[name] = data
    return patches


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
    patched_wnd_use_item: tuple[str, Path] | None,
    patched_buff_click: tuple[str, Path],
    patched_modankh: Path,
    feedback_classes: dict[str, bytes],
    payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str,
) -> None:
    root = dungeon_entry[:-len("Dungeon.class")]
    wnd_entry = root + "windows/WndGame.class"
    char_entry = root + "actors/Char.class"
    wnd_bytes = patched_wndgame.read_bytes()
    char_bytes = patched_char.read_bytes()
    wnd_use_item_entry = (
        patched_wnd_use_item[0] if patched_wnd_use_item is not None else None
    )
    wnd_use_item_bytes = (
        patched_wnd_use_item[1].read_bytes()
        if patched_wnd_use_item is not None
        else None
    )
    buff_entry, buff_path = patched_buff_click
    buff_bytes = buff_path.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not wnd_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched WndGame.class is invalid")
    if not char_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Char.class is invalid")
    if (
        wnd_use_item_bytes is not None
        and not wnd_use_item_bytes.startswith(injector.CLASS_MAGIC)
    ):
        raise injector.InjectError("Patched WndUseItem.class is invalid")
    if not buff_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched BuffIndicator button class is invalid")
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
        if buff_entry not in names:
            raise injector.InjectError(f"Target JAR has no {buff_entry}")
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
                elif (
                    wnd_use_item_entry is not None
                    and name == wnd_use_item_entry
                ):
                    data = wnd_use_item_bytes
                elif name == buff_entry:
                    data = buff_bytes
                elif name in feedback_classes:
                    data = feedback_classes[name]
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
    "com/spd/mod/mechanics/ModLastStand.class",
    "com/spd/mod/journal/ModLastStandTag.class",
    "com/spd/mod/journal/ModRuntimeTagStack.class",
}

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
) -> tuple[dict[str, bytes], dict[str, dict[str, bytes]]]:
    available = {
        name for name in donor.namelist()
        if name.startswith(FULL_SMM_CLASS_PREFIX) and name.endswith(".class")
    }
    root = injector.MOD_ANKH_ENTRY
    if root not in available:
        raise injector.InjectError("SMM donor JAR is missing ModAnkh")

    def collect(roots: list[str]) -> set[str]:
        closure: set[str] = set()
        queue = list(roots)
        while queue:
            name = queue.pop()
            if name == root or name in closure:
                continue
            if name not in available:
                raise injector.InjectError(
                    "SMM donor dependency closure is missing: " + name
                )
            closure.add(name)
            for dep in _smm_dependencies(donor.read(name), available):
                if dep != root and dep not in closure:
                    queue.append(dep)
        return closure

    core_roots = list(_smm_dependencies(donor.read(root), available))
    core_roots.extend([
        "com/spd/mod/journal/ModLastStandTag.class",
        "com/spd/mod/journal/ModRuntimeTagStack.class",
    ])
    core_closure = collect(core_roots)
    missing = sorted(ANKH_REQUIRED_ROOTS - core_closure)
    if missing:
        raise injector.InjectError(
            "SMM donor is too old for --ankh-only JAR injection; rebuild the Injection Kit. "
            "Missing ModAnkh dependency root(s): " + ", ".join(missing)
        )

    optional_roots = {
        "parry": "com/spd/mod/mechanics/ModParryRiposte.class",
        "instant": "com/spd/mod/mechanics/ModInstantKill.class",
        "force": "com/spd/mod/mechanics/ModForceHit.class",
        "assassinate": "com/spd/mod/mechanics/ModAssassinate.class",
        "enemy_surge": "com/spd/mod/mechanics/ModEnemySurge.class",
    }
    optional_labels = {
        "parry": "Parry/Riposte",
        "instant": "Instant Kill",
        "force": "Force Hit",
        "assassinate": "Assassinate",
        "enemy_surge": "Enemy Surge",
    }
    optional_closures: dict[str, set[str]] = {}
    for feature, optional_root in optional_roots.items():
        if optional_root in available:
            optional_closures[feature] = collect([optional_root])
        else:
            injector.log(
                f"Optional {optional_labels[feature]} skipped: donor class is missing"
            )

    def rebased(names: set[str]) -> dict[str, bytes]:
        return {
            name: injector.rebase_class_bytes(donor.read(name), target_game_root)
            for name in sorted(names)
        }

    injector.log(
        f"ModAnkh core dependency closure: {len(core_closure)} class(es) "
        "(Store + Loot + Console + Last Stand + Tag)"
    )
    return (
        rebased(core_closure),
        {
            feature: rebased(closure)
            for feature, closure in optional_closures.items()
        },
    )


BUFF_CLICK_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmBuffClickPatcher {
    static final int API = Opcodes.ASM8;
    static final String BUFF = "__BUFF__";
    static final String WND_INFO_BUFF = "__WND_INFO_BUFF__";
    static final String BUFF_INDICATOR_PREFIX = "__BUFF_INDICATOR_PREFIX__";

    static final boolean ENABLE_PARRY = __ENABLE_PARRY__;
    static final boolean ENABLE_INSTANT = __ENABLE_INSTANT__;
    static final boolean ENABLE_FORCE = __ENABLE_FORCE__;
    static final boolean ENABLE_ASSASSINATE = __ENABLE_ASSASSINATE__;
    static final boolean ENABLE_ENEMY_SURGE = __ENABLE_ENEMY_SURGE__;

    static final String LAST_STAND = "com/spd/mod/mechanics/ModLastStand";
    static final String PARRY_RIPOSTE = "com/spd/mod/mechanics/ModParryRiposte";
    static final String INSTANT_KILL = "com/spd/mod/mechanics/ModInstantKill";
    static final String FORCE_HIT = "com/spd/mod/mechanics/ModForceHit";
    static final String ASSASSINATE = "com/spd/mod/mechanics/ModAssassinate";
    static final String ENEMY_SURGE = "com/spd/mod/mechanics/ModEnemySurge";

    static final String INFO_HELPER = "smm$nativeInfo";
    static final String LONG_HELPER = "smm$nativeLongClick";
    static final String LEGACY_INFO_HELPER = "smm$lastStandInfo";

    static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    static LinkedHashMap<String, String> handlers() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put(LAST_STAND, "open");
        if (ENABLE_PARRY) result.put(PARRY_RIPOSTE, "openInfo");
        if (ENABLE_INSTANT) result.put(INSTANT_KILL, "openInfo");
        if (ENABLE_FORCE) result.put(FORCE_HIT, "openInfo");
        if (ENABLE_ASSASSINATE) result.put(ASSASSINATE, "openInfo");
        if (ENABLE_ENEMY_SURGE) result.put(ENEMY_SURGE, "openInfo");
        return result;
    }

    static final class Shape {
        String className;
        String superName;
        String buffField;
        int buffFields;
        int onClickMethods;
        int infoRefs;
        boolean hasLongClick;
        boolean alreadyPatched;

        boolean candidate() {
            return className != null
                    && className.startsWith(BUFF_INDICATOR_PREFIX)
                    && buffFields == 1
                    && onClickMethods == 1
                    && infoRefs > 0;
        }
    }

    static Shape analyze(byte[] bytes) {
        Shape shape = new Shape();
        ClassReader reader = new ClassReader(bytes);
        shape.className = reader.getClassName();
        shape.superName = reader.getSuperName();

        reader.accept(new ClassVisitor(API) {
            @Override
            public FieldVisitor visitField(int access, String name, String desc,
                                           String signature, Object value) {
                if ((access & Opcodes.ACC_STATIC) == 0
                        && ("L" + BUFF + ";").equals(desc)) {
                    shape.buffFields++;
                    shape.buffField = name;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (INFO_HELPER.equals(name)
                        || LONG_HELPER.equals(name)
                        || LEGACY_INFO_HELPER.equals(name)) {
                    shape.alreadyPatched = true;
                }
                if ("onLongClick".equals(name)
                        && "()Z".equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    shape.hasLongClick = true;
                }
                if (!"onClick".equals(name)
                        || !"()V".equals(desc)
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return null;
                }
                shape.onClickMethods++;
                return new MethodVisitor(API) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.NEW && WND_INFO_BUFF.equals(type)) {
                            shape.infoRefs++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (WND_INFO_BUFF.equals(owner) && "<init>".equals(methodName)) {
                            shape.infoRefs++;
                        }
                        String expected = handlers().get(owner);
                        if (expected != null && expected.equals(methodName)
                                && "()V".equals(methodDesc)) {
                            shape.alreadyPatched = true;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return shape;
    }

    static int privateAccess(int access) {
        return (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE;
    }

    static void emitShortHandler(
            MethodVisitor click, Shape shape, String type, String method) {
        Label next = new Label();
        click.visitVarInsn(Opcodes.ALOAD, 0);
        click.visitFieldInsn(
                Opcodes.GETFIELD,
                shape.className,
                shape.buffField,
                "L" + BUFF + ";");
        click.visitTypeInsn(Opcodes.INSTANCEOF, type);
        click.visitJumpInsn(Opcodes.IFEQ, next);
        click.visitVarInsn(Opcodes.ALOAD, 0);
        click.visitFieldInsn(
                Opcodes.GETFIELD,
                shape.className,
                shape.buffField,
                "L" + BUFF + ";");
        click.visitTypeInsn(Opcodes.CHECKCAST, type);
        click.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                type,
                method,
                "()V",
                false);
        click.visitInsn(Opcodes.RETURN);
        click.visitLabel(next);
        click.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
    }

    static void emitLongHandler(MethodVisitor click, Shape shape, String type) {
        Label next = new Label();
        click.visitVarInsn(Opcodes.ALOAD, 0);
        click.visitFieldInsn(
                Opcodes.GETFIELD,
                shape.className,
                shape.buffField,
                "L" + BUFF + ";");
        click.visitTypeInsn(Opcodes.INSTANCEOF, type);
        click.visitJumpInsn(Opcodes.IFEQ, next);
        click.visitVarInsn(Opcodes.ALOAD, 0);
        click.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                shape.className,
                INFO_HELPER,
                "()V",
                false);
        click.visitInsn(Opcodes.ICONST_1);
        click.visitInsn(Opcodes.IRETURN);
        click.visitLabel(next);
        click.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
    }

    static byte[] patch(byte[] original, Shape shape) {
        if (shape.alreadyPatched) {
            throw new IllegalStateException(
                    "BuffIndicator button already contains an SMM click bridge");
        }

        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if ("onClick".equals(name)
                        && "()V".equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    return super.visitMethod(
                            privateAccess(access),
                            INFO_HELPER,
                            desc,
                            signature,
                            exceptions);
                }
                if ("onLongClick".equals(name)
                        && "()Z".equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    return super.visitMethod(
                            privateAccess(access),
                            LONG_HELPER,
                            desc,
                            signature,
                            exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                MethodVisitor click = super.visitMethod(
                        Opcodes.ACC_PROTECTED, "onClick", "()V", null, null);
                click.visitCode();
                for (Map.Entry<String, String> handler : handlers().entrySet()) {
                    emitShortHandler(click, shape, handler.getKey(), handler.getValue());
                }
                click.visitVarInsn(Opcodes.ALOAD, 0);
                click.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        shape.className,
                        INFO_HELPER,
                        "()V",
                        false);
                click.visitInsn(Opcodes.RETURN);
                click.visitMaxs(2, 1);
                click.visitEnd();

                MethodVisitor longClick = super.visitMethod(
                        Opcodes.ACC_PROTECTED, "onLongClick", "()Z", null, null);
                longClick.visitCode();
                for (String type : handlers().keySet()) {
                    emitLongHandler(longClick, shape, type);
                }
                longClick.visitVarInsn(Opcodes.ALOAD, 0);
                if (shape.hasLongClick) {
                    longClick.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            shape.className,
                            LONG_HELPER,
                            "()Z",
                            false);
                } else {
                    longClick.visitMethodInsn(
                            Opcodes.INVOKESPECIAL,
                            shape.superName,
                            "onLongClick",
                            "()Z",
                            false);
                }
                longClick.visitInsn(Opcodes.IRETURN);
                longClick.visitMaxs(2, 1);
                longClick.visitEnd();

                super.visitEnd();
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SmmBuffClickPatcher <target.jar> <out-class> <status.txt>");
        }

        Path target = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Path status = Paths.get(args[2]);

        ArrayList<String> candidates = new ArrayList<>();
        LinkedHashMap<String, byte[]> bytesByEntry = new LinkedHashMap<>();
        LinkedHashMap<String, Shape> shapeByEntry = new LinkedHashMap<>();

        try (JarFile jar = new JarFile(target.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || !entry.getName().startsWith(BUFF_INDICATOR_PREFIX)
                        || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes = read(jar, entry);
                Shape shape = analyze(bytes);
                if (shape.candidate()) {
                    candidates.add(entry.getName());
                    bytesByEntry.put(entry.getName(), bytes);
                    shapeByEntry.put(entry.getName(), shape);
                }
            }
        }

        if (candidates.size() != 1) {
            throw new IllegalStateException(
                    "Expected one BuffIndicator button with Buff field + native info click, found "
                            + candidates.size() + ": " + candidates);
        }

        String entry = candidates.get(0);
        Shape shape = shapeByEntry.get(entry);
        Files.write(output, patch(bytesByEntry.get(entry), shape));
        Files.writeString(status, entry + "\n");

        System.out.println(
                "SMM BuffIndicator click hook: OK (" + entry
                        + "; handlers=" + handlers().keySet() + ")");
    }
}
'''

WND_USE_ITEM_ACTION_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmWndUseItemActionPatcher {
    static final int API = Opcodes.ASM8;
    static final String WND_USE_ITEM = "__WND_USE_ITEM__";
    static final String ITEM = "__ITEM__";
    static final String HERO = "__HERO__";
    static final String DUNGEON = "__DUNGEON__";
    static final String MESSAGES = "__MESSAGES__";

    static final String HERO_DESC = "L" + HERO + ";";
    static final String ACTION_NAME_DESC =
            "(Ljava/lang/String;" + HERO_DESC + ")Ljava/lang/String;";
    static final String MESSAGE_GET_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";
    static final String ACTION_HELPER = "smm$actionName";

    static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) throw new IOException("Missing JAR entry: " + entryName);
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static boolean itemActionNameAvailable(Path target) throws IOException {
        byte[] item = readJarEntry(target, ITEM + ".class");
        final int[] matches = {0};
        new ClassReader(item).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if ("actionName".equals(name)
                        && ACTION_NAME_DESC.equals(desc)
                        && (access & Opcodes.ACC_PUBLIC) != 0
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    matches[0]++;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return matches[0] == 1;
    }

    static final class Analysis {
        final LinkedHashSet<String> legacyConstructors = new LinkedHashSet<>();
        boolean helperExists;
    }

    static boolean containsLegacyKey(Object value) {
        return value instanceof String && ((String) value).contains("ac_");
    }

    static Analysis analyze(byte[] original) {
        ClassReader reader = new ClassReader(original);
        if (!WND_USE_ITEM.equals(reader.getClassName())) {
            throw new IllegalStateException(
                    "Target class is not WndUseItem: " + reader.getClassName());
        }

        Analysis analysis = new Analysis();
        reader.accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (ACTION_HELPER.equals(name) && MESSAGE_GET_DESC.equals(desc)) {
                    analysis.helperExists = true;
                }
                if (!"<init>".equals(name) || (access & Opcodes.ACC_STATIC) != 0) {
                    return null;
                }

                return new MethodVisitor(API) {
                    boolean hasLegacyKey;
                    boolean hasMessagesGet;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (containsLegacyKey(value)) {
                            hasLegacyKey = true;
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(
                            String name,
                            String methodDesc,
                            Handle bootstrapMethodHandle,
                            Object... bootstrapMethodArguments) {
                        for (Object argument : bootstrapMethodArguments) {
                            if (containsLegacyKey(argument)) {
                                hasLegacyKey = true;
                            }
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && MESSAGES.equals(owner)
                                && "get".equals(methodName)
                                && MESSAGE_GET_DESC.equals(methodDesc)) {
                            hasMessagesGet = true;
                        }
                    }

                    @Override
                    public void visitEnd() {
                        if (hasLegacyKey && hasMessagesGet) {
                            analysis.legacyConstructors.add(desc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return analysis;
    }

    static byte[] patch(byte[] original, Set<String> legacyConstructors, int[] rewrites) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"<init>".equals(name)
                        || (access & Opcodes.ACC_STATIC) != 0
                        || !legacyConstructors.contains(desc)) {
                    return base;
                }

                return new MethodVisitor(API, base) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && MESSAGES.equals(owner)
                                && "get".equals(methodName)
                                && MESSAGE_GET_DESC.equals(methodDesc)) {
                            rewrites[0]++;
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    WND_USE_ITEM,
                                    ACTION_HELPER,
                                    MESSAGE_GET_DESC,
                                    false);
                            return;
                        }
                        super.visitMethodInsn(
                                opcode, owner, methodName, methodDesc, isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                MethodVisitor mv = super.visitMethod(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        ACTION_HELPER,
                        MESSAGE_GET_DESC,
                        null,
                        null);
                mv.visitCode();

                Label fallback = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitJumpInsn(Opcodes.IFNULL, fallback);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, ITEM);
                mv.visitJumpInsn(Opcodes.IFEQ, fallback);

                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitLdcInsn("ac_");
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "startsWith",
                        "(Ljava/lang/String;)Z",
                        false);
                mv.visitJumpInsn(Opcodes.IFEQ, fallback);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitTypeInsn(Opcodes.CHECKCAST, ITEM);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitInsn(Opcodes.ICONST_3);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/String",
                        "substring",
                        "(I)Ljava/lang/String;",
                        false);
                mv.visitFieldInsn(
                        Opcodes.GETSTATIC,
                        DUNGEON,
                        "hero",
                        HERO_DESC);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        ITEM,
                        "actionName",
                        ACTION_NAME_DESC,
                        false);
                mv.visitInsn(Opcodes.ARETURN);

                mv.visitLabel(fallback);
                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        MESSAGES,
                        "get",
                        MESSAGE_GET_DESC,
                        false);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(3, 3);
                mv.visitEnd();

                super.visitEnd();
            }
        };

        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: SmmWndUseItemActionPatcher <target.jar> <out-class> <status.txt>");
        }

        Path target = Paths.get(args[0]);
        Path output = Paths.get(args[1]);
        Path status = Paths.get(args[2]);

        byte[] original = readJarEntry(target, WND_USE_ITEM + ".class");
        Analysis analysis = analyze(original);
        if (analysis.helperExists) {
            throw new IllegalStateException(
                    "WndUseItem already contains SMM action-name compatibility bridge");
        }
        if (analysis.legacyConstructors.isEmpty()) {
            Files.writeString(status, "native\n");
            System.out.println(
                    "WndUseItem action labels: native actionName-compatible path");
            return;
        }
        if (!itemActionNameAvailable(target)) {
            throw new IllegalStateException(
                    "Legacy WndUseItem bypasses Item.actionName(), but target Item "
                    + "does not expose public actionName(String, Hero)");
        }

        int[] rewrites = {0};
        byte[] patched = patch(original, analysis.legacyConstructors, rewrites);
        if (rewrites[0] == 0) {
            throw new IllegalStateException(
                    "Legacy WndUseItem constructor was identified, but no Messages.get "
                    + "call was rewritten");
        }

        Files.write(output, patched);
        Files.writeString(status, "patched\n");
        System.out.println(
                "WndUseItem action labels: bridged "
                        + rewrites[0]
                        + " legacy Messages.get call(s) through Item.actionName()");
    }
}
'''


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



ANKH_CHAR_HELPER = r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;

public class SmmAnkhCharAttackPatcher {
    static final int API = Opcodes.ASM8;
    static final String CHAR = "__CHAR__";
    static final String MOD_PARRY_RIPOSTE = "com/spd/mod/mechanics/ModParryRiposte";
    static final String MOD_INSTANT_KILL = "com/spd/mod/mechanics/ModInstantKill";
    static final String MOD_FORCE_HIT = "com/spd/mod/mechanics/ModForceHit";

    static final String MODERN_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";FZ)Z";
    static final String LEGACY_HIT_DESC = "(L" + CHAR + ";L" + CHAR + ";Z)Z";
    static final String COMBAT_HOOK_DESC = "(L" + CHAR + ";L" + CHAR + ";)Z";
    static final String INCOMING_DESC = "(L" + CHAR + ";L" + CHAR + ";)V";
    static final String FORCE_ACTIVE_DESC = "(L" + CHAR + ";)Z";
    static final String FINISH_ATTACK_DESC = "(Z)V";
    static final String NO_ARGS_VOID_DESC = "()V";
    static final String DEFENSE_FEEDBACK_DESC = "(L" + CHAR + ";)Ljava/lang/String;";

    static byte[] readJarEntry(Path jarPath, String entryName) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    static boolean hasPublicStaticHook(
            Path payloadJar, String owner, String methodName, String expectedDesc) throws IOException {
        byte[] bytes = readJarEntry(payloadJar, owner + ".class");
        if (bytes == null) return false;

        final int[] matches = {0};
        final int[] valid = {0};
        new ClassReader(bytes).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (methodName.equals(name) && expectedDesc.equals(desc)) {
                    matches[0]++;
                    if ((access & Opcodes.ACC_PUBLIC) != 0
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        valid[0]++;
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return matches[0] == 1 && valid[0] == 1;
    }

    static boolean isCharAttack(String name, String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return "attack".equals(name)
                && (access & Opcodes.ACC_STATIC) == 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 1
                && ("L" + CHAR + ";").equals(args[0].getDescriptor());
    }

    static boolean isStructuralHit(String desc, int access) {
        Type[] args = Type.getArgumentTypes(desc);
        return (access & Opcodes.ACC_STATIC) != 0
                && Type.BOOLEAN_TYPE.equals(Type.getReturnType(desc))
                && args.length >= 2
                && ("L" + CHAR + ";").equals(args[0].getDescriptor())
                && ("L" + CHAR + ";").equals(args[1].getDescriptor());
    }

    static final class Scan {
        boolean alreadyParry;
        boolean alreadyInstant;
        boolean alreadyForce;
        String terminalDesc;
        String hitMethod;
        String hitDesc;
        String hitDetail;
    }

    static Scan scan(byte[] original) {
        Scan scan = new Scan();
        LinkedHashSet<String> attacks = new LinkedHashSet<>();
        LinkedHashSet<String> hitCandidates = new LinkedHashSet<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackEdges = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> attackHitCalls = new LinkedHashMap<>();

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                if (isCharAttack(name, desc, access)) {
                    attacks.add(desc);
                    attackEdges.put(desc, new LinkedHashSet<>());
                    attackHitCalls.put(desc, new LinkedHashSet<>());
                }
                if (isStructuralHit(desc, access)) {
                    hitCandidates.add(name + "\n" + desc);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        new ClassReader(original).accept(new ClassVisitor(API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(API) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean isInterface) {
                        if (MOD_PARRY_RIPOSTE.equals(owner)
                                && "onHitCheck".equals(methodName)) {
                            scan.alreadyParry = true;
                        }
                        if (MOD_INSTANT_KILL.equals(owner)
                                && ("beginAttack".equals(methodName)
                                    || "finishAttack".equals(methodName)
                                    || "resolveSuccessfulAttack".equals(methodName))) {
                            scan.alreadyInstant = true;
                        }
                        if (MOD_FORCE_HIT.equals(owner)
                                && "forceHitCheck".equals(methodName)) {
                            scan.alreadyForce = true;
                        }
                        if (!isCharAttack(name, desc, access) || !CHAR.equals(owner)) {
                            return;
                        }
                        if ("attack".equals(methodName) && attacks.contains(methodDesc)) {
                            attackEdges.get(desc).add(methodDesc);
                        }
                        String key = methodName + "\n" + methodDesc;
                        if (opcode == Opcodes.INVOKESTATIC && hitCandidates.contains(key)) {
                            attackHitCalls.get(desc).add(key);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String modernDirect = "hit\n" + MODERN_HIT_DESC;
        String legacyDirect = "hit\n" + LEGACY_HIT_DESC;
        String selected = hitCandidates.contains(modernDirect)
                ? modernDirect
                : (hitCandidates.contains(legacyDirect) ? legacyDirect : null);

        LinkedHashSet<String> terminals = new LinkedHashSet<>();
        for (String desc : attacks) {
            if (attackEdges.get(desc).isEmpty()) terminals.add(desc);
        }
        if (terminals.size() == 1) {
            scan.terminalDesc = terminals.iterator().next();
        }

        if (selected != null) {
            scan.hitDetail = "direct hit-check ";
        } else if (scan.terminalDesc != null) {
            LinkedHashSet<String> calledHits = attackHitCalls.get(scan.terminalDesc);
            if (calledHits.size() == 1) {
                selected = calledHits.iterator().next();
                scan.hitDetail = "structural hit-check ";
            } else {
                scan.hitDetail = "expected one callable hit-check in terminal Char.attack"
                        + scan.terminalDesc + ", found " + calledHits.size()
                        + ": " + calledHits;
            }
        } else {
            scan.hitDetail = "no direct hit-check and expected one terminal Char.attack, found "
                    + terminals.size();
        }

        if (selected != null) {
            int split = selected.indexOf('\n');
            scan.hitMethod = selected.substring(0, split);
            scan.hitDesc = selected.substring(split + 1);
            scan.hitDetail += scan.hitMethod + scan.hitDesc;
        }
        return scan;
    }

    static byte[] patch(
            byte[] original,
            boolean parry,
            boolean instant,
            boolean force,
            Scan scan) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);

        ClassVisitor visitor = new ClassVisitor(API, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);

                if (parry
                        && scan.terminalDesc != null
                        && "attack".equals(name)
                        && scan.terminalDesc.equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    base = new MethodVisitor(API, base) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean isInterface) {
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && CHAR.equals(owner)
                                    && "defenseVerb".equals(methodName)
                                    && "()Ljava/lang/String;".equals(methodDesc)) {
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_PARRY_RIPOSTE,
                                        "defenseVerb",
                                        DEFENSE_FEEDBACK_DESC,
                                        false);
                                return;
                            }
                            super.visitMethodInsn(
                                    opcode, owner, methodName, methodDesc, isInterface);
                        }
                    };
                }

                if (instant
                        && scan.terminalDesc != null
                        && "attack".equals(name)
                        && scan.terminalDesc.equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    return new MethodVisitor(API, base) {
                        @Override
                        public void visitCode() {
                            super.visitCode();

                            if (instant) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_INSTANT_KILL,
                                        "beginAttack",
                                        INCOMING_DESC,
                                        false);
                            }

                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                if (instant) {
                                    // Keep the original boolean on stack and pass a
                                    // duplicate into the context completion helper.
                                    super.visitInsn(Opcodes.DUP);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            MOD_INSTANT_KILL,
                                            "finishAttack",
                                            FINISH_ATTACK_DESC,
                                            false);
                                }
                            }
                            super.visitInsn(opcode);
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 3, maxLocals);
                        }
                    };
                }

                if ((force || parry)
                        && scan.hitMethod.equals(name)
                        && scan.hitDesc.equals(desc)
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    return new MethodVisitor(API, base) {
                        @Override
                        public void visitCode() {
                            super.visitCode();

                            if (parry) {
                                Label noParry = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_PARRY_RIPOSTE,
                                        "onHitCheck",
                                        COMBAT_HOOK_DESC,
                                        false);
                                super.visitJumpInsn(Opcodes.IFEQ, noParry);

                                if (force) {
                                    Label parryMiss = new Label();
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitVarInsn(Opcodes.ALOAD, 1);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            MOD_FORCE_HIT,
                                            "forceHitCheck",
                                            COMBAT_HOOK_DESC,
                                            false);
                                    super.visitJumpInsn(Opcodes.IFEQ, parryMiss);
                                    super.visitInsn(Opcodes.ICONST_1);
                                    super.visitInsn(Opcodes.IRETURN);
                                    super.visitLabel(parryMiss);
                                    super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                }
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(noParry);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }

                            if (force) {
                                Label nativeHit = new Label();
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        MOD_FORCE_HIT,
                                        "forceHitCheck",
                                        COMBAT_HOOK_DESC,
                                        false);
                                super.visitJumpInsn(Opcodes.IFEQ, nativeHit);
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitInsn(Opcodes.IRETURN);
                                super.visitLabel(nativeHit);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        }

                        @Override
                        public void visitMaxs(int maxStack, int maxLocals) {
                            super.visitMaxs(maxStack + 2, maxLocals);
                        }
                    };
                }

                return base;
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: SmmAnkhCharAttackPatcher <target.jar> <payload.jar> "
                            + "<out-Char.class> <status.txt>");
        }

        Path target = Paths.get(args[0]);
        Path payload = Paths.get(args[1]);
        Path output = Paths.get(args[2]);
        Path status = Paths.get(args[3]);

        byte[] original = readJarEntry(target, CHAR + ".class");
        if (original == null) {
            throw new IOException("Missing JAR entry: " + CHAR + ".class");
        }

        boolean donorParry = hasPublicStaticHook(
                payload, MOD_PARRY_RIPOSTE, "onHitCheck", COMBAT_HOOK_DESC)
                && hasPublicStaticHook(
                        payload, MOD_PARRY_RIPOSTE, "defenseVerb",
                        DEFENSE_FEEDBACK_DESC);
        boolean donorInstant = hasPublicStaticHook(
                payload, MOD_INSTANT_KILL, "resolveSuccessfulAttack",
                COMBAT_HOOK_DESC)
                && hasPublicStaticHook(
                        payload, MOD_INSTANT_KILL, "beginAttack", INCOMING_DESC)
                && hasPublicStaticHook(
                        payload, MOD_INSTANT_KILL, "finishAttack", FINISH_ATTACK_DESC);
        boolean donorForce = hasPublicStaticHook(
                payload, MOD_FORCE_HIT, "forceHitCheck", COMBAT_HOOK_DESC);
        Scan scan = scan(original);

        boolean parry = donorParry
                && !scan.alreadyParry
                && scan.hitMethod != null
                && scan.hitDesc != null;
        boolean instant = donorInstant
                && !scan.alreadyInstant
                && scan.terminalDesc != null;
        boolean force = donorForce
                && !scan.alreadyForce
                && scan.hitMethod != null
                && scan.hitDesc != null;

        if (parry) {
            System.out.println(
                    "Optional Parry/Riposte unified hit hook: supported - "
                            + scan.hitDetail);
        } else {
            System.out.println(
                    "Optional Parry/Riposte skipped: "
                            + (scan.hitDetail == null ? "no compatible hit-check" : scan.hitDetail));
        }
        if (instant) {
            System.out.println(
                    "Optional Instant Kill attack-context return hook: supported - "
                            + scan.terminalDesc);
        } else {
            System.out.println(
                    "Optional Instant Kill skipped: no compatible terminal Char.attack");
        }
        if (force) {
            System.out.println(
                    "Optional Force Hit hook: supported - " + scan.hitDetail);
        } else {
            System.out.println(
                    "Optional Force Hit skipped: "
                            + (scan.hitDetail == null ? "no compatible hit-check" : scan.hitDetail));
        }

        Files.write(output, patch(original, parry, instant, force, scan));
        Files.writeString(
                status,
                "parry=" + (parry ? "1" : "0") + "\n"
                        + "instant=" + (instant ? "1" : "0") + "\n"
                        + "force=" + (force ? "1" : "0") + "\n");
    }
}
'''

def patch_buff_click_jar(
    java: Path,
    target: Path,
    work: Path,
    target_game_root: str,
    *,
    parry: bool,
    instant: bool,
    force: bool,
    assassinate: bool,
    enemy_surge: bool,
) -> tuple[str, Path]:
    helper = work / "SmmBuffClickPatcher.java"
    helper.write_text(
        BUFF_CLICK_HELPER
        .replace("__BUFF__", target_game_root + "/actors/buffs/Buff")
        .replace("__WND_INFO_BUFF__", target_game_root + "/windows/WndInfoBuff")
        .replace("__BUFF_INDICATOR_PREFIX__", target_game_root + "/ui/BuffIndicator$")
        .replace("__ENABLE_PARRY__", str(parry).lower())
        .replace("__ENABLE_INSTANT__", str(instant).lower())
        .replace("__ENABLE_FORCE__", str(force).lower())
        .replace("__ENABLE_ASSASSINATE__", str(assassinate).lower())
        .replace("__ENABLE_ENEMY_SURGE__", str(enemy_surge).lower()),
        encoding="utf-8",
    )

    output = work / "SmmBuffButton.class"
    status = work / "smm-buff-click-entry.txt"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper, target, output, status,
    ])

    if not output.is_file() or not output.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError(
            "SMM BuffIndicator helper did not produce a valid class"
        )
    if not status.is_file():
        raise injector.InjectError(
            "SMM BuffIndicator helper did not report the patched entry"
        )

    entry = status.read_text(encoding="utf-8").strip()
    prefix = target_game_root + "/ui/BuffIndicator$"
    if not entry.startswith(prefix) or not entry.endswith(".class"):
        raise injector.InjectError(
            "SMM BuffIndicator helper reported an invalid entry: " + entry
        )
    return entry, output


def patch_wnd_use_item_action_names(
    java: Path,
    target: Path,
    work: Path,
    target_game_root: str,
) -> tuple[str, Path] | None:
    wnd_entry = target_game_root + "/windows/WndUseItem.class"
    helper = work / "SmmWndUseItemActionPatcher.java"
    helper.write_text(
        WND_USE_ITEM_ACTION_HELPER
        .replace("__WND_USE_ITEM__", target_game_root + "/windows/WndUseItem")
        .replace("__ITEM__", target_game_root + "/items/Item")
        .replace("__HERO__", target_game_root + "/actors/hero/Hero")
        .replace("__DUNGEON__", target_game_root + "/Dungeon")
        .replace("__MESSAGES__", target_game_root + "/messages/Messages"),
        encoding="utf-8",
    )

    output = work / "WndUseItemAction.class"
    status = work / "wnduseitem-action-status.txt"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper, target, output, status,
    ])
    if not status.is_file():
        raise injector.InjectError(
            "WndUseItem action helper did not report compatibility status"
        )

    mode = status.read_text(encoding="utf-8").strip()
    if mode == "native":
        return None
    if mode != "patched":
        raise injector.InjectError(
            "WndUseItem action helper reported unknown status: " + mode
        )
    if not output.is_file() or not output.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError(
            "WndUseItem action helper did not produce a valid class"
        )
    return wnd_entry, output


def patch_ankh_char(
    java: Path,
    target: Path,
    payload_jar: Path,
    work: Path,
    target_game_root: str,
) -> tuple[Path | None, set[str]]:
    char_name = target_game_root + "/actors/Char"
    helper = work / "SmmAnkhCharAttackPatcher.java"
    helper.write_text(
        ANKH_CHAR_HELPER.replace("__CHAR__", char_name),
        encoding="utf-8",
    )
    output = work / "AnkhChar.class"
    status = work / "ankh-char-features.txt"
    injector.run([
        java,
        "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
        helper, target, payload_jar, output, status,
    ])
    if not output.is_file() or not output.read_bytes().startswith(injector.CLASS_MAGIC):
        raise injector.InjectError(
            "Ankh-only Char bytecode helper did not produce a valid class"
        )
    if not status.is_file():
        raise injector.InjectError(
            "Ankh-only Char bytecode helper did not report optional feature status"
        )

    enabled = set()
    for line in status.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and value == "1":
            enabled.add(key)

    return (output if enabled else None), enabled


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
    patched_char: Path | None,
    patched_wnd_use_item: tuple[str, Path] | None,
    patched_buff_click: tuple[str, Path],
    patched_modankh: Path,
    feedback_classes: dict[str, bytes],
    payload: dict[str, bytes],
    output: Path,
    dungeon_entry: str,
) -> None:
    dungeon_bytes = patched_dungeon.read_bytes()
    char_bytes = patched_char.read_bytes() if patched_char is not None else None
    wnd_use_item_entry = (
        patched_wnd_use_item[0] if patched_wnd_use_item is not None else None
    )
    wnd_use_item_bytes = (
        patched_wnd_use_item[1].read_bytes()
        if patched_wnd_use_item is not None
        else None
    )
    buff_entry, buff_path = patched_buff_click
    buff_bytes = buff_path.read_bytes()
    modankh_bytes = patched_modankh.read_bytes()
    if not dungeon_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Dungeon.class is invalid")
    if char_bytes is not None and not char_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched Char.class is invalid")
    if (
        wnd_use_item_bytes is not None
        and not wnd_use_item_bytes.startswith(injector.CLASS_MAGIC)
    ):
        raise injector.InjectError("Patched WndUseItem.class is invalid")
    if not buff_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched BuffIndicator button class is invalid")
    if not modankh_bytes.startswith(injector.CLASS_MAGIC):
        raise injector.InjectError("Patched ModAnkh.class is invalid")
    for name, data in payload.items():
        if not data.startswith(injector.CLASS_MAGIC):
            raise injector.InjectError(f"Invalid ModAnkh dependency class: {name}")

    with zipfile.ZipFile(target, "r") as zin:
        names = set(zin.namelist())
        if dungeon_entry not in names:
            raise injector.InjectError(f"Target JAR has no {dungeon_entry}")
        root = dungeon_entry[:-len("Dungeon.class")]
        char_entry = root + "actors/Char.class"
        if char_entry not in names:
            raise injector.InjectError(f"Target JAR has no {char_entry}")
        if buff_entry not in names:
            raise injector.InjectError(f"Target JAR has no {buff_entry}")
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
                if info.filename == dungeon_entry:
                    data = dungeon_bytes
                elif info.filename == char_entry and char_bytes is not None:
                    data = char_bytes
                elif (
                    wnd_use_item_entry is not None
                    and info.filename == wnd_use_item_entry
                ):
                    data = wnd_use_item_bytes
                elif info.filename == buff_entry:
                    data = buff_bytes
                elif info.filename in feedback_classes:
                    data = feedback_classes[info.filename]
                else:
                    data = zin.read(info.filename)
                zout.writestr(injector.clone_zipinfo(info), data)

            zout.writestr(
                injector.clone_zipinfo(dungeon_info, injector.MOD_ANKH_ENTRY), modankh_bytes
            )
            for name in sorted(payload):
                zout.writestr(injector.clone_zipinfo(dungeon_info, name), payload[name])


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
        core_payload, optional_payloads = build_ankh_payload(
            donor, target_game_root
        )

    raw_core_jar = work / "rebased-ankh-core-payload.jar"
    injector.write_helper_payload_jar(raw_core_jar, core_payload)
    core_helper_tmp, core_payload = adapt_ankh_payload(
        java, target, raw_core_jar, work, target_game_root
    )
    core_helper = work / "adapted-ankh-core-payload.jar"
    shutil.copy2(core_helper_tmp, core_helper)

    adapted_optional: dict[str, dict[str, bytes]] = {}
    feature_labels = {
        "parry": "Parry/Riposte",
        "instant": "Instant Kill",
        "force": "Force Hit",
        "assassinate": "Assassinate",
        "enemy_surge": "Enemy Surge",
    }
    for feature, feature_payload in optional_payloads.items():
        raw_feature_jar = work / f"rebased-ankh-{feature}-payload.jar"
        injector.write_helper_payload_jar(raw_feature_jar, feature_payload)
        try:
            _feature_helper, adapted = adapt_ankh_payload(
                java, target, raw_feature_jar, work, target_game_root
            )
        except injector.InjectError as exc:
            injector.log(
                f"Optional {feature_labels.get(feature, feature)} skipped "
                f"during payload adaptation: {exc}"
            )
            continue
        adapted_optional[feature] = adapted

    probe_payload: dict[str, bytes] = {}
    for feature_payload in adapted_optional.values():
        probe_payload.update(feature_payload)
    probe_payload_jar = work / "rebased-ankh-optional-probe.jar"
    injector.write_helper_payload_jar(probe_payload_jar, probe_payload)

    injector.step("Adapting and validating donor ModAnkh against target JAR")
    patched_modankh, patched_dungeon = _original_patch_classes(
        java, target, core_helper, donor_modankh, work, target_game_root
    )
    patched_wnd_use_item = patch_wnd_use_item_action_names(
        java, target, work, target_game_root
    )
    try:
        patched_char, enabled_features = patch_ankh_char(
            java, target, probe_payload_jar, work, target_game_root
        )
    except injector.InjectError as exc:
        injector.log(
            "Optional combat Char patch skipped; core injection continues: "
            + str(exc)
        )
        patched_char = None
        enabled_features = set()

    payload_features = set(enabled_features)
    if "assassinate" in adapted_optional:
        payload_features.add("assassinate")
    if "enemy_surge" in adapted_optional:
        payload_features.add("enemy_surge")

    patched_buff_click = patch_buff_click_jar(
        java,
        target,
        work,
        target_game_root,
        parry="parry" in payload_features,
        instant="instant" in payload_features,
        force="force" in payload_features,
        assassinate="assassinate" in payload_features,
        enemy_surge="enemy_surge" in payload_features,
    )
    feedback_classes = (
        patch_parry_feedback_classes(java, target, work, target_game_root)
        if "parry" in payload_features
        else {}
    )

    payload = dict(core_payload)
    for feature in sorted(payload_features):
        payload.update(adapted_optional.get(feature, {}))

    injector.step("Repacking target JAR")
    output.parent.mkdir(parents=True, exist_ok=True)
    tmp = work / "output-ankh.jar"
    rebuild_ankh_jar(
        target,
        patched_dungeon,
        patched_char,
        patched_wnd_use_item,
        patched_buff_click,
        patched_modankh,
        feedback_classes,
        payload,
        tmp,
        dungeon_entry,
    )
    char_entry = target_game_root + "/actors/Char.class"
    validate_entries = [
        dungeon_entry,
        char_entry,
        injector.MOD_ANKH_ENTRY,
        *sorted(feedback_classes),
        *sorted(payload),
    ]
    if patched_wnd_use_item is not None:
        validate_entries.append(patched_wnd_use_item[0])
    validate_entries.append(patched_buff_click[0])
    injector.validate_jar(tmp, validate_entries)
    shutil.copy2(tmp, output)

    injector.step("Done")
    injector.log(f"Output : {output}")
    injector.log(f"SHA-256: {injector.sha256(output)}")
    injector.log(
        f"Injected: ModAnkh only "
        f"(core: Store + Loot + Console + Last Stand; "
        f"optional payloads: {', '.join(sorted(payload_features)) or 'none'}; "
        f"Char hooks: {', '.join(sorted(enabled_features)) or 'none'}; "
        f"{len(payload)} dependency classes)"
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
        "  --ankh-only   inject ModAnkh + Last Stand/Tag core; add Parry/Riposte, Instant Kill, Force Hit, Assassinate, and Enemy Surge when compatible\n\n"
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
            "ModAnkh only (core: Store + Loot + Console + Last Stand + Tag; optional: Parry/Riposte, Instant Kill, Force Hit, Assassinate, Enemy Surge)"
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
        patched_wnd_use_item = patch_wnd_use_item_action_names(
            java, target, work, target_game_root
        )
        patched_buff_click = patch_buff_click_jar(
            java,
            target,
            work,
            target_game_root,
            parry=True,
            instant=True,
            force=True,
            assassinate=True,
            enemy_surge=True,
        )
        feedback_classes = patch_parry_feedback_classes(
            java, target, work, target_game_root
        )

        injector.step("Repacking target JAR")
        output.parent.mkdir(parents=True, exist_ok=True)
        unsigned_tmp = work / "output.jar"
        rebuild_full_jar(
            target,
            patched_wndgame,
            patched_char,
            patched_wnd_use_item,
            patched_buff_click,
            patched_modankh,
            feedback_classes,
            payload,
            unsigned_tmp,
            dungeon_entry,
        )
        full_validate_entries = [
            wnd_entry,
            char_entry,
            patched_buff_click[0],
            *sorted(feedback_classes),
            injector.MOD_ANKH_ENTRY,
            *payload_names,
        ]
        if patched_wnd_use_item is not None:
            full_validate_entries.append(patched_wnd_use_item[0])
        injector.validate_jar(unsigned_tmp, full_validate_entries)
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
