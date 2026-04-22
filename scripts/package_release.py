import os
import sys
import shutil
import zipfile
import urllib.request

def package():
    if len(sys.argv) < 4:
        print("Usage: python package_release.py <UPSTREAM_TAG> <MOD_VER> <SIGNED_APK>")
        sys.exit(1)

    upstream_tag = sys.argv[1]
    mod_ver = sys.argv[2]
    signed_apk = sys.argv[3]

    out_dir = "out"
    if not os.path.exists(out_dir):
        os.makedirs(out_dir)

    # --- 1. 打包 Android APK ---
    apk_name = f"SPD-{upstream_tag}-m{mod_ver}-Android.apk"
    dest_apk = os.path.join(out_dir, apk_name)
    if os.path.exists(signed_apk):
        shutil.copy2(signed_apk, dest_apk)
    else:
        print(f"Error: Signed APK not found at {signed_apk}")
        sys.exit(1)

    # --- 2. 下載並解壓縮官方 Windows ZIP ---
    win_zip_url = f"https://github.com/00-Evan/shattered-pixel-dungeon/releases/download/{upstream_tag}/ShatteredPD-{upstream_tag}-Windows.zip"
    official_zip = "official.zip"
    temp_win = "temp_win"
    
    urllib.request.urlretrieve(win_zip_url, official_zip)
    with zipfile.ZipFile(official_zip, 'r') as zip_ref:
        zip_ref.extractall(temp_win)

    # 精準鎖定 Gradle 編譯產出物
    new_jar = "spd_src/desktop/build/libs/desktop-release.jar"
    if not os.path.exists(new_jar):
        print(f"Error: Compiled desktop JAR not found at {new_jar}")
        sys.exit(1)

    # --- 3. 輸出獨立的 Java 執行檔 (JAR) ---
    java_jar_name = f"SPD-{upstream_tag}-m{mod_ver}-Java.jar"
    java_jar_path = os.path.join(out_dir, java_jar_name)
    shutil.copy2(new_jar, java_jar_path)

    # --- 4. 替換 Windows ZIP 內的官方 JAR 並重新打包 ---
    old_jar = None
    for root, dirs, files in os.walk(temp_win):
        for file in files:
            if file.startswith("desktop-") and file.endswith(".jar"):
                old_jar = os.path.join(root, file)
                break
        if old_jar: break

    if old_jar:
        shutil.copy2(new_jar, old_jar)
        
        new_zip_name = f"SPD-{upstream_tag}-m{mod_ver}-Windows.zip"
        new_zip_path = os.path.join(out_dir, new_zip_name)
        
        with zipfile.ZipFile(new_zip_path, 'w', zipfile.ZIP_DEFLATED) as new_zip:
            for root, dirs, files in os.walk(temp_win):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, temp_win)
                    new_zip.write(full_path, rel_path)
    else:
        print("Error: Could not find the main game JAR in the official zip")
        sys.exit(1)

    os.remove(official_zip)
    shutil.rmtree(temp_win)

if __name__ == "__main__":
    package()
