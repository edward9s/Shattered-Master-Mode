# Shattered Master Mode Binary Injection 開發規範

[English](smm_injection_rules.md)

## 範圍

Binary injection 會把已編譯的 SMM `com.spd.mod.*` payload 注入相容的 SPD 衍生 APK 或 desktop JAR。

公開入口：

- `scripts/inject_apk.py`
- `scripts/inject_jar.py`

必要的內部模組：

- `scripts/_inject_apk_core.py`
- `scripts/_inject_jar_core.py`

## Injection Kit

公開 artifact 以 SMM 版本命名：

```text
SMM-m<version>-InjectKit.zip
```

內容：

- `smm-inject-donor.apk`
- `smm-inject-donor.jar`
- `inject_apk.py`
- `_inject_apk_core.py`
- `inject_jar.py`
- `_inject_jar_core.py`
- `README.txt`

使用方式：

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

Injector 會從自身所在目錄取得對應 donor。預設輸出為 `<target>-SMM.apk` 與 `<target>-SMM.jar`；可用 `--out` 指定其他路徑。

## Donor

APK donor 使用 Injection Kit workflow 產生的專用 non-minified build。R8/minification 可能產生 donor-only 的混淆 dependency，不適合直接移植到 target。

JAR donor 使用 desktop release 輸出。

Donor 編譯時使用的 SPD source 版本只是 build baseline；InjectKit 的版本以 SMM 版本為準。

## Payload 與 target 處理

- Injectable payload 是已編譯的 `com.spd.mod.*`。
- Target APK/JAR 始終是 base artifact。
- 需要時將 SPD package reference rebase 到 target fork package。
- `com.spd.mod.*` 名稱保持不變。
- Patch target `WndGame` constructor，呼叫 `com.spd.mod.ModGame.installInjectedMenu(Object)`。
- Payload self-containment 或 target compatibility 無法解決時停止 injection。

### APK

- 保留 target package identity。
- 原始 target DEX byte-for-byte 保留，並移到 injected overlay DEX 之後。
- 除 injector 明確處理的 manifest 修改外，保留 target resource。
- 最終 APK 重新 build 並簽名。

### JAR

- 從 donor JAR 移植完整 `com.spd.mod.*` class。
- 保留無關 target entry。
- Repack 時可移除已失效的 signature / index metadata。

## 相容性規則

- 將 SPD fork 間的 binary API 差異視為正常情況。
- 比較 compiled descriptor，不只看 Java source signature。
- 優先使用跨支援 target 穩定的 API。
- Fork-sensitive 或 minifier-sensitive API 使用明確 adapter，或不依賴 member name 的 reflection / capability check。
- 不得為了繞過 compatibility error 而複製任意 donor-only 或 obfuscated class。
- 不得放寬 validation 來忽略真正缺少的 executable reference。

## 驗證

會影響 injection 的改動會使用最新 source 重新編譯 donor，並從實際打包後的 Injection Kit layout 執行測試。

目前 CI 驗證：

- 官方 Shattered Pixel Dungeon 3.3.8 APK/JAR
- 官方 Shattered Pixel Dungeon 4.0 beta APK
- Rat King Adventure 2.3.3 APK/JAR

靜態 packaging 無法涵蓋的行為仍需要 runtime 測試。
