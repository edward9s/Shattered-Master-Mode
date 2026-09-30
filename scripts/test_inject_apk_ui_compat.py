import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import _inject_action_name as action_name
import _inject_apk_core as injector
import _inject_last_stand_click as last_stand_click
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

    def test_last_stand_direct_patch_preserves_click_body_and_adds_long_click(self):
        patched = last_stand_click.patch(
            injector, button_text(), BUFF_BUTTON, GAME
        )
        self.assertIn(
            "Lcom/spd/mod/mechanics/ModLastStand;->open()V",
            patched,
        )
        self.assertIn(
            ".method private smmLastStandInfo()V",
            patched,
        )
        self.assertIn(
            f"invoke-direct {{p0}}, {BUFF_BUTTON}->smmLastStandInfo()V",
            patched,
        )
        self.assertIn(".method protected onClick()V", patched)
        self.assertIn(".method protected onLongClick()Z", patched)
        self.assertIn(
            "invoke-super {p0}, Lcom/watabou/noosa/ui/Button;->onLongClick()Z",
            patched,
        )

    def test_last_stand_existing_long_click_is_preserved_for_normal_buffs(self):
        patched = last_stand_click.patch(
            injector,
            button_text(native_long=True),
            BUFF_BUTTON,
            GAME,
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
            f"invoke-direct {{p0}}, {BUFF_BUTTON}->smmLastStandInfo()V",
            patched,
        )

    def test_last_stand_target_selection_fails_on_zero_or_multiple_candidates(self):
        with self.assertRaises(injector.InjectError):
            last_stand_click.find_target(injector, {}, GAME)

        first = injector.SmaliClass.from_text(
            pathlib.Path("BuffIcon.smali"),
            button_text(),
        )
        second = injector.SmaliClass.from_text(
            pathlib.Path("SecondBuffIcon.smali"),
            button_text(SECOND_BUTTON),
        )
        with self.assertRaises(injector.InjectError):
            last_stand_click.find_target(
                injector,
                {
                    first.descriptor: first,
                    second.descriptor: second,
                },
                GAME,
            )

    def test_last_stand_target_selection_ignores_non_info_click(self):
        non_info = injector.SmaliClass.from_text(
            pathlib.Path("BuffIcon.smali"),
            button_text(info_click=False),
        )
        with self.assertRaises(injector.InjectError):
            last_stand_click.find_target(
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


if __name__ == "__main__":
    unittest.main()
