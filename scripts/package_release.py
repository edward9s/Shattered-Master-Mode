import os
import sys
import shutil
import zipfile
import urllib.request
import glob

def package():
    # 傳入參數減為 3 個：UPSTREAM_TAG, MOD_VER, SIGNED_APK
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
        print(f"Success: Copied APK to {dest_apk}")
    else:
        print(f"Error: Signed APK not found at {signed_apk}")
        sys.exit(1)

    # --- 2. 打包 Windows ZIP ---
    win_zip_url = f"https://github.com/00-Evan/shattered-pixel-dungeon/releases/download/{upstream_tag}/ShatteredPD-{upstream_tag}-Windows.zip"
    official_zip = "official.zip"
    temp_win = "temp_win"
    
    print(f"Downloading official Windows zip: {win_zip_url}")
    try:
        urllib.request.urlretrieve(win_zip_url, official_zip)
    except Exception as e:
        print(f"Error: Failed to download official zip. {e}")
        sys.exit(1)

    with zipfile.ZipFile(official_zip, 'r') as zip_ref:
        zip_ref.extractall(temp_win)

    new_jar_list = glob.glob("spd_src/**/desktop-*.jar", recursive=True)
    if not new_jar_list:
        print("Error: Compiled desktop JAR not found in spd_src")
        sys.exit(1)
    new_jar = new_jar_list[0]

    old_jar = None
    for root, dirs, files in os.walk(temp_win):
        for file in files:
            if file.endswith(".jar"):
                full_path = os.path.join(root, file)
                if os.path.getsize(full_path) > 1024 * 1024:
                    old_jar = full_path
                    break
        if old_jar: break

    if old_jar:
        print(f"Replacing {old_jar} with {new_jar}")
        shutil.copy2(new_jar, old_jar)
        
        new_zip_name = f"SPD-{upstream_tag}-m{mod_ver}-Windows.zip"
        new_zip_path = os.path.join(out_dir, new_zip_name)
        
        with zipfile.ZipFile(new_zip_path, 'w', zipfile.ZIP_DEFLATED) as new_zip:
            for root, dirs, files in os.walk(temp_win):
                for file in files:
                    full_path = os.path.join(root, file)
                    rel_path = os.path.relpath(full_path, temp_win)
                    new_zip.write(full_path, rel_path)
        print(f"Success: Created Windows zip at {new_zip_path}")
    else:
        print("Error: Could not find the main game JAR in the official zip")
        sys.exit(1)

    os.remove(official_zip)
    shutil.rmtree(temp_win)

if __name__ == "__main__":
    package()
