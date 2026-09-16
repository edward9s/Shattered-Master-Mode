# Shattered Master Mode Binary Injection 開發規範

[English](smm_injection_rules.md)

## 模式

Injection Kit 用於已編譯完成的 SPD 衍生 APK 或 JAR。

完整 SMM：

```bash
python inject_apk.py TARGET.apk
python inject_jar.py TARGET.jar
```

針對老舊或高度修改 fork 的最小注入：

```bash
python inject_apk.py TARGET.apk --ankh-only
python inject_jar.py TARGET.jar --ankh-only
```

`--ankh-only` 包含：

- `ModAnkh`
- `ModLastStand`
- Store / Loot / Debug Console 所需相依

不安裝完整 SMM 選單，也不加入其他無關戰鬥功能。

## Injection Kit

Artifact 名稱為 `SMM-m<version>-InjectKit.zip`。Injector script 與 donor APK/JAR 必須放在一起。

只要注入 payload 內的 Java class 有變更，就要重新 build Injection Kit；只修改 injector Python 則不需要重建 donor。

## APK 簽章金鑰

APK injector 第一次需要簽章時，會在 `smm-inject-donor.apk` 旁建立 `smm-inject.keystore`，之後的 APK 注入會持續重用同一個 keystore。

如果希望之後注入的新 APK 能直接更新手機上已安裝、且 package name 相同的舊注入 APK，請保留這個檔案。若刪除 keystore 或改用另一把金鑰，Android package 簽章就會改變，通常必須先解除安裝舊 APK，才能安裝新簽章的 APK。

升級到新版本並重新解壓 Injection Kit 時，如果需要維持簽章連續性，請先把原本的 `smm-inject.keystore` 複製到新的 kit 目錄，再執行 `inject_apk.py`。這個 keystore 只應保留在本機，不要 commit 到 repository，也不要打包進公開 artifact。

JAR 注入不使用這把 APK 簽章金鑰。

## 相容性規則

- 以 target 實際編譯後的 API 為準，不以版本號推測相容性。
- Fork package name 不同時，重新對應 SPD package reference。
- ABI dependency 無法可靠解析時直接停止，不猜測、不硬塞。
- 不複製任意 donor-only 或混淆 class 來掩蓋 compatibility error。
- `--ankh-only` 必須保持精簡；用途就是讓老 fork 能使用 ModAnkh、ModLastStand，以及 Store / Loot / Debug Console 工具。
- 完整注入可以使用既有 SMM 選單與 Riposte hook；最小注入不得安裝無關的 full-SMM hook。
- Debug Console 指令可能觸發 target 本身既有的 bug；不要為了讓指令表面成功而順便修改無關的 target 遊戲邏輯。

## 目前命名

刺客 Buff class 已改名為 `ModAssassinate`。舊的 `ModAssassinBuff` 已移除，不保留相容 alias。

## 驗證

代表性 APK/JAR 測試只能證明該 target 的靜態注入通過；若某個必要 ABI 無法安全適配，應 fail closed。
