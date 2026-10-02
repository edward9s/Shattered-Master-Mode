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
        capability = mod._probe_char_attack_hook(
            {self.char: self.cls(text)}, self.game
        )
        self.assertEqual(mod.ABI_DIRECT, capability.strategy)
        self.assertEqual(terminal, capability.data.get("proto"))

    def test_ankh_only_does_not_require_attack_or_hit_optional_abis(self):
        original = mod._ankh_only_mode
        try:
            mod._ankh_only_mode = True
            profile = mod.detect_target_abi({}, self.game)
        finally:
            mod._ankh_only_mode = original

        self.assertFalse(profile.get("char.incomingAttackHook").required)
        self.assertFalse(profile.get("char.hitHook").required)
        self.assertFalse(profile.get("char.incomingAttackHook").compatible)
        self.assertFalse(profile.get("char.hitHook").compatible)


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


    def test_instant_kill_balances_attack_context_on_native_returns(self):
        proto = f"({self.char}FFF)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{proto}\n"
            "    .locals 1\n"
            "    if-eqz p1, :miss\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ":miss\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_instant_kill(text, self.char, proto)
        _, _, block = mod.injector.method_block(patched, "attack", proto)
        self.assertIn(".locals 1", block)
        self.assertIn(
            "ModInstantKill;->beginAttack(" + self.char + self.char + ")V",
            block,
        )
        self.assertEqual(
            2,
            block.count("ModInstantKill;->finishAttack(Z)V"),
        )
        self.assertIn(
            "invoke-static/range {v0 .. v0}, "
            "Lcom/spd/mod/mechanics/ModInstantKill;->finishAttack(Z)V",
            block,
        )
        self.assertEqual(
            1,
            block.count("ModInstantKill;->resolveSuccessfulAttack("),
        )


    def test_instant_kill_does_not_grow_high_register_methods(self):
        proto = f"({self.char}FFF)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{proto}\n"
            "    .locals 17\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )
        patched = mod.patch_char_instant_kill(text, self.char, proto)
        _, _, block = mod.injector.method_block(patched, "attack", proto)
        self.assertIn(".locals 17", block)
        self.assertNotIn(".locals 18", block)
        self.assertIn(
            "invoke-static/range {p0 .. p0}, "
            "Lcom/spd/mod/mechanics/ModForceHit;->isForceHitEnabled",
            block,
        )
        self.assertIn(
            "invoke-static/range {p0 .. p1}, "
            "Lcom/spd/mod/mechanics/ModInstantKill;->beginAttack",
            block,
        )



    def test_return_hooks_do_not_read_reused_attack_parameters(self):
        proto = f"({self.char}FFF)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{proto}\n"
            "    .locals 2\n"
            "    const/4 v0, 0x1\n"
            "    new-instance p1, Ljava/lang/Object;\n"
            "    return v0\n"
            ".end method\n"
        )
        patched = mod.patch_char_instant_kill(text, self.char, proto)
        _, _, block = mod.injector.method_block(patched, "attack", proto)

        self.assertIn(".locals 2", block)
        tail = block[block.index("new-instance p1"):]
        self.assertIn("ModInstantKill;->finishAttack(Z)V", tail)
        self.assertNotIn(
            "ModInstantKill;->resolveSuccessfulAttack(",
            tail,
        )
        self.assertNotIn("ModParryRiposte", block)

    def test_instant_kill_does_not_depend_on_mlpd_hit_branch_shape(self):
        terminal = f"({self.char}FFF{self.damage_type})Z"
        hit_proto = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{terminal}\n"
            "    .locals 2\n"
            "    const/4 v1, 0x0\n"
            f"    invoke-static {{p0, p1, p4, v1}}, {self.char}->hit{hit_proto}\n"
            "    move-result v0\n"
            "    const/4 v1, 0x0\n"
            "    if-nez v0, :hit_success\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ":hit_success\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_instant_kill(text, self.char, terminal)
        instant_hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        _, _, block = mod.injector.method_block(patched, "attack", terminal)
        self.assertIn("if-nez v0, :hit_success", block)
        self.assertEqual(1, block.count(instant_hook))
        self.assertEqual(2, block.count("ModInstantKill;->finishAttack(Z)V"))
        self.assertNotIn(":smm_instant_kill_miss", block)
        self.assertNotIn("confirmed hit", block)


    def test_force_hit_accepts_accessible_direct_hit_method(self):
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


    def test_ark_legacy_hit_is_direct_for_force_hit(self):
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
            "    move v1, v0\n"
            "    if-eqz v1, :miss\n"
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
        )
        instant_hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        _, _, attack = mod.injector.method_block(
            instant, "attack", attack_proto
        )
        self.assertEqual(1, attack.count(instant_hook))
        self.assertEqual(2, attack.count("ModInstantKill;->finishAttack(Z)V"))

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


    def test_ark_inherited_static_hit_callers_are_canonicalized(self):
        hit_proto = f"({self.char}{self.char}Z)Z"
        mob = self.game + "actors/mobs/Mob;"
        eye = self.game + "actors/mobs/Eye;"
        bright = self.game + "actors/mobs/YogFist$BrightFist;"

        char_text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )
        mob_text = (
            f".class public {mob}\n"
            f".super {self.char}\n"
        )
        eye_text = (
            f".class public {eye}\n"
            f".super {mob}\n"
            ".method public deathGaze()V\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            f"    invoke-static {{p0, p0, v0}}, {eye}->hit{hit_proto}\n"
            "    return-void\n"
            ".end method\n"
        )
        bright_text = (
            f".class public {bright}\n"
            f".super {mob}\n"
            ".method protected zap()V\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            f"    invoke-static/range {{p0 .. p0}}, {bright}->hit{hit_proto}\n"
            "    return-void\n"
            ".end method\n"
        )
        index = {
            self.char: self.cls(char_text),
            mob: self.cls(mob_text),
            eye: self.cls(eye_text),
            bright: self.cls(bright_text),
        }

        overlays, count = mod.canonicalize_inherited_static_hit_calls(
            index,
            self.char,
            "hit",
            hit_proto,
        )

        self.assertEqual(2, count)
        self.assertEqual({eye, bright}, set(overlays))
        self.assertIn(f"{self.char}->hit{hit_proto}", overlays[eye])
        self.assertIn(f"{self.char}->hit{hit_proto}", overlays[bright])
        self.assertNotIn(f"{eye}->hit{hit_proto}", overlays[eye])
        self.assertNotIn(f"{bright}->hit{hit_proto}", overlays[bright])


    def test_char_local_parry_feedback_is_rewritten(self):
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            ".method public attack()Z\n"
            "    .locals 1\n"
            f"    invoke-virtual {{p0}}, {self.char}->defenseVerb()Ljava/lang/String;\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
        )

        patched, count = mod.rewrite_char_parry_defense_verb_calls(
            text,
            self.char,
        )

        self.assertEqual(1, count)
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModParryRiposte;->defenseVerb("
            + self.char
            + ")Ljava/lang/String;",
            patched,
        )
        self.assertNotIn(
            f"invoke-virtual {{p0}}, {self.char}->defenseVerb()Ljava/lang/String;",
            patched,
        )


    def test_parry_feedback_rewrites_every_char_virtual_call(self):
        mob = self.game + "actors/mobs/Mob;"
        hero = self.game + "actors/hero/Hero;"
        caller = self.game + "actors/mobs/TestCaller;"
        other = "Lexample/Other;"

        char_text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            ".method public defenseVerb()Ljava/lang/String;\n"
            "    .locals 1\n"
            "    const-string v0, \"dodge\"\n"
            "    return-object v0\n"
            ".end method\n"
        )
        mob_text = (
            f".class public {mob}\n"
            f".super {self.char}\n"
        )
        hero_text = (
            f".class public {hero}\n"
            f".super {self.char}\n"
            ".method public defenseVerb()Ljava/lang/String;\n"
            "    .locals 1\n"
            f"    invoke-super {{p0}}, {self.char}->defenseVerb()Ljava/lang/String;\n"
            "    move-result-object v0\n"
            "    return-object v0\n"
            ".end method\n"
        )
        other_text = (
            f".class public {other}\n"
            ".super Ljava/lang/Object;\n"
            ".method public defenseVerb()Ljava/lang/String;\n"
            "    .locals 1\n"
            "    const-string v0, \"other\"\n"
            "    return-object v0\n"
            ".end method\n"
        )
        caller_text = (
            f".class public {caller}\n"
            ".super Ljava/lang/Object;\n"
            ".method public test()V\n"
            "    .locals 1\n"
            f"    invoke-virtual {{p0}}, {self.char}->defenseVerb()Ljava/lang/String;\n"
            f"    invoke-virtual {{p0}}, {hero}->defenseVerb()Ljava/lang/String;\n"
            f"    invoke-virtual/range {{p0 .. p0}}, {mob}->defenseVerb()Ljava/lang/String;\n"
            f"    invoke-virtual {{p0}}, {other}->defenseVerb()Ljava/lang/String;\n"
            "    return-void\n"
            ".end method\n"
        )
        index = {
            self.char: self.cls(char_text),
            mob: self.cls(mob_text),
            hero: self.cls(hero_text),
            other: self.cls(other_text),
            caller: self.cls(caller_text),
        }

        overlays, count = mod.rewrite_parry_defense_verb_calls(
            index,
            self.char,
        )

        self.assertEqual(3, count)
        self.assertEqual({caller}, set(overlays))
        patched = overlays[caller]
        hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->defenseVerb("
            + self.char
            + ")Ljava/lang/String;"
        )
        self.assertEqual(3, patched.count(hook))
        self.assertIn(
            f"invoke-virtual {{p0}}, {other}->defenseVerb()Ljava/lang/String;",
            patched,
        )
        self.assertNotIn(hero, overlays)
        self.assertIn(
            f"invoke-super {{p0}}, {self.char}->defenseVerb()Ljava/lang/String;",
            hero_text,
        )


    def test_ark_hit_and_parry_feedback_rewrites_compose(self):
        hit_proto = f"({self.char}{self.char}Z)Z"
        mob = self.game + "actors/mobs/Mob;"
        eye = self.game + "actors/mobs/Eye;"

        char_text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
            ".method public defenseVerb()Ljava/lang/String;\n"
            "    .locals 1\n"
            "    const-string v0, \"dodge\"\n"
            "    return-object v0\n"
            ".end method\n"
        )
        mob_text = (
            f".class public {mob}\n"
            f".super {self.char}\n"
        )
        eye_text = (
            f".class public {eye}\n"
            f".super {mob}\n"
            ".method public deathGaze()V\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            f"    invoke-static {{p0, p0, v0}}, {eye}->hit{hit_proto}\n"
            f"    invoke-virtual {{p0}}, {eye}->defenseVerb()Ljava/lang/String;\n"
            "    return-void\n"
            ".end method\n"
        )
        index = {
            self.char: self.cls(char_text),
            mob: self.cls(mob_text),
            eye: self.cls(eye_text),
        }

        hit_overlays, hit_count = mod.canonicalize_inherited_static_hit_calls(
            index,
            self.char,
            "hit",
            hit_proto,
        )
        feedback_overlays, feedback_count = mod.rewrite_parry_defense_verb_calls(
            index,
            self.char,
            hit_overlays,
        )

        self.assertEqual(1, hit_count)
        self.assertEqual(1, feedback_count)
        patched = feedback_overlays[eye]
        self.assertIn(f"{self.char}->hit{hit_proto}", patched)
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModParryRiposte;->defenseVerb("
            + self.char
            + ")Ljava/lang/String;",
            patched,
        )
        self.assertNotIn(f"{eye}->hit{hit_proto}", patched)
        self.assertNotIn(
            f"{eye}->defenseVerb()Ljava/lang/String;",
            patched,
        )


    def test_hidden_static_hit_is_not_canonicalized(self):
        hit_proto = f"({self.char}{self.char}Z)Z"
        special = self.game + "actors/mobs/Special;"

        char_text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )
        special_text = (
            f".class public {special}\n"
            f".super {self.char}\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x0\n"
            "    return v0\n"
            ".end method\n"
            ".method public test()V\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            f"    invoke-static {{p0, p0, v0}}, {special}->hit{hit_proto}\n"
            "    return-void\n"
            ".end method\n"
        )
        index = {
            self.char: self.cls(char_text),
            special: self.cls(special_text),
        }

        overlays, count = mod.canonicalize_inherited_static_hit_calls(
            index,
            self.char,
            "hit",
            hit_proto,
        )

        self.assertEqual(0, count)
        self.assertEqual({}, overlays)


    def test_parry_covers_modern_magic_hit_wrapper(self):
        legacy_hit = f"({self.char}{self.char}Z)Z"
        modern_hit = f"({self.char}{self.char}FZ)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{legacy_hit}\n"
            "    .locals 1\n"
            "    const/high16 v0, 0x40000000    # 2.0f\n"
            f"    invoke-static {{p0, p1, v0, p2}}, {self.char}->hit{modern_hit}\n"
            "    move-result v0\n"
            "    return v0\n"
            ".end method\n"
            f".method public static hit{modern_hit}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_hit(
            text,
            self.char,
            "hit",
            modern_hit,
            force=False,
            parry=True,
        )
        _, _, wrapper = mod.injector.method_block(patched, "hit", legacy_hit)
        _, _, selected = mod.injector.method_block(patched, "hit", modern_hit)
        parry_hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onHitCheck("
            + self.char + self.char + ")Z"
        )

        self.assertIn(f"->hit{modern_hit}", wrapper)
        self.assertNotIn(parry_hook, wrapper)
        self.assertIn(parry_hook, selected)
        self.assertLess(selected.index(parry_hook), selected.index("const/4 v0, 0x1"))



    def test_force_hit_overrides_parry_after_unified_hit_observation(self):
        hit_proto = f"({self.char}{self.char}Z)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public static hit{hit_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )

        patched = mod.patch_char_hit(
            text,
            self.char,
            "hit",
            hit_proto,
            force=True,
            parry=True,
        )
        _, _, hit = mod.injector.method_block(patched, "hit", hit_proto)
        force_hook = (
            "Lcom/spd/mod/mechanics/ModForceHit;->forceHitCheck("
            + self.char + self.char + ")Z"
        )
        parry_hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onHitCheck("
            + self.char + self.char + ")Z"
        )
        self.assertEqual(2, hit.count(force_hook))
        self.assertEqual(1, hit.count(parry_hook))
        self.assertLess(hit.index(parry_hook), hit.index(force_hook))
        self.assertIn(":smm_parry_riposte_return_miss", hit)
        self.assertIn(":smm_parry_riposte_no_parry", hit)
        self.assertIn("const/4 v0, 0x1", hit)
        self.assertIn("const/4 v0, 0x0", hit)

    def test_instant_kill_can_patch_without_force_hit_dependency(self):
        attack_proto = f"({self.char}FFF)Z"
        text = (
            f".class public {self.char}\n"
            ".super Ljava/lang/Object;\n"
            f".method public attack{attack_proto}\n"
            "    .locals 1\n"
            "    const/4 v0, 0x1\n"
            "    return v0\n"
            ".end method\n"
        )
        patched = mod.patch_char_instant_kill(
            text,
            self.char,
            attack_proto,
            force_combo=False,
        )
        _, _, attack = mod.injector.method_block(
            patched, "attack", attack_proto
        )
        instant_hook = (
            "Lcom/spd/mod/mechanics/ModInstantKill;->resolveSuccessfulAttack("
            + self.char + self.char + ")Z"
        )
        self.assertEqual(0, attack.count(instant_hook))
        self.assertEqual(1, attack.count("ModInstantKill;->beginAttack("))
        self.assertEqual(1, attack.count("ModInstantKill;->finishAttack(Z)V"))
        self.assertNotIn(
            "Lcom/spd/mod/mechanics/ModForceHit;->isForceHitEnabled",
            attack,
        )
        self.assertNotIn(":smm_instant_kill_force_native", attack)


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
        self.assertIn(".locals 1", block)
        self.assertIn("move-result v0", block)
        self.assertIn("if-eqz v0, :smm_combat_hit_native", block)
        self.assertLess(block.index(hook), block.index(":smm_force_hit_native"))
        self.assertIn("return v0", block)



if __name__ == "__main__":
    unittest.main()
