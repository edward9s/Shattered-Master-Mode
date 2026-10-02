import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import inject_jar as mod


GAME_ROOT = "com/shatteredpixel/shatteredpixeldungeon"


class AnkhJarUiTests(unittest.TestCase):

    def test_legacy_wnduseitem_bridge_uses_virtual_action_name(self):
        source = mod.WND_USE_ITEM_ACTION_HELPER
        self.assertIn('ACTION_HELPER = "smm$actionName"', source)
        self.assertIn('"actionName"', source)
        self.assertIn('"startsWith"', source)
        self.assertIn('"ac_"', source)
        self.assertIn("MESSAGE_GET_DESC", source)
        self.assertIn("MESSAGES", source)

    def test_jar_injector_does_not_patch_item_message_resources(self):
        self.assertFalse(hasattr(mod, "_ACTION_MESSAGE_BUNDLE_RE"))
        self.assertFalse(hasattr(mod, "_ACTION_MESSAGES"))
        self.assertFalse(hasattr(mod, "_append_action_messages"))

    def test_buff_click_is_direct_target_patch(self):
        source = mod.BUFF_CLICK_HELPER
        self.assertIn('INFO_HELPER = "smm$nativeInfo"', source)
        self.assertIn('LONG_HELPER = "smm$nativeLongClick"', source)
        self.assertIn('"onClick"', source)
        self.assertIn('"onLongClick"', source)
        self.assertIn('result.put(LAST_STAND, "open")', source)
        self.assertIn('result.put(INSTANT_KILL, "openInfo")', source)
        self.assertIn('result.put(FORCE_HIT, "openInfo")', source)
        self.assertIn('result.put(PARRY_RIPOSTE, "openInfo")', source)
        self.assertIn('result.put(ASSASSINATE, "openInfo")', source)
        self.assertIn('result.put(ENEMY_SURGE, "openInfo")', source)
        self.assertIn("ENABLE_PARRY", source)
        self.assertIn("ENABLE_ASSASSINATE", source)
        self.assertIn("ENABLE_ENEMY_SURGE", source)

    def test_ankh_core_includes_last_stand_runtime_ui(self):
        self.assertIn(
            "com/spd/mod/journal/ModLastStandTag.class", mod.ANKH_REQUIRED_ROOTS
        )
        self.assertIn(
            "com/spd/mod/journal/ModRuntimeTagStack.class", mod.ANKH_REQUIRED_ROOTS
        )
        source = pathlib.Path(mod.__file__).read_text(encoding="utf-8")
        self.assertIn(
            '"parry": "com/spd/mod/mechanics/ModParryRiposte.class"',
            source,
        )
        self.assertIn(
            '"assassinate": "com/spd/mod/mechanics/ModAssassinate.class"',
            source,
        )
        self.assertIn(
            '"enemy_surge": "com/spd/mod/mechanics/ModEnemySurge.class"',
            source,
        )

        root = pathlib.Path(__file__).resolve().parents[1]
        runtime_sources = [
            root / "core/src/main/java/com/spd/mod/journal/ModLastStandTag.java",
            root / "core/src/main/java/com/spd/mod/journal/ModRuntimeTagStack.java",
            root / "core/src/main/java/com/spd/mod/mechanics/ModAssassinate.java",
        ]
        for runtime_source in runtime_sources:
            text = runtime_source.read_text(encoding="utf-8")
            self.assertNotIn("ShatteredPixelDungeon.scene()", text)
        self.assertNotIn(
            "ShatteredPixelDungeon.runOnRenderThread",
            runtime_sources[2].read_text(encoding="utf-8"),
        )
        for runtime_source in (runtime_sources[0], runtime_sources[2]):
            self.assertNotIn(
                "com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon",
                runtime_source.read_text(encoding="utf-8"),
            )

        stack_source = runtime_sources[1].read_text(encoding="utf-8")
        self.assertIn('getDeclaredField("hotArea")', stack_source)
        self.assertIn('getDeclaredMethod("givePointerPriority")', stack_source)

        assassinate_source = runtime_sources[2].read_text(encoding="utf-8")
        self.assertNotIn("givePointerPriority();", assassinate_source)

        instant_source = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModInstantKill.java"
        ).read_text(encoding="utf-8")
        self.assertIn("class ModInstantKill extends Buff", instant_source)
        self.assertNotIn("ModForceHit", instant_source)
        self.assertNotIn("resolveForcedAttack", instant_source)
        self.assertNotIn("ChampionEnemy", instant_source)
        self.assertNotIn("onAttackProc", instant_source)
        self.assertNotIn("resolveBlockedAttack", instant_source)
        self.assertNotIn("Swarm", instant_source)
        self.assertIn(
            "TargetHealthIndicator.instance.target(null)",
            instant_source,
        )

        force_source = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModForceHit.java"
        ).read_text(encoding="utf-8")
        self.assertIn("isForceHitEnabled", force_source)

        riposte_source = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModParryRiposte.java"
        ).read_text(encoding="utf-8")
        self.assertIn(
            "ModCombatCompat.performRiposteAttack(riposter, attacker)",
            riposte_source,
        )

        assassin_source = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModAssassin.java"
        ).read_text(encoding="utf-8")
        self.assertIn("hit = attacker.attack(target);", assassin_source)
        self.assertNotIn("attacker.attack(target, 1f, 0f, 1f)", assassin_source)
        self.assertNotIn("attacker.invisible", assassin_source)
        self.assertNotIn("resolveBlockedAttack", assassin_source)
        self.assertNotIn("HeroClass.DUELIST", assassin_source)
        self.assertIn('((Enum<?>) hero.heroClass).name()', assassin_source)

        self.assertNotIn(
            "com.shatteredpixel.shatteredpixeldungeon.ui.Button",
            assassinate_source,
        )
        self.assertIn(
            "ModLegacyCompat.longClickThreshold()",
            assassinate_source,
        )

    def test_enemy_surge_minimal_payload_avoids_overlay_dependency(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        surge = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModEnemySurge.java"
        ).read_text(encoding="utf-8")
        window = (
            root / "core/src/main/java/com/spd/mod/journal/WndEnemySurgeInfo.java"
        ).read_text(encoding="utf-8")

        self.assertIn("public void openInfo()", surge)
        self.assertIn('invokeOptionalOverlay("ensureInstalled")', surge)
        self.assertNotIn(
            "import com.spd.mod.journal.ModEnemySurgeInfoOverlay;",
            surge,
        )
        self.assertNotIn("ModEnemySurgeInfoOverlay", window)
        self.assertNotIn("RenderedTextBlock", window)



    def test_parry_riposte_stays_portable_across_old_forks(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModParryRiposte.java"
        ).read_text(encoding="utf-8")

        self.assertIn("class ModParryRiposte extends Buff", source)
        self.assertIn(
            "public static boolean onHitCheck(Char attacker, Char defender)",
            source,
        )
        self.assertIn(
            "public static boolean onDirectDamage(Char defender, int damage, Object src)",
            source,
        )
        self.assertIn("queueRiposte(defender, attacker)", source)
        on_hit = source[
            source.index("public static boolean onHitCheck"):
            source.index("public static boolean onDirectDamage")
        ]
        self.assertNotIn("attacker.isAlive()", on_hit)
        self.assertNotIn("defender.isAlive()", on_hit)
        self.assertNotIn("buff.target != defender", on_hit)
        self.assertNotIn("onIncomingAttack", source)
        self.assertNotIn("onIncomingAttackComplete", source)
        self.assertNotIn("shouldParry", source)
        self.assertNotIn("MonkEnergy", source)
        self.assertNotIn("ParryDetachSink", source)
        self.assertNotIn("currentAttackSource", source)
        self.assertIn('getDeclaredField("current")', source)
        self.assertIn("getEnclosingClass()", source)
        self.assertNotIn("Talulah", source)
        self.assertNotIn("Yog", source)
        self.assertNotIn("Eye", source)

        combat_compat = (
            root / "core/src/main/java/com/spd/mod/mechanics/ModCombatCompat.java"
        ).read_text(encoding="utf-8")
        self.assertIn("performRiposteAttack", combat_compat)
        self.assertIn('"attack"', combat_compat)
        self.assertIn("Float.TYPE", combat_compat)


    def test_jar_parry_feedback_bridge_is_all_char_and_virtual_only(self):
        source = mod.PARRY_FEEDBACK_HELPER
        self.assertIn('"defenseVerb".equals(methodName)', source)
        self.assertIn("opcode == Opcodes.INVOKEVIRTUAL", source)
        self.assertIn("isCharType(owner, parents)", source)
        self.assertNotIn(
            'Opcodes.INVOKESPECIAL\n                                && "defenseVerb"',
            source,
        )

        for helper in (mod.CHAR_HELPER, mod.ANKH_CHAR_HELPER):
            self.assertIn("DEFENSE_FEEDBACK_DESC", helper)
            self.assertIn('"onHitCheck"', helper)
            self.assertNotIn('"onIncomingAttack"', helper)
            self.assertNotIn('"onIncomingAttackComplete"', helper)
            self.assertNotIn('"shouldParry"', helper)

        self.assertIn(
            'payload, MOD_PARRY_RIPOSTE, "defenseVerb"',
            mod.ANKH_CHAR_HELPER,
        )

    def test_last_stand_tag_keeps_legacy_safe_badge_icon(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        source = (
            root / "core/src/main/java/com/spd/mod/journal/ModLastStandTag.java"
        ).read_text(encoding="utf-8")

        self.assertIn("BADGE_RED", source)
        self.assertIn("HEART_YELLOW", source)
        self.assertIn("lastStandBadgeIcon()", source)
        self.assertIn('"interfaces/buffs.png"', source)
        self.assertIn("new TextureFilm(atlas, 7, 7)", source)
        self.assertIn("heart.hardlight(HEART_YELLOW)", source)
        self.assertNotIn("Assets.Interfaces.class.getField", source)
        self.assertNotIn(
            "com.shatteredpixel.shatteredpixeldungeon.ui.BuffIcon",
            source,
        )


    def test_tag_has_only_store_tag_responsibility(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        old_name = "ModLastStand" + "Overlay"
        old_path = root / f"core/src/main/java/com/spd/mod/journal/{old_name}.java"
        tag = root / "core/src/main/java/com/spd/mod/journal/ModLastStandTag.java"
        self.assertFalse(old_path.exists())
        source = tag.read_text(encoding="utf-8")
        for forbidden in (
            "LastStandButton",
            "WndInfoBuff",
            "collectComponents",
            "getDeclaredFields",
        ):
            self.assertNotIn(forbidden, source)



    def test_total_info_overlay_is_removed(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        overlay = root / "core/src/main/java/com/spd/mod/journal/ModTotalInfoOverlay.java"
        self.assertFalse(overlay.exists())

        for relative in (
            "core/src/main/java/com/spd/mod/mechanics/ModParryRiposte.java",
            "core/src/main/java/com/spd/mod/mechanics/ModInstantKill.java",
            "core/src/main/java/com/spd/mod/mechanics/ModForceHit.java",
            "core/src/main/java/com/spd/mod/mechanics/ModAssassinate.java",
            "core/src/main/java/com/spd/mod/journal/WndTotalBuffInfo.java",
            "core/src/main/java/com/spd/mod/journal/WndInstantKillInfo.java",
        ):
            source = (root / relative).read_text(encoding="utf-8")
            self.assertNotIn("ModTotalInfoOverlay", source)

    def test_apk_full_and_ankh_only_share_buff_patch_module(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        full = (root / "scripts/inject_apk.py").read_text(encoding="utf-8")
        narrow = (root / "scripts/_inject_apk_ankh.py").read_text(encoding="utf-8")
        shared = (root / "scripts/_inject_buff_click.py").read_text(
            encoding="utf-8"
        )

        self.assertIn("import _inject_buff_click as buff_click", full)
        self.assertIn("buff_click.find_target", full)
        self.assertIn("write_buff_click_patch", full)
        self.assertIn("write_buff_click_patch", narrow)
        self.assertIn("selected_handlers", shared)

        for duplicate in (
            "_find_buff_click_overlay",
            "_patch_last_stand_buff_click",
            "_add_legacy_last_stand_long_click",
        ):
            self.assertNotIn(duplicate, narrow)
            self.assertNotIn(duplicate, full)
            self.assertNotIn(duplicate, shared)

    @staticmethod
    def _tool(name: str) -> str:
        value = shutil.which(name)
        if value is None:
            raise unittest.SkipTest(f"{name} is unavailable")
        return value

    @staticmethod
    def _write(root: pathlib.Path, relative: str, text: str) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    @classmethod
    def _compile_last_stand_target(
        cls,
        work: pathlib.Path,
        *,
        native_long_click: bool,
        extra_candidate: bool = False,
        info_click: bool = True,
    ) -> tuple[pathlib.Path, pathlib.Path]:
        src = work / "src"
        classes = work / "classes"

        cls._write(
            src,
            "com/watabou/noosa/ui/Button.java",
            """
package com.watabou.noosa.ui;
public class Button {
    public static int longClicks;
    protected void onClick() {
    }
    protected boolean onLongClick() {
        longClicks++;
        return false;
    }
}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/actors/buffs/Buff.java",
            f"""
package {GAME_ROOT.replace('/', '.')}.actors.buffs;
public class Buff {{
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/windows/WndInfoBuff.java",
            f"""
package {GAME_ROOT.replace('/', '.')}.windows;
import {GAME_ROOT.replace('/', '.')}.actors.buffs.Buff;
public class WndInfoBuff {{
    public static int opened;
    public WndInfoBuff(Buff buff) {{
        opened++;
    }}
}}
""",
        )
        cls._write(
            src,
            "com/spd/mod/mechanics/ModLastStand.java",
            f"""
package com.spd.mod.mechanics;
import {GAME_ROOT.replace('/', '.')}.actors.buffs.Buff;
public class ModLastStand extends Buff {{
    public static int opened;
    public void open() {{
        opened++;
    }}
}}
""",
        )

        click_body = (
            "new WndInfoBuff(buff);"
            if info_click
            else "int ignored = buff == null ? 0 : 1;"
        )
        long_method = (
            """
        @Override
        protected boolean onLongClick() {
            NativeLongProbe.calls++;
            return true;
        }
"""
            if native_long_click
            else ""
        )
        second = (
            """
    public static class SecondBuffIcon extends Button {
        private final Buff buff;
        public SecondBuffIcon(Buff buff) {
            this.buff = buff;
        }
        @Override
        protected void onClick() {
            new WndInfoBuff(buff);
        }
    }
"""
            if extra_candidate
            else ""
        )
        cls._write(
            src,
            f"{GAME_ROOT}/ui/BuffIndicator.java",
            f"""
package {GAME_ROOT.replace('/', '.')}.ui;
import com.watabou.noosa.ui.Button;
import {GAME_ROOT.replace('/', '.')}.actors.buffs.Buff;
import {GAME_ROOT.replace('/', '.')}.windows.WndInfoBuff;

public class BuffIndicator {{
    public static class BuffIcon extends Button {{
        private final Buff buff;
        public BuffIcon(Buff buff) {{
            this.buff = buff;
        }}
        protected void onClick() {{
            {click_body}
        }}
        public boolean invokeLongClick() {{
            return onLongClick();
        }}
{long_method}
    }}
{second}
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/ui/NativeLongProbe.java",
            f"""
package {GAME_ROOT.replace('/', '.')}.ui;
public class NativeLongProbe {{
    public static int calls;
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/ui/Harness.java",
            f"""
package {GAME_ROOT.replace('/', '.')}.ui;

import com.spd.mod.mechanics.ModLastStand;
import com.watabou.noosa.ui.Button;
import {GAME_ROOT.replace('/', '.')}.actors.buffs.Buff;
import {GAME_ROOT.replace('/', '.')}.windows.WndInfoBuff;

public class Harness {{
    private static void check(boolean value, String label) {{
        if (!value) throw new AssertionError(label);
    }}

    public static void main(String[] args) {{
        ModLastStand.opened = 0;
        WndInfoBuff.opened = 0;
        NativeLongProbe.calls = 0;
        Button.longClicks = 0;

        BuffIndicator.BuffIcon lastStand =
                new BuffIndicator.BuffIcon(new ModLastStand());
        lastStand.onClick();
        check(ModLastStand.opened == 1, "Last Stand short click did not open Store");
        check(WndInfoBuff.opened == 0, "Last Stand short click opened info");

        check(lastStand.invokeLongClick(), "Last Stand long click was not consumed");
        check(WndInfoBuff.opened == 1, "Last Stand long click did not open native info");
        check(NativeLongProbe.calls == 0, "Last Stand long click called native long handler");
        check(Button.longClicks == 0, "Last Stand long click called superclass handler");

        BuffIndicator.BuffIcon normal = new BuffIndicator.BuffIcon(new Buff());
        normal.onClick();
        check(WndInfoBuff.opened == 2, "Normal buff click did not keep native info");
        boolean normalLong = normal.invokeLongClick();

        if ({str(native_long_click).lower()}) {{
            check(normalLong, "Native long-click result was not preserved");
            check(NativeLongProbe.calls == 1, "Native long-click body was not preserved");
        }} else {{
            check(!normalLong, "Superclass long-click result was not preserved");
            check(Button.longClicks == 1, "Superclass long-click was not called");
        }}
    }}
}}
""",
        )
        javac = cls._tool("javac")
        java_files = [str(path) for path in src.rglob("*.java")]
        subprocess.run(
            [javac, "-d", str(classes), *java_files],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        target = work / "target.jar"
        with zipfile.ZipFile(target, "w") as jar:
            for class_file in classes.rglob("*.class"):
                jar.write(class_file, class_file.relative_to(classes).as_posix())
        return target, classes

    def _run_last_stand_synthetic(self, *, native_long_click: bool) -> None:
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, classes = self._compile_last_stand_target(
                work,
                native_long_click=native_long_click,
            )
            entry, patched = mod.patch_buff_click_jar(
                    java,
                    target,
                    work,
                    GAME_ROOT,
                    parry=False,
                    instant=False,
                    force=False,
                    assassinate=False,
                    enemy_surge=False,
                )
            target_class = classes / entry
            target_class.write_bytes(patched.read_bytes())

            result = subprocess.run(
                [
                    str(java),
                    "-cp",
                    str(classes),
                    f"{GAME_ROOT.replace('/', '.')}.ui.Harness",
                ],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, result.returncode, result.stdout)

    def test_last_stand_synthetic_target_without_native_long_click(self):
        self._run_last_stand_synthetic(native_long_click=False)

    def test_last_stand_synthetic_target_with_native_long_click(self):
        self._run_last_stand_synthetic(native_long_click=True)

    def test_last_stand_multiple_candidates_fail_early(self):
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, _classes = self._compile_last_stand_target(
                work,
                native_long_click=False,
                extra_candidate=True,
            )
            with self.assertRaises(mod.injector.InjectError):
                mod.patch_buff_click_jar(
                    java,
                    target,
                    work,
                    GAME_ROOT,
                    parry=False,
                    instant=False,
                    force=False,
                    assassinate=False,
                    enemy_surge=False,
                )

    def test_last_stand_zero_candidates_fail_early(self):
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, _classes = self._compile_last_stand_target(
                work,
                native_long_click=False,
                info_click=False,
            )
            with self.assertRaises(mod.injector.InjectError):
                mod.patch_buff_click_jar(
                    java,
                    target,
                    work,
                    GAME_ROOT,
                    parry=False,
                    instant=False,
                    force=False,
                    assassinate=False,
                    enemy_surge=False,
                )

    @classmethod
    def _compile_ark_combat_target(
        cls,
        work: pathlib.Path,
    ) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
        src = work / "combat-src"
        target_classes = work / "combat-target-classes"
        payload_classes = work / "combat-payload-classes"
        package = GAME_ROOT.replace("/", ".")

        cls._write(
            src,
            f"{GAME_ROOT}/actors/Char.java",
            f"""
package {package}.actors;
public class Char {{
    public static boolean nativeHit = true;
    public static boolean invulnerable = false;
    public String defenseVerb() {
        return "Dodge";
    }
    public boolean attack(Char enemy) {{
        if (enemy == null) return false;
        if (invulnerable) return false;
        boolean landed = hit(this, enemy, false);
        int unrelated = 1;
        if (landed && unrelated == 1) {{
            return true;
        }}
        enemy.defenseVerb();
        return false;
    }}
    public static boolean hit(Char attacker, Char defender, boolean magic) {{
        return nativeHit;
    }}
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/actors/Eye.java",
            f"""
package {package}.actors;
public class Eye extends Char {{
    public String miss(Eye defender) {{
        return defender.defenseVerb();
    }}
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/actors/FeedbackHarness.java",
            f"""
package {package}.actors;
import com.spd.mod.mechanics.ModParryRiposte;
public class FeedbackHarness {{
    public static void main(String[] args) {{
        Eye eye = new Eye();
        ModParryRiposte.feedbackCalls = 0;
        String text = eye.miss(eye);
        if (!"Parried".equals(text)) throw new AssertionError(text);
        if (ModParryRiposte.feedbackCalls != 1) {{
            throw new AssertionError("feedbackCalls=" + ModParryRiposte.feedbackCalls);
        }}
    }}
}}
""",
        )

        cls._write(
            src,
            "com/spd/mod/mechanics/ModInstantKill.java",
            f"""
package com.spd.mod.mechanics;
import {package}.actors.Char;
public class ModInstantKill {{
    public static boolean enabled;
    public static int successfulCalls;
    public static void beginAttack(Char attacker, Char defender) {{
    }}
    public static void finishAttack(boolean successful) {{
        if (successful) resolveSuccessfulAttack(null, null);
    }}
    public static boolean resolveSuccessfulAttack(Char attacker, Char defender) {{
        successfulCalls++;
        return enabled;
    }}
}}
""",
        )
        cls._write(
            src,
            "com/spd/mod/mechanics/ModForceHit.java",
            f"""
package com.spd.mod.mechanics;
import {package}.actors.Char;
public class ModForceHit {{
    public static boolean enabled;
    public static boolean isForceHitEnabled(Char attacker) {{
        return enabled;
    }}
    public static boolean forceHitCheck(Char attacker, Char defender) {{
        return enabled;
    }}
}}
""",
        )
        cls._write(
            src,
            "com/spd/mod/mechanics/ModParryRiposte.java",
            f"""
package com.spd.mod.mechanics;
import {package}.actors.Char;
public class ModParryRiposte {{
    public static boolean enabled;
    public static int hitChecks;
    public static int feedbackCalls;
    public static boolean onHitCheck(Char attacker, Char defender) {{
        hitChecks++;
        return enabled;
    }}
    public static String defenseVerb(Char defender) {{
        feedbackCalls++;
        return "Parried";
    }}
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/actors/CombatHarness.java",
            f"""
package {package}.actors;
import com.spd.mod.mechanics.ModForceHit;
import com.spd.mod.mechanics.ModInstantKill;
import com.spd.mod.mechanics.ModParryRiposte;
public class CombatHarness {{
    private static void check(boolean value, String label) {{
        if (!value) throw new AssertionError(label);
    }}
    public static void main(String[] args) {{
        Char attacker = new Char();
        Char defender = new Char();

        Char.invulnerable = false;
        Char.nativeHit = false;
        ModForceHit.enabled = true;
        ModInstantKill.enabled = false;
        ModInstantKill.successfulCalls = 0;
        check(attacker.attack(defender), "Force Hit did not override legacy hit");
        check(ModInstantKill.successfulCalls == 2, "Force Hit attack should check Instant Kill at entry and after forced hit");

        Char.nativeHit = true;
        ModForceHit.enabled = false;
        ModInstantKill.enabled = true;
        ModInstantKill.successfulCalls = 0;
        check(attacker.attack(defender), "Instant Kill changed successful attack result");
        check(ModInstantKill.successfulCalls == 1, "Instant Kill did not hook successful attack return");

        Char.invulnerable = true;
        ModForceHit.enabled = false;
        ModInstantKill.enabled = true;
        ModInstantKill.successfulCalls = 0;
        check(!attacker.attack(defender), "Instant Kill alone bypassed invulnerability");
        check(ModInstantKill.successfulCalls == 0, "Instant Kill alone bypassed the native invulnerability stop");

        ModForceHit.enabled = true;
        ModInstantKill.enabled = true;
        ModInstantKill.successfulCalls = 0;
        check(attacker.attack(defender), "Force Hit + Instant Kill did not bypass invulnerability");
        check(ModInstantKill.successfulCalls == 1, "Force Hit + Instant Kill did not resolve at attack entry");
    }}
}}
""",
        )
        cls._write(
            src,
            f"{GAME_ROOT}/actors/ParryHarness.java",
            f"""
package {package}.actors;
import com.spd.mod.mechanics.ModForceHit;
import com.spd.mod.mechanics.ModInstantKill;
import com.spd.mod.mechanics.ModParryRiposte;
public class ParryHarness {{
    private static void check(boolean value, String label) {{
        if (!value) throw new AssertionError(label);
    }}
    public static void main(String[] args) {{
        Char attacker = new Char();
        Char defender = new Char();
        Char.invulnerable = false;
        ModInstantKill.enabled = false;
        ModParryRiposte.hitChecks = 0;
        ModParryRiposte.feedbackCalls = 0;

        Char.nativeHit = true;
        ModForceHit.enabled = false;
        ModParryRiposte.enabled = true;

        check(!Char.hit(attacker, defender, true),
                "Parry did not block a direct guaranteed/magic hit");
        check(ModParryRiposte.hitChecks == 1,
                "Direct hit did not reach unified Parry/Riposte hook");

        check(!attacker.attack(defender),
                "Parry did not force the normal attack to miss");
        check(ModParryRiposte.hitChecks == 2,
                "Normal attack did not use the same hit-check hook");
        check(ModParryRiposte.feedbackCalls == 1,
                "Parry feedback bridge was not used");

        Char.nativeHit = false;
        ModForceHit.enabled = true;
        check(attacker.attack(defender), "Force Hit did not override Parry");
        check(ModParryRiposte.hitChecks == 3,
                "Force Hit suppressed Parry/Riposte hit observation");
    }}
}}
""",
        )

        javac = cls._tool("javac")
        target_sources = [
            src / f"{GAME_ROOT}/actors/Char.java",
            src / f"{GAME_ROOT}/actors/Eye.java",
            src / f"{GAME_ROOT}/actors/CombatHarness.java",
            src / f"{GAME_ROOT}/actors/ParryHarness.java",
            src / f"{GAME_ROOT}/actors/FeedbackHarness.java",
            src / "com/spd/mod/mechanics/ModInstantKill.java",
            src / "com/spd/mod/mechanics/ModForceHit.java",
            src / "com/spd/mod/mechanics/ModParryRiposte.java",
        ]
        subprocess.run(
            [javac, "-d", str(target_classes), *map(str, target_sources)],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        payload_classes.mkdir(parents=True, exist_ok=True)
        for relative in (
            "com/spd/mod/mechanics/ModInstantKill.class",
            "com/spd/mod/mechanics/ModForceHit.class",
            "com/spd/mod/mechanics/ModParryRiposte.class",
        ):
            source = target_classes / relative
            destination = payload_classes / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)

        target = work / "ark-target.jar"
        with zipfile.ZipFile(target, "w") as jar:
            for class_file in target_classes.rglob("*.class"):
                if "com/spd/mod/" in class_file.as_posix():
                    continue
                jar.write(class_file, class_file.relative_to(target_classes).as_posix())

        payload = work / "combat-payload.jar"
        with zipfile.ZipFile(payload, "w") as jar:
            for class_file in payload_classes.rglob("*.class"):
                jar.write(class_file, class_file.relative_to(payload_classes).as_posix())

        return target, payload, target_classes

    @staticmethod
    def _run_combat_harness(classes: pathlib.Path) -> subprocess.CompletedProcess[str]:
        java = shutil.which("java")
        if java is None:
            raise unittest.SkipTest("java is unavailable")
        return subprocess.run(
            [
                java,
                "-cp",
                str(classes),
                f"{GAME_ROOT.replace('/', '.')}.actors.CombatHarness",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    @staticmethod
    def _run_parry_harness(classes: pathlib.Path) -> subprocess.CompletedProcess[str]:
        java = shutil.which("java")
        if java is None:
            raise unittest.SkipTest("java is unavailable")
        return subprocess.run(
            [
                java,
                "-cp",
                str(classes),
                f"{GAME_ROOT.replace('/', '.')}.actors.ParryHarness",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    @staticmethod
    def _run_feedback_harness(classes: pathlib.Path) -> subprocess.CompletedProcess[str]:
        java = shutil.which("java")
        if java is None:
            raise unittest.SkipTest("java is unavailable")
        return subprocess.run(
            [
                java,
                "-cp",
                str(classes),
                f"{GAME_ROOT.replace('/', '.')}.actors.FeedbackHarness",
            ],
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

    def test_nonhero_char_subclass_uses_jar_parry_feedback_bridge(self):
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, _payload, classes = self._compile_ark_combat_target(work)

            before = self._run_feedback_harness(classes)
            self.assertNotEqual(0, before.returncode)

            patches = mod.patch_parry_feedback_classes(
                java, target, work, GAME_ROOT
            )
            self.assertIn(f"{GAME_ROOT}/actors/Eye.class", patches)
            for entry, data in patches.items():
                target_class = classes / entry
                target_class.parent.mkdir(parents=True, exist_ok=True)
                target_class.write_bytes(data)

            after = self._run_feedback_harness(classes)
            self.assertEqual(0, after.returncode, after.stdout)


    def test_ark_legacy_hit_supported_by_ankh_jar_for_parry_force_and_instant(self):
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, payload, classes = self._compile_ark_combat_target(work)
            patched, enabled = mod.patch_ankh_char(
                java, target, payload, work, GAME_ROOT
            )
            self.assertIsNotNone(patched)
            self.assertEqual({"parry", "instant", "force"}, enabled)
            char_class = classes / f"{GAME_ROOT}/actors/Char.class"
            char_class.write_bytes(patched.read_bytes())

            result = self._run_combat_harness(classes)
            self.assertEqual(0, result.returncode, result.stdout)
            parry_result = self._run_parry_harness(classes)
            self.assertEqual(0, parry_result.returncode, parry_result.stdout)

    def test_ark_legacy_hit_supported_by_full_jar_for_parry_force_and_instant(self):
        java = pathlib.Path(self._tool("java"))
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            target, payload, classes = self._compile_ark_combat_target(work)
            helper = work / "SmmCharAttackPatcher.java"
            helper.write_text(
                mod.CHAR_HELPER.replace(
                    "__CHAR__", GAME_ROOT + "/actors/Char"
                ),
                encoding="utf-8",
            )
            patched = work / "Char.class"
            result = subprocess.run(
                [
                    str(java),
                    "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED",
                    str(helper),
                    str(target),
                    str(payload),
                    str(patched),
                ],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("Shared hit-check selected:", result.stdout)
            self.assertIn("direct", result.stdout)

            char_class = classes / f"{GAME_ROOT}/actors/Char.class"
            char_class.write_bytes(patched.read_bytes())
            harness = self._run_combat_harness(classes)
            self.assertEqual(0, harness.returncode, harness.stdout)
            parry_harness = self._run_parry_harness(classes)
            self.assertEqual(
                0, parry_harness.returncode, parry_harness.stdout
            )

    def test_legacy_wnduseitem_synthetic_bridge(self):
        java = pathlib.Path(self._tool("java"))
        javac = self._tool("javac")
        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            src = work / "src"
            classes = work / "classes"
            package = GAME_ROOT.replace("/", ".")

            self._write(
                src,
                f"{GAME_ROOT}/actors/hero/Hero.java",
                f"package {package}.actors.hero; public class Hero {{}}\n",
            )
            self._write(
                src,
                f"{GAME_ROOT}/Dungeon.java",
                f"""
package {package};
import {package}.actors.hero.Hero;
public class Dungeon {{
    public static Hero hero = new Hero();
}}
""",
            )
            self._write(
                src,
                f"{GAME_ROOT}/items/Item.java",
                f"""
package {package}.items;
import {package}.actors.hero.Hero;
public class Item {{
    public String actionName(String action, Hero hero) {{
        return action;
    }}
}}
""",
            )
            self._write(
                src,
                f"{GAME_ROOT}/messages/Messages.java",
                f"""
package {package}.messages;
public class Messages {{
    public static String get(Object object, String key, Object[] args) {{
        return "MSG:" + key;
    }}
}}
""",
            )
            self._write(
                src,
                f"{GAME_ROOT}/windows/WndUseItem.java",
                f"""
package {package}.windows;
import {package}.items.Item;
import {package}.messages.Messages;
public class WndUseItem {{
    public static String actionLabel;
    public static String title;
    public WndUseItem(Item item, String action) {{
        actionLabel = Messages.get(item, "ac_" + action, new Object[0]);
        title = Messages.get(item, "title", new Object[0]);
    }}
}}
""",
            )
            self._write(
                src,
                f"{GAME_ROOT}/windows/ActionHarness.java",
                f"""
package {package}.windows;
import {package}.items.Item;
public class ActionHarness {{
    public static void main(String[] args) {{
        new WndUseItem(new Item(), "Store");
        if (!"Store".equals(WndUseItem.actionLabel)) {{
            throw new AssertionError(WndUseItem.actionLabel);
        }}
        if (!"MSG:title".equals(WndUseItem.title)) {{
            throw new AssertionError(WndUseItem.title);
        }}
    }}
}}
""",
            )

            subprocess.run(
                [javac, "-d", str(classes), *[str(p) for p in src.rglob("*.java")]],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            target = work / "target.jar"
            with zipfile.ZipFile(target, "w") as jar:
                for class_file in classes.rglob("*.class"):
                    jar.write(class_file, class_file.relative_to(classes).as_posix())

            patched = mod.patch_wnd_use_item_action_names(
                java, target, work, GAME_ROOT
            )
            self.assertIsNotNone(patched)
            entry, patched_path = patched
            (classes / entry).write_bytes(patched_path.read_bytes())

            result = subprocess.run(
                [
                    str(java),
                    "-cp",
                    str(classes),
                    f"{package}.windows.ActionHarness",
                ],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, result.returncode, result.stdout)

            self._write(
                src,
                f"{GAME_ROOT}/windows/WndUseItem.java",
                f"""
package {package}.windows;
import {package}.Dungeon;
import {package}.items.Item;
import {package}.messages.Messages;
public class WndUseItem {{
    public static String actionLabel;
    public static String title;
    public WndUseItem(Item item, String action) {{
        actionLabel = item.actionName(action, Dungeon.hero);
        title = Messages.get(item, "title", new Object[0]);
    }}
}}
""",
            )
            subprocess.run(
                [javac, "-d", str(classes), *[str(p) for p in src.rglob("*.java")]],
                check=True,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            with zipfile.ZipFile(target, "w") as jar:
                for class_file in classes.rglob("*.class"):
                    jar.write(class_file, class_file.relative_to(classes).as_posix())

            self.assertIsNone(
                mod.patch_wnd_use_item_action_names(
                    java, target, work, GAME_ROOT
                )
            )
            native_result = subprocess.run(
                [
                    str(java),
                    "-cp",
                    str(classes),
                    f"{package}.windows.ActionHarness",
                ],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, native_result.returncode, native_result.stdout)


if __name__ == "__main__":
    unittest.main()
