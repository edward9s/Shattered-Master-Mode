import pathlib
import sys
import unittest
from types import SimpleNamespace

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import _inject_action_name as action_name
import _inject_apk_core as injector
import _inject_buff_click as buff_click
import _inject_apk_ankh as ankh_mode


GAME = "Lcom/shatteredpixel/shatteredpixeldungeon/"
BUFF_BUTTON = GAME + "ui/BuffIndicator$BuffIcon;"
SECOND_BUTTON = GAME + "ui/BuffIndicator$SecondBuffIcon;"
BUFF = GAME + "actors/buffs/Buff;"
WND_INFO = GAME + "windows/WndInfoBuff;"
WND_USE = GAME + "windows/WndUseItem;"
MESSAGES = GAME + "messages/Messages;"


def button_text(descriptor=BUFF_BUTTON, *, native_long=False, info_click=True):
    click = (
        f"    new-instance v1, {WND_INFO}\n"
        f"    invoke-direct {{v1, v0}}, {WND_INFO}-><init>({BUFF})V\n"
        if info_click
        else "    const/4 v1, 0x0\n"
    )
    long_click = (
        "\n.method protected onLongClick()Z\n"
        "    .locals 1\n"
        "    const/4 v0, 0x1\n"
        "    return v0\n"
        ".end method\n"
        if native_long
        else ""
    )
    return (
        f".class private {descriptor}\n"
        ".super Lcom/watabou/noosa/ui/Button;\n"
        f".field private buff:{BUFF}\n\n"
        ".method protected onClick()V\n"
        "    .locals 2\n"
        f"    iget-object v0, p0, {descriptor}->buff:{BUFF}\n"
        + click
        + "    return-void\n"
        ".end method\n"
        + long_click
    )


class ApkUiCompatTests(unittest.TestCase):

    def test_full_buff_click_patch_dispatches_all_mod_buffs(self):
        handlers = buff_click.selected_handlers(
            parry=True,
            instant=True,
            force=True,
            assassinate=True,
        )
        patched = buff_click.patch(
            injector, button_text(), BUFF_BUTTON, GAME, handlers
        )

        for hook in (
            "Lcom/spd/mod/mechanics/ModLastStand;->open()V",
            "Lcom/spd/mod/mechanics/ModParryRiposte;->openInfo()V",
            "Lcom/spd/mod/mechanics/ModInstantKill;->openInfo()V",
            "Lcom/spd/mod/mechanics/ModForceHit;->openInfo()V",
            "Lcom/spd/mod/mechanics/ModAssassinate;->openInfo()V",
        ):
            self.assertIn(hook, patched)

        self.assertIn(".method private smmNativeInfo()V", patched)
        self.assertIn(
            f"invoke-direct {{p0}}, {BUFF_BUTTON}->smmNativeInfo()V",
            patched,
        )
        self.assertIn(".method protected onClick()V", patched)
        self.assertIn(".method protected onLongClick()Z", patched)
        self.assertIn(
            "invoke-super {p0}, Lcom/watabou/noosa/ui/Button;->onLongClick()Z",
            patched,
        )

    def test_narrow_buff_click_patch_only_references_kept_features(self):
        handlers = buff_click.selected_handlers(
            parry=True,
            instant=True,
            force=False,
            assassinate=True,
        )
        patched = buff_click.patch(
            injector, button_text(), BUFF_BUTTON, GAME, handlers
        )

        self.assertIn(
            "Lcom/spd/mod/mechanics/ModLastStand;->open()V",
            patched,
        )
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModInstantKill;->openInfo()V",
            patched,
        )
        self.assertNotIn("Lcom/spd/mod/mechanics/ModForceHit;", patched)
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModParryRiposte;->openInfo()V",
            patched,
        )
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModAssassinate;->openInfo()V",
            patched,
        )

    def test_existing_long_click_is_preserved_for_normal_buffs(self):
        handlers = buff_click.selected_handlers(
            parry=False,
            instant=False,
            force=False,
            assassinate=False,
        )
        patched = buff_click.patch(
            injector,
            button_text(native_long=True),
            BUFF_BUTTON,
            GAME,
            handlers,
        )
        self.assertIn(
            ".method private smmNativeLongClick()Z",
            patched,
        )
        self.assertIn(
            f"invoke-direct {{p0}}, {BUFF_BUTTON}->smmNativeLongClick()Z",
            patched,
        )
        self.assertIn(
            f"invoke-direct {{p0}}, {BUFF_BUTTON}->smmNativeInfo()V",
            patched,
        )

    def test_buff_click_target_selection_fails_on_zero_or_multiple_candidates(self):
        with self.assertRaises(injector.InjectError):
            buff_click.find_target(injector, {}, GAME)

        first = injector.SmaliClass.from_text(
            pathlib.Path("BuffIcon.smali"),
            button_text(),
        )
        second = injector.SmaliClass.from_text(
            pathlib.Path("SecondBuffIcon.smali"),
            button_text(SECOND_BUTTON),
        )
        with self.assertRaises(injector.InjectError):
            buff_click.find_target(
                injector,
                {
                    first.descriptor: first,
                    second.descriptor: second,
                },
                GAME,
            )

    def test_buff_click_target_selection_ignores_non_info_click(self):
        non_info = injector.SmaliClass.from_text(
            pathlib.Path("BuffIcon.smali"),
            button_text(info_click=False),
        )
        with self.assertRaises(injector.InjectError):
            buff_click.find_target(
                injector,
                {non_info.descriptor: non_info},
                GAME,
            )

    def test_legacy_action_name_patch_routes_ac_labels_to_item(self):
        proto = (
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
            "Ljava/lang/String;"
        )
        text = (
            f".class public {WND_USE}\n"
            ".super Ljava/lang/Object;\n\n"
            ".method public constructor <init>()V\n"
            "    .locals 4\n"
            '    const-string v1, "ac_"\n'
            f"    invoke-static {{v0, v1, v2}}, {MESSAGES}->get{proto}\n"
            "    move-result-object v3\n"
            "    return-void\n"
            ".end method\n"
        )
        patched = action_name.patch(injector, text, WND_USE, GAME)
        self.assertIn(f"{WND_USE}->smmActionName{proto}", patched)
        self.assertIn("->actionName(Ljava/lang/String;", patched)
        self.assertIn('const-string v0, "ac_"', patched)

    def test_native_wnduseitem_with_other_messages_get_is_not_patched(self):
        proto = (
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)"
            "Ljava/lang/String;"
        )
        text = (
            f".class public {WND_USE}\n"
            ".super Ljava/lang/Object;\n\n"
            ".method public constructor <init>()V\n"
            "    .locals 4\n"
            '    const-string v1, "title"\n'
            f"    invoke-static {{v0, v1, v2}}, {MESSAGES}->get{proto}\n"
            "    move-result-object v3\n"
            "    return-void\n"
            ".end method\n"
        )
        target = injector.SmaliClass.from_text(
            pathlib.Path("WndUseItem.smali"),
            text,
        )
        self.assertIsNone(
            action_name.find_target(
                injector,
                {target.descriptor: target},
                GAME,
            )
        )

    def test_ankh_apk_no_longer_patches_item_properties(self):
        self.assertFalse(hasattr(ankh_mode, "_ACTION_MESSAGE_BUNDLE_RE"))
        self.assertFalse(hasattr(ankh_mode, "_ACTION_MESSAGES"))
        self.assertFalse(hasattr(ankh_mode, "_append_action_messages"))

    def test_ankh_payload_builder_resolves_required_roots_without_module_globals(self):
        class FakeInjectError(Exception):
            pass

        class UnsupportedCapability:
            compatible = False
            detail = "unsupported"

        class Profile:
            def get(self, key):
                return UnsupportedCapability()

        full_prefix = "Lcom/spd/mod/"
        mod_ankh = full_prefix + "items/ModAnkh;"
        last_stand = full_prefix + "mechanics/ModLastStand;"
        last_stand_tag = full_prefix + "journal/ModLastStandTag;"
        parry = full_prefix + "mechanics/ModParryRiposte;"
        assassinate = full_prefix + "mechanics/ModAssassinate;"
        ankh_item = object()
        last_stand_item = SimpleNamespace(descriptor=last_stand)
        last_stand_tag_item = SimpleNamespace(descriptor=last_stand_tag)
        parry_item = SimpleNamespace(descriptor=parry)
        assassinate_item = SimpleNamespace(descriptor=assassinate)

        def dependencies(item):
            if item is ankh_item:
                return {last_stand}
            return set()

        fake_injector = SimpleNamespace(
            MOD_ANKH=mod_ankh,
            TARGET_API_PREFIXES=("Ltarget/",),
            InjectError=FakeInjectError,
            smali_dependencies=dependencies,
            relocated_helper_descriptor=lambda descriptor: descriptor,
            rewrite_smali_class=lambda item, relocations: item,
            log=lambda message: None,
        )
        public_module = SimpleNamespace(
            injector=fake_injector,
            FULL_SMM_PREFIX=full_prefix,
            _current_abi_profile=Profile(),
            _current_game_prefix=None,
            _original_find_class=None,
            _original_patch_dungeon=None,
        )

        ankh_mode.configure(public_module)
        payload, relocations = fake_injector.build_debug_payload(
            {
                mod_ankh: ankh_item,
                last_stand: last_stand_item,
                last_stand_tag: last_stand_tag_item,
                parry: parry_item,
                assassinate: assassinate_item,
            },
            {},
        )

        self.assertIn(last_stand, payload)
        self.assertEqual({}, relocations)
        self.assertEqual(
            {last_stand, last_stand_tag},
            public_module._ankh_core_payload_descriptors,
        )
        self.assertFalse(public_module._ankh_parry_riposte_enabled)
        self.assertTrue(public_module._ankh_assassinate_enabled)
        self.assertEqual(
            {assassinate},
            public_module._ankh_optional_payload_descriptors["assassinate"],
        )


if __name__ == "__main__":
    unittest.main()
