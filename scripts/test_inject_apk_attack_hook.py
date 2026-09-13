#!/usr/bin/env python3
from __future__ import annotations

import pathlib
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
        hook = (
            "Lcom/spd/mod/mechanics/ModParryRiposte;->onIncomingAttack("
            + self.char + self.char + ")V"
        )
        self.assertEqual(1, patched.count(hook))
        for proto in wrappers:
            _, _, block = mod.injector.method_block(patched, "attack", proto)
            self.assertNotIn(hook, block)
        _, _, block = mod.injector.method_block(patched, "attack", terminal)
        self.assertIn(hook, block)

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


if __name__ == "__main__":
    unittest.main()
