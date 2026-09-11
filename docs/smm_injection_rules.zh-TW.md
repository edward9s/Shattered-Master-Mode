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

`--ankh-only` 只包含：

- `ModAnkh`
- Store / Loot / Debug Console 所需相依

不加入 `ModLastStand`，也不安裝完整 SMM 選單或其他無關戰鬥功能。

## Injection Kit

Artifact 名稱為 `SMM-m<version>-InjectKit.zip`。Injector script 與 donor APK/JAR 必須放在一起。

只要注入 payload 內的 Java class 有變更，就要重新 build Injection Kit；只修改 injector Python 則不需要重建 donor。

## 相容性規則

- 以 target 實際編譯後的 API 為準，不以版本號推測相容性。
- Fork package name 不同時，重新對應 SPD package reference。
- ABI dependency 無法可靠解析時直接停止，不猜測、不硬塞。
- 不複製任意 donor-only 或混淆 class 來掩蓋 compatibility error。
- `--ankh-only` 必須保持精簡；用途就是讓老 fork 能使用 ModAnkh 的 Store / Loot / Debug Console。
- 完整注入可以使用既有 SMM 選單與 Riposte hook；最小注入不得安裝無關的 full-SMM hook。
- Debug Console 指令可能觸發 target 本身既有的 bug；不要為了讓指令表面成功而順便修改無關的 target 遊戲邏輯。

## 目前命名

刺客 Buff class 已改名為 `ModAssassinate`。舊的 `ModAssassinBuff` 已移除，不保留相容 alias。

## 驗證

代表性 APK/JAR 測試只能證明該 target 的靜態注入通過；若某個必要 ABI 無法安全適配，應 fail closed。
