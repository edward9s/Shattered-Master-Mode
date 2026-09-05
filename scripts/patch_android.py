from io import BytesIO
import re
import sys
from urllib.request import urlopen
import xml.etree.ElementTree as ET
from zipfile import ZipFile


PLAY_GAMES_MAVEN = (
    'https://dl.google.com/dl/android/maven2/'
    'com/google/android/gms/play-services-games-v2'
)

def patch_gradle(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        data = f.read()

    # 1. 修改包名：在原字串後加上 .mod
    data = re.sub(r"(appPackageName\s*=\s*'[^']+)'", r"\1.mod'", data)

    # 2. 修改顯示名稱：首字母加上中括號
    def label_replacer(match):
        val = match.group(1)
        if len(val) > 0:
            return f"appName = '[{val[0]}]{val[1:]}'"
        return match.group(0)
    
    data = re.sub(r"appName\s*=\s*'([^']+)'", label_replacer, data)

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(data)


def patch_play_games_version(build_file, android_build_file):
    with open(android_build_file, 'r', encoding='utf-8') as f:
        android_build = f.read()

    dependency = r'(com\.google\.android\.gms:play-services-games-v2:)\+'
    if not re.search(dependency, android_build):
        return

    with open(build_file, 'r', encoding='utf-8') as f:
        build = f.read()

    match = re.search(r'appAndroidMinSDK\s*=\s*(\d+)', build)
    if not match:
        raise RuntimeError('Unable to determine appAndroidMinSDK')
    app_min_sdk = int(match.group(1))

    with urlopen(f'{PLAY_GAMES_MAVEN}/maven-metadata.xml', timeout=30) as response:
        metadata = ET.fromstring(response.read())

    compatible_version = None
    for version_node in reversed(metadata.findall('./versioning/versions/version')):
        version = version_node.text
        aar_url = f'{PLAY_GAMES_MAVEN}/{version}/play-services-games-v2-{version}.aar'
        with urlopen(aar_url, timeout=30) as response:
            with ZipFile(BytesIO(response.read())) as aar:
                manifest = ET.fromstring(aar.read('AndroidManifest.xml'))

        uses_sdk = manifest.find('uses-sdk')
        android_min_sdk = uses_sdk.get(
            '{http://schemas.android.com/apk/res/android}minSdkVersion', '1'
        )
        if int(android_min_sdk) <= app_min_sdk:
            compatible_version = version
            break

    if not compatible_version:
        raise RuntimeError(f'No Play Games Services version supports minSdk {app_min_sdk}')

    android_build = re.sub(
        dependency, rf'\g<1>{compatible_version}', android_build
    )
    with open(android_build_file, 'w', encoding='utf-8') as f:
        f.write(android_build)

    print(
        f'Using play-services-games-v2:{compatible_version} '
        f'for minSdk {app_min_sdk}'
    )


def patch_proguard(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        data = f.read()

    rules = (
        '-keep class com.spd.mod.mechanics.ModDebug { *; }',
        '-keep class com.spd.mod.mechanics.ModDebug$* { *; }',
        '-keep class com.spd.mod.mechanics.ModAssassinBuff { *; }',
        '-keep class com.spd.mod.mechanics.ModAssassinBuff$* { *; }',
        '-keep class com.spd.mod.mechanics.ModAssassin { *; }',
        '-keep class com.spd.mod.mechanics.ModAssassin$* { *; }',
        '-keep class com.spd.mod.mechanics.ModFlash { *; }',
        '-keep class com.spd.mod.mechanics.ModFlash$* { *; }',
        '-keep class com.spd.mod.mechanics.ModParryRiposte { *; }',
        '-keep class com.spd.mod.mechanics.ModParryRiposte$* { *; }',
        '-keep class com.spd.mod.journal.ModTotalInfoOverlay { *; }',
        '-keep class com.spd.mod.journal.ModTotalInfoOverlay$* { *; }',
        '-keep class com.spd.mod.journal.WndTotalBuffInfo { *; }',
        '-keep class com.spd.mod.journal.WndTotalBuffInfo$* { *; }',
        '-keep class com.spd.mod.mechanics.ModEnemySurge { *; }',
        '-keep class com.spd.mod.mechanics.ModEnemySurge$* { *; }',
        '-keep class com.spd.mod.journal.ModEnemySurgeInfoOverlay { *; }',
        '-keep class com.spd.mod.journal.ModEnemySurgeInfoOverlay$* { *; }',
        '-keep class com.spd.mod.journal.WndEnemySurgeInfo { *; }',
        '-keep class com.spd.mod.journal.WndEnemySurgeInfo$* { *; }',
        '-keep class com.spd.mod.mechanics.ModLootBuff { *; }',
        '-keep class com.spd.mod.mechanics.ModLootBuff$* { *; }',
        '-keep class com.spd.mod.mechanics.ModLootStorage { *; }',
        '-keep class com.spd.mod.mechanics.ModLootStorage$* { *; }',
        '-keep class com.spd.mod.mechanics.ModLoot { *; }',
        '-keep class com.spd.mod.mechanics.ModLoot$* { *; }',
        '-keep class com.spd.mod.mechanics.ModItemKind { *; }',
        '-keep class com.spd.mod.mechanics.ModItemKind$* { *; }',
        '-keep class com.spd.mod.mechanics.ModItemOrder { *; }',
        '-keep class com.spd.mod.mechanics.ModItemOrder$* { *; }',
        '-keep class com.spd.mod.journal.ModLootBuffOverlay { *; }',
        '-keep class com.spd.mod.journal.ModLootBuffOverlay$* { *; }',
        '-keep class com.spd.mod.items.WndModLoot { *; }',
        '-keep class com.spd.mod.items.WndModLoot$* { *; }',
        '-keep class com.spd.mod.items.Mod** { *; }',
        '-keep class com.spd.mod.mechanics.ModBlast { *; }',
        '-keep class com.spd.mod.mechanics.ModBlast$* { *; }',
        '-keep class com.spd.mod.mechanics.ModSight { *; }',
        '-keep class com.spd.mod.mechanics.ModSight$* { *; }',
        '-keepclassmembers class com.shatteredpixel.shatteredpixeldungeon.levels.Terrain { public static final int *; }',
    )
    missing = [rule for rule in rules if rule not in data]
    if missing:
        data = data.rstrip() + (
            '\n\n# Keep SMM binary-injection payload stable\n'
            + '\n'.join(missing)
            + '\n'
        )

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(data)


def patch_manifest(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        data = f.read()

    # 3. 新增外部儲存權限
    permissions = (
        '\t<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />\n'
        '\t<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />\n'
        '\t<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />\n'
    )
    if 'MANAGE_EXTERNAL_STORAGE' not in data:
        data = data.replace('\t<application', permissions + '\t<application', 1)

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(data)

if __name__ == '__main__':
    # 預設路徑對應 CI/CD 執行時的相對位置
    patch_gradle('spd_src/build.gradle')
    patch_play_games_version(
        'spd_src/build.gradle', 'spd_src/android/build.gradle'
    )
    patch_proguard('spd_src/android/proguard-rules.pro')
    patch_manifest('spd_src/android/src/main/AndroidManifest.xml')