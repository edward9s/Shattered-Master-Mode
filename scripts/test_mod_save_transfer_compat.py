import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SAVE_TRANSFER = ROOT / "core/src/main/java/com/spd/mod/mechanics/ModSaveTransfer.java"


class ModSaveTransferCompatTests(unittest.TestCase):

    @staticmethod
    def _tool(name: str) -> str:
        value = shutil.which(name)
        if value is None:
            raise unittest.SkipTest(f"{name} is unavailable")
        return value

    @staticmethod
    def _write(root: pathlib.Path, relative: str, text: str) -> pathlib.Path:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def _compile_and_run(self, file_utils_source: str, extra_sources=None):
        javac = self._tool("javac")
        java = self._tool("java")
        extra_sources = extra_sources or {}

        with tempfile.TemporaryDirectory() as tmp:
            work = pathlib.Path(tmp)
            src = work / "src"
            classes = work / "classes"
            save_dir = work / "save"
            save_dir.mkdir()

            self._write(
                src,
                "com/shatteredpixel/shatteredpixeldungeon/Dungeon.java",
                """
package com.shatteredpixel.shatteredpixeldungeon;
public final class Dungeon {
    public static void saveAll() {
    }
}
""",
            )
            self._write(
                src,
                "com/shatteredpixel/shatteredpixeldungeon/utils/GLog.java",
                """
package com.shatteredpixel.shatteredpixeldungeon.utils;
public final class GLog {
    public static void h(String text, Object[] args) {
    }
    public static void w(String text, Object[] args) {
    }
}
""",
            )
            self._write(
                src,
                "com/watabou/noosa/Game.java",
                """
package com.watabou.noosa;
public class Game {
    public static void runOnRenderThread(Runnable runnable) {
        runnable.run();
    }
}
""",
            )
            self._write(
                src,
                "com/watabou/utils/DeviceCompat.java",
                """
package com.watabou.utils;
public final class DeviceCompat {
    public static boolean isDesktop() {
        return true;
    }
}
""",
            )
            self._write(
                src,
                "com/watabou/utils/FileUtils.java",
                file_utils_source,
            )
            for relative, source in extra_sources.items():
                self._write(src, relative, source)

            target_source = self._write(
                src,
                "com/spd/mod/mechanics/ModSaveTransfer.java",
                SAVE_TRANSFER.read_text(encoding="utf-8"),
            )
            self._write(
                src,
                "com/spd/mod/mechanics/SavePathHarness.java",
                """
package com.spd.mod.mechanics;
import java.io.File;
import java.lang.reflect.Method;

public final class SavePathHarness {
    public static void main(String[] args) throws Exception {
        File expected = new File(args[0]).getCanonicalFile();
        com.watabou.utils.FileUtils.configure(expected);

        Method method = ModSaveTransfer.class.getDeclaredMethod(
                "desktopSaveDirectory");
        method.setAccessible(true);
        File actual = (File) method.invoke(null);

        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "expected=" + expected + " actual=" + actual);
        }
    }
}
""",
            )

            sources = [str(path) for path in src.rglob("*.java")]
            result = subprocess.run(
                [javac, "-d", str(classes), *sources],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, result.returncode, result.stdout)

            run = subprocess.run(
                [
                    java,
                    "-cp",
                    str(classes),
                    "com.spd.mod.mechanics.SavePathHarness",
                    str(save_dir),
                ],
                check=False,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
            self.assertEqual(0, run.returncode, run.stdout)
            self.assertTrue(target_source.is_file())

    def test_legacy_fileutils_getdir_without_filehandle_api(self):
        self._compile_and_run(
            """
package com.watabou.utils;
import java.io.File;

public final class FileUtils {
    private static File root;

    public static void configure(File directory) {
        root = directory;
    }

    public static File getDir(String name) {
        return name.isEmpty() ? root : new File(root, name);
    }
}
"""
        )

    def test_legacy_filehandle_backing_field_without_file_method(self):
        self._compile_and_run(
            """
package com.watabou.utils;
import java.io.File;
import com.badlogic.gdx.files.FileHandle;

public final class FileUtils {
    private static File root;

    public static void configure(File directory) {
        root = directory;
    }

    public static FileHandle getFileHandle(String name) {
        return new FileHandle(name.isEmpty() ? root : new File(root, name));
    }
}
""",
            {
                "com/badlogic/gdx/files/FileHandle.java": """
package com.badlogic.gdx.files;
import java.io.File;

public class FileHandle {
    protected final File file;

    public FileHandle(File file) {
        this.file = file;
    }
}
"""
            },
        )


if __name__ == "__main__":
    unittest.main()
