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

`--ankh-only` 保證的核心只有：

- `ModAnkh`
- `ModLastStand`
- Store / Loot / Debug Console 所需相依

另外會盡力加入兩個選配戰鬥 Buff：

- `ModInstantKill`：只要 payload 本身與 target API 相容就保留。優先安裝 pre-defense `Char.attack()` hook；若無法安全辨識該 hook，仍保留 Buff，改用既有的 `attackProc()` fallback。
- `ModForceHit`：從 terminal `Char.attack()` 結構追蹤唯一的 static boolean 命中判定方法；其前兩個參數必須是 attacker / defender `Char`，且回傳值必須直接控制命中分支。即使方法改名或多出其他參數，只要能唯一安全辨識就照樣 patch；只有無法唯一確定命中判定時才跳過 Force Hit。

兩者都可透過 Debug Console 的 `affect ModInstantKill` / `affect ModForceHit` 套用。最小注入仍不安裝完整 SMM 選單、Assassinate 或 Riposte。

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
- `--ankh-only` 必須保持精簡；ModAnkh + ModLastStand 與 Store / Loot / Debug Console 是保證核心，Instant Kill 與 Force Hit 是選配，不能因選配失敗拖垮核心注入。
- 「選配」不代表消極放棄。Injector 必須先嘗試安全的結構式適配再決定跳過。Instant Kill 可退回 `attackProc()`；Force Hit 必須從 terminal attack 結構追蹤命中判定，而不是只接受精確的 vanilla `Char.hit(Char, Char, float, boolean)` signature。
- 完整注入可以使用既有 SMM 選單與 Riposte `Char.attack()` hook。最小注入不得安裝 Riposte 或完整選單，但可以安裝上述狹窄用途的 Instant Kill / Force Hit hook。
- Debug Console 指令可能觸發 target 本身既有的 bug；不要為了讓指令表面成功而順便修改無關的 target 遊戲邏輯。

## 目前命名

刺客 Buff class 已改名為 `ModAssassinate`。舊的 `ModAssassinBuff` 已移除，不保留相容 alias。

## 驗證

代表性 APK/JAR 測試只能證明該 target 的靜態注入通過；必要核心 ABI 無法安全適配時應 fail closed。選配功能則應先嘗試安全的結構式適配，以及既有且明確的 fallback；兩者都不可行時，只跳過該選配功能。
