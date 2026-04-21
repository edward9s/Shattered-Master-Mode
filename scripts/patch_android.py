import re
import sys

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
    patch_manifest('spd_src/android/src/main/AndroidManifest.xml')
