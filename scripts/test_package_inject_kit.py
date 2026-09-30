import pathlib
import subprocess
import sys
import tempfile
import unittest

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
                "_inject_last_stand_click.py",
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


if __name__ == "__main__":
    unittest.main()
