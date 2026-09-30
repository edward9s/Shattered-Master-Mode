#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import re
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import inject_apk as mod


class TerminalAttackHookTest(unittest.TestCase):
    game = "Lcom/shatteredpixel/shatteredpixeldungeon/"
    char = game + "actors/Char;"
    damage_type = char[:-1] + "$DamageType;"

    @staticmethod
    def cls(text: str):
        return mod.injector.SmaliClass.from_text(pathlib.Path("Char.smali"), text)

    def test_mlpd_wrappers_converge_on_five_argument_terminal(self):
        terminal = f"({self.char}FFF{self.damage_type})Z"
        wrappers = [
            f"({self.char})Z",
            f"({self.char}F)Z",
            f"({self.char}FFF)Z",
        ]
        chunks = [
            f".class public {self.char}\n",
            ".super Ljava/lang/Object;\n",
        ]
        for proto in wrappers:
            chunks.extend([
                f".method public attack{proto}\n",
                "    .locals 1\n",
                f"    invoke-virtual/range {{p0 .. p1}}, {self.char}->attack{terminal}\n",
                "    move-result v0\n",
                "    return v0\n",
                ".end method\n",
            ])
        chunks.extend([
            f".method public attack{terminal}\n",
            "    .locals 1\n",
            "    const/4 v0, 0x1\n",
            "    return v0\n",
            ".end method\n",
        ])
        text = "".join(chunks)
        char_class = self.cls(text)
        capability = mod._probe_char_attack_hook({self.char: char_class}, self.game)
        self.assertEqual(mod.ABI_DIRECT, capability.strategy)
        self.assertEqual(terminal, capability.data.get("proto"))

        patched = mod.patch_char_attack(text, self.char)
        pre_hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttack("
            + self.char + self.char + ")V"
        )
        post_hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttackComplete("
            + self.char + self.char + ")V"
        )
        self.assertEqual(1, patched.count(pre_hook))
        self.assertEqual(1, patched.count(post_hook))
        for proto in wrappers:
            _, _, block = mod.injector.method_block(patched, "attack", proto)
            self.assertNotIn(pre_hook, block)
            self.assertNotIn(post_hook, block)
        _, _, block = mod.injector.method_block(patched, "attack", terminal)
        self.assertIn(pre_hook, block)
        self.assertIn(post_hook, block)
        self.assertLess(block.index(pre_hook), block.index("const/4 v0, 0x1"))
        self.assertLess(block.index(post_hook), block.index("return v0"))


    def test_completion_hook_is_added_before_every_terminal_return(self):
        terminal = f"({self.char}FFF{self.damage_type})Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{terminal}\n"
            "    .locals 1\n"
            "    if-eqz p1, :miss\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        patched = mod.patch_char_attack(text, self.char)
        post_hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttackComplete("
            + self.char + self.char + ")V"
        )
        _, _, block = mod.injector.method_block(patched, "attack", terminal)
        self.assertEqual(2, block.count(post_hook))
        self.assertEqual(2, len(re.findall(
            re.escape(post_hook) + r"\n\s*return v0", block
        )))

    def test_multiple_terminal_overloads_are_rejected(self):
        first = f"({self.char})Z"
        second = f"({self.char}F)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{first}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
            f".method public attack{second}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_char_attack_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_UNSUPPORTED, capability.strategy)
        self.assertIn("exactly one terminal", capability.detail)

    def test_cyclic_overload_delegation_is_rejected(self):
        first = f"({self.char})Z"
        second = f"({self.char}F)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{first}\n"
            "    .locals 1\n"
            f"    invoke-virtual {{p0, p1}}, {self.char}->attack{second}\n"
            "    move-result v0\n"
            "    return v0\n"
            ".end method\n"
            f".method public attack{second}\n"
            "    .locals 1\n"
            f"    invoke-virtual {{p0, p1}}, {self.char}->attack{first}\n"
            "    move-result v0\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_char_attack_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_UNSUPPORTED, capability.strategy)
        self.assertIn("cycle", capability.detail)


    def test_instant_kill_runs_after_hit_before_defense_proc(self):
        proto = f"({self.char}FFF)Z"
        hit_proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{proto}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, p4, v1}}, {self.char}->hit{hit_proto}\n"
            "    move-result v0\n"
            "    if-eqz v0, :miss\n"
            f"    invoke-virtual {{p1, p0, v1}}, {self.char}->defenseProc({self.char}I)I\n"
            "    move-result v1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_instant_kill(text, self.char, proto)
        hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        _, _, block = mod.injector.method_block(patched, "attack", proto)
        self.assertEqual(1, block.count(hook))
        self.assertLess(block.index("if-eqz v0, :miss"), block.index(hook))
        self.assertLess(block.index(hook), block.index("->defenseProc("))
        self.assertIn(":smm_instant_kill_native\n    const/4 v0, 0x1", block)

        # The normal compile path adds Riposte completion after the Instant Kill
        # early return has been inserted, so every return remains covered.
        patched = mod.patch_char_attack(patched, self.char, proto)
        _, _, block = mod.injector.method_block(patched, "attack", proto)
        completion = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttackComplete("
            + self.char + self.char + ")V"
        )
        self.assertEqual(3, block.count(completion))


    def test_instant_kill_supports_mlpd_branch_to_success_shape(self):
        terminal = f"({self.char}FFF{self.damage_type})Z"
        hit_proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{terminal}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, p4, v1}}, {self.char}->hit{hit_proto}\n"
            "    .line 123\n"
            "    move-result v0\n"
            "    .line 124\n"
            "    if-nez v0, :hit_success\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ":hit_success\n"
            "    .line 125\n"
            f"    invoke-virtual {{p1, p0, v1}}, {self.char}->defenseProc({self.char}I)I\n"
            "    move-result v1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_instant_kill(text, self.char, terminal)
        hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        _, _, block = mod.injector.method_block(patched, "attack", terminal)
        self.assertEqual(1, block.count(hook))
        self.assertNotIn("if-nez v0, :hit_success", block)
        self.assertLess(
            block.index("if-eqz v0, :smm_instant_kill_miss"),
            block.index(hook),
        )
        self.assertLess(block.index(hook), block.index("goto :hit_success"))
        self.assertLess(
            block.index(":smm_instant_kill_miss"),
            block.index("const/4 v0, 0x0"),
        )
        self.assertLess(
            block.index("const/4 v0, 0x0"),
            block.index("\n:hit_success\n"),
        )
        self.assertLess(block.index("\n:hit_success\n"), block.index("->defenseProc("))
        self.assertIn(":smm_instant_kill_native\n    const/4 v0, 0x1", block)

    def test_force_hit_prefers_exact_spd_hit_without_structural_trace(self):
        attack_proto = f"({self.char}FFF{self.damage_type})Z"
        hit_proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{attack_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_hit_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_DIRECT, capability.strategy)
        self.assertEqual("hit", capability.data.get("method"))
        self.assertEqual(hit_proto, capability.data.get("proto"))

    def test_ark_legacy_hit_is_direct_for_force_and_instant_kill(self):
        attack_proto = f"({self.char})Z"
        hit_proto = f"({self.char}{self.char}Z)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{attack_proto}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, v1}}, {self.char}->hit{hit_proto}\n"
            "    move-result v0\n"
            "    if-eqz v0, :miss\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_hit_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_DIRECT, capability.strategy)
        self.assertEqual("hit", capability.data.get("method"))
        self.assertEqual(hit_proto, capability.data.get("proto"))

        instant = mod.patch_char_instant_kill(
            text,
            self.char,
            attack_proto,
            capability.data["method"],
            capability.data["proto"],
        )
        instant_hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        _, _, attack = mod.injector.method_block(
            instant, "attack", attack_proto
        )
        self.assertEqual(1, attack.count(instant_hook))

        forced = mod.patch_char_hit(
            instant,
            self.char,
            capability.data["method"],
            capability.data["proto"],
        )
        force_hook = (
            "Lcom/spd/mod/mechanics/ModForceHit;->forceHitCheck("
            + self.char + self.char + ")Z"
        )
        _, _, hit = mod.injector.method_block(forced, "hit", hit_proto)
        self.assertEqual(1, hit.count(force_hook))

    def test_force_hit_structurally_finds_renamed_hit_with_extra_parameter(self):
        attack_proto = f"({self.char}FFF)Z"
        hit_proto = f"({self.char}{self.char}FZI)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{attack_proto}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, p4, v1, v1}}, {self.char}->rollHit{hit_proto}\n"
            "    move-result v0\n"
            "    if-eqz v0, :miss\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static rollHit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_hit_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_STRUCTURAL, capability.strategy)
        self.assertEqual("rollHit", capability.data.get("method"))
        self.assertEqual(hit_proto, capability.data.get("proto"))

        patched = mod.patch_char_hit(
            text,
            self.char,
            capability.data["method"],
            capability.data["proto"],
        )
        hook = (
            "Lcom/spd/mod/mechanics/ModForceHit;->forceHitCheck("
            + self.char + self.char + ")Z"
        )
        _, _, block = mod.injector.method_block(
            patched, "rollHit", hit_proto
        )
        self.assertEqual(1, block.count(hook))
        self.assertLess(block.index(hook), block.index("const/4 v0, 0x0"))

    def test_force_hit_structural_probe_rejects_ambiguous_hit_checks(self):
        attack_proto = f"({self.char}FFF)Z"
        hit_proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{attack_proto}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, p4, v1}}, {self.char}->hitA{hit_proto}\n"
            "    move-result v0\n"
            "    if-eqz v0, :miss\n"
            f"    invoke-static {{p0, p1, p4, v1}}, {self.char}->hitB{hit_proto}\n"
            "    move-result v0\n"
            "    if-eqz v0, :miss\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static hitA{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static hitB{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        capability = mod._probe_hit_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_UNSUPPORTED, capability.strategy)
        self.assertIn("found 2", capability.detail)

    def test_force_hit_hook_is_added_to_static_hit(self):
        proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )
        patched = mod.patch_char_hit(text, self.char)
        hook = (
            "Lcom/spd/mod/mechanics/ModForceHit;->forceHitCheck("
            + self.char + self.char + ")Z"
        )
        _, _, block = mod.injector.method_block(patched, "hit", proto)
        self.assertEqual(1, block.count(hook))
        self.assertLess(block.index(hook), block.index("const/4 v0, 0x0"))
        self.assertIn("return v0", block)



if __name__ == "__main__":
    unittest.main()
