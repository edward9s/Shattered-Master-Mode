import pathlib
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import _inject_apk_core as apk
import _inject_jar_core as jar
from _inject_app_name import AppNameError, smm_app_name


def _string_pool(strings):
    encoded = []
    offsets = []
    cursor = 0
    for value in strings:
        payload = apk.encode_axml_pool_string(value, True)
        offsets.append(cursor)
        encoded.append(payload)
        cursor += len(payload)

    region = bytearray(b"".join(encoded))
    while len(region) % 4:
        region.append(0)

    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(region)
    out = bytearray()
    out.extend(struct.pack("<HHI", apk.RES_STRING_POOL_TYPE, header_size, size))
    out.extend(struct.pack(
        "<IIIII",
        len(strings),
        0,
        apk.RES_STRING_POOL_UTF8_FLAG,
        strings_start,
        0,
    ))
    out.extend(struct.pack("<" + "I" * len(offsets), *offsets))
    out.extend(region)
    return bytes(out)


def _literal_label_manifest(app_name):
    strings = [
        "http://schemas.android.com/apk/res/android",
        "application",
        "label",
        app_name,
    ]
    pool = _string_pool(strings)

    resource_ids = [
        0,
        0,
        apk.ANDROID_LABEL_RESOURCE_ID,
        0,
    ]
    resource_map = (
        struct.pack(
            "<HHI",
            apk.RES_XML_RESOURCE_MAP_TYPE,
            8,
            8 + 4 * len(resource_ids),
        )
        + struct.pack("<" + "I" * len(resource_ids), *resource_ids)
    )

    start = bytearray()
    start.extend(struct.pack(
        "<HHIII",
        apk.RES_XML_START_ELEMENT_TYPE,
        16,
        56,
        0,
        apk.RES_XML_NO_INDEX,
    ))
    start.extend(struct.pack(
        "<IIHHHHHH",
        apk.RES_XML_NO_INDEX,
        1,
        20,
        20,
        1,
        0,
        0,
        0,
    ))
    start.extend(struct.pack(
        "<IIIHBBI",
        0,
        2,
        3,
        8,
        0,
        apk.RES_VALUE_TYPE_STRING,
        3,
    ))
    end = struct.pack(
        "<HHIIIII",
        apk.RES_XML_END_ELEMENT_TYPE,
        16,
        24,
        0,
        apk.RES_XML_NO_INDEX,
        apk.RES_XML_NO_INDEX,
        1,
    )

    body = pool + resource_map + bytes(start) + end
    return struct.pack("<HHI", apk.RES_XML_TYPE, 8, 8 + len(body)) + body


class AppNameTests(unittest.TestCase):

    def test_shared_transformation(self):
        self.assertEqual("[R]ogueNights", smm_app_name("RogueNights"))
        self.assertEqual(
            "[M]agic Ling Pixel Dungeon",
            smm_app_name("Magic Ling Pixel Dungeon"),
        )

    def test_invalid_path_name_fails(self):
        with self.assertRaises(AppNameError):
            smm_app_name("Bad/Name")

    def test_jar_manifest_title_is_rewritten(self):
        source = (
            b"Manifest-Version: 1.0\r\n"
            b"Main-Class: example.DesktopLauncher\r\n"
            b"Specification-Title: RogueNights\r\n"
            b"Specification-Version: 0.5.3\r\n"
            b"\r\n"
        )
        patched, old_name, new_name = jar.patch_manifest_specification_title(source)
        self.assertEqual("RogueNights", old_name)
        self.assertEqual("[R]ogueNights", new_name)
        self.assertIn(b"Specification-Title: [R]ogueNights\r\n", patched)

    def test_apk_literal_application_label_is_rewritten(self):
        source = _literal_label_manifest("RogueNights")
        patched, old_name, new_name = apk.patch_binary_manifest_app_name(source)
        self.assertEqual("RogueNights", old_name)
        self.assertEqual("[R]ogueNights", new_name)

        chunks = list(apk.axml_chunks(patched))
        string_chunk = next(c for c in chunks if c[1] == apk.RES_STRING_POOL_TYPE)
        pool = apk.parse_axml_string_pool(patched, string_chunk[0])
        self.assertIn("[R]ogueNights", pool.strings)


if __name__ == "__main__":
    unittest.main()
