import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import package_inject_kit as package


class InjectionKitPackagingTests(unittest.TestCase):

    def test_packaged_injectors_import_from_isolated_kit(self):
        repo = pathlib.Path(__file__).resolve().parents[1]

        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            donor_apk = root / "donor.apk"
            donor_jar = root / "donor.jar"
            donor_apk.write_bytes(b"apk")
            donor_jar.write_bytes(b"jar")
            kit = root / "kit"

            package.populate_kit(kit, repo, donor_apk, donor_jar)

            expected = {
                "inject_apk.py",
                "_attack_hook_common.py",
                "_inject_apk_core.py",
                "_inject_apk_ankh.py",
                "_inject_action_name.py",
                "_inject_buff_click.py",
                "inject_jar.py",
                "_inject_jar_core.py",
            }
            self.assertTrue(expected.issubset({p.name for p in kit.iterdir()}))

            result = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    "import inject_apk; import inject_jar",
                ],
                cwd=kit,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stdout)


    def _write_parry_donors(
        self,
        root: pathlib.Path,
        *,
        jar_extra: bytes = b"",
        apk_extra: bytes = b"",
    ):
        donor_jar = root / "donor.jar"
        donor_apk = root / "donor.apk"
        parry = b"onHitCheck resolveDirectDamage defenseVerb " + jar_extra
        with zipfile.ZipFile(donor_jar, "w") as jar:
            jar.writestr(package.PARRY_CLASS, parry)
        with zipfile.ZipFile(donor_apk, "w") as apk:
            apk.writestr(
                "classes.dex",
                b"onHitCheck resolveDirectDamage " + apk_extra,
            )
        return donor_apk, donor_jar

    def test_parry_donor_validation_accepts_focus_free_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            donor_apk, donor_jar = self._write_parry_donors(root)
            package.validate_parry_donors(donor_apk, donor_jar)

    def test_parry_donor_validation_rejects_focus_era_jar(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            donor_apk, donor_jar = self._write_parry_donors(
                root,
                jar_extra=b"MonkEnergy$MonkAbility$Focus$FocusBuff",
            )
            with self.assertRaisesRegex(RuntimeError, "obsolete hook"):
                package.validate_parry_donors(donor_apk, donor_jar)

    def test_parry_donor_validation_rejects_focus_era_nested_class(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            donor_apk, donor_jar = self._write_parry_donors(root)
            with zipfile.ZipFile(donor_jar, "a") as jar:
                jar.writestr(
                    "com/spd/mod/mechanics/ModParryRiposte$TotalParryFocus.class",
                    b"old focus bridge",
                )
            with self.assertRaisesRegex(RuntimeError, "obsolete Parry lifecycle"):
                package.validate_parry_donors(donor_apk, donor_jar)

    def test_parry_donor_validation_rejects_focus_era_apk_marker(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            donor_apk, donor_jar = self._write_parry_donors(
                root,
                apk_extra=b"ModParryRiposte$TotalParryFocus",
            )
            with self.assertRaisesRegex(RuntimeError, "obsolete Parry ABI marker"):
                package.validate_parry_donors(donor_apk, donor_jar)


if __name__ == "__main__":
    unittest.main()
