import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parent / "inject_mod.py"


class SourceParryInjectionTests(unittest.TestCase):

    def _write(self, root: pathlib.Path, relative: str, content: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def _make_tree(self, root: pathlib.Path, *, legacy_hit: bool) -> pathlib.Path:
        java = root / "core/src/main/java"
        game = java / "com/example/game"

        hit_signature = (
            "public static boolean hit(Char attacker, Char defender, boolean magic)"
            if legacy_hit
            else "public static boolean hit(Char attacker, Char defender, float acc, boolean magic)"
        )
        hit_call = (
            "hit(this, enemy, false)"
            if legacy_hit
            else "hit(this, enemy, 1f, false)"
        )

        self._write(
            java,
            "com/example/game/actors/Char.java",
            f"""package com.example.game.actors;
public class Char {{
    public boolean attack(Char enemy) {{
        if ({hit_call}) {{
            enemy.defenseVerb();
            return true;
        }} else {{
            enemy.defenseVerb();
            return false;
        }}
    }}
    {hit_signature} {{
        return true;
    }}
    public String defenseVerb() {{
        return "dodge";
    }}
    public void damage(int damage, Object src) {{
    }}
}}
""",
        )
        self._write(
            java,
            "com/example/game/actors/hero/Hero.java",
            """package com.example.game.actors.hero;
import com.example.game.actors.Char;
public class Hero extends Char {
    @Override
    public void damage(final int amount, final Object source) {
        super.damage(amount, source);
    }
}
""",
        )
        self._write(
            java,
            "com/example/game/actors/mobs/Mob.java",
            """package com.example.game.actors.mobs;
import com.example.game.actors.Char;
public class Mob extends Char {
}
""",
        )
        self._write(
            java,
            "com/example/game/actors/mobs/Eye.java",
            """package com.example.game.actors.mobs;
public class Eye extends Mob {
    @Override
    public void damage(int damage, Object src) {
        super.damage(damage, src);
    }
}
""",
        )
        self._write(
            java,
            "com/example/game/actors/mobs/Boss.java",
            """package com.example.game.actors.mobs;
public class Boss extends Mob {
    public static class Phase extends Boss {
        @Override
        public void damage(int damage, Object src) {
            super.damage(damage, src);
        }
    }
}
""",
        )
        self._write(
            java,
            "com/example/game/TrapReceiver.java",
            """package com.example.game;
public class TrapReceiver {
    public void damage(int damage, Object src) {
    }
}
""",
        )

        required = {
            "Dungeon.java": "package com.example.game; public class Dungeon {}\n",
            "actors/hero/HeroClass.java": (
                "package com.example.game.actors.hero; public class HeroClass {}\n"
            ),
            "items/Item.java": "package com.example.game.items; public class Item {}\n",
            "levels/Level.java": "package com.example.game.levels; public class Level {}\n",
            "scenes/GameScene.java": (
                "package com.example.game.scenes; public class GameScene {}\n"
            ),
        }
        for relative, source in required.items():
            self._write(java, "com/example/game/" + relative, source)

        self._write(
            java,
            "com/example/game/windows/WndGame.java",
            """package com.example.game.windows;
public class WndGame {
    public WndGame() { super(); }
    private void addButton(Object button) {}
}
""",
        )
        self._write(
            java,
            "com/example/game/ui/BuffIndicator.java",
            """package com.example.game.ui;
public class BuffIndicator {
    class BuffButton {
        @Override
        protected void onClick() {
            if (buff.icon() != NONE) GameScene.show(new WndInfoBuff(buff));
        }
    }
}
""",
        )
        return java

    def _run(self, root: pathlib.Path, *, legacy_hit: bool) -> pathlib.Path:
        java = self._make_tree(root, legacy_hit=legacy_hit)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), str(root)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout)
        return java

    def _assert_direct_damage_scope(self, java: pathlib.Path) -> None:
        marker = "// MASTER_MODE_PARRY_RIPOSTE_DIRECT_DAMAGE"
        hero = (java / "com/example/game/actors/hero/Hero.java").read_text()
        eye = (java / "com/example/game/actors/mobs/Eye.java").read_text()
        boss = (java / "com/example/game/actors/mobs/Boss.java").read_text()
        char = (java / "com/example/game/actors/Char.java").read_text()
        unrelated = (java / "com/example/game/TrapReceiver.java").read_text()

        self.assertEqual(1, hero.count(marker))
        self.assertEqual(1, eye.count(marker))
        self.assertEqual(1, boss.count(marker))
        self.assertNotIn(marker, char)
        self.assertNotIn(marker, unrelated)

        for source in (hero, eye, boss):
            self.assertIn("ModParryRiposte.resolveDirectDamage(this,", source)

        self.assertIn("ModParryRiposte.onHitCheck(", char)
        self.assertNotIn("Focus", char)

    def _assert_instant_kill_completion_scope(self, java: pathlib.Path) -> None:
        char = (java / "com/example/game/actors/Char.java").read_text()
        begin = "ModInstantKill.beginAttack(this, enemy);"
        finish_true = "ModInstantKill.finishAttackResult(true);"
        finish_false = "ModInstantKill.finishAttackResult(false);"

        self.assertEqual(1, char.count(begin))
        self.assertEqual(1, char.count(finish_true))
        self.assertEqual(1, char.count(finish_false))
        self.assertNotIn("ModInstantKill.resolveSuccessfulAttack(this, enemy)", char)

        # Native successful-hit work must run before Instant Kill completion.
        native_hit_work = char.index("enemy.defenseVerb();")
        instant_completion = char.index(finish_true)
        self.assertLess(native_hit_work, instant_completion)

    def test_modern_source_uses_hit_and_direct_damage_hooks(self):
        with tempfile.TemporaryDirectory() as tmp:
            java = self._run(pathlib.Path(tmp), legacy_hit=False)
            self._assert_direct_damage_scope(java)
            self._assert_instant_kill_completion_scope(java)

    def test_legacy_source_uses_same_focus_free_hooks(self):
        with tempfile.TemporaryDirectory() as tmp:
            java = self._run(pathlib.Path(tmp), legacy_hit=True)
            self._assert_direct_damage_scope(java)
            self._assert_instant_kill_completion_scope(java)

    def test_injector_contains_no_focus_bridge(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("MonkEnergy", source)
        self.assertNotIn("ParryDetachSink", source)
        self.assertNotIn("TotalParryFocus", source)
        self.assertIn("resolveDirectDamage", source)


if __name__ == "__main__":
    unittest.main()
