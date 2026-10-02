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
- `ModLastStand`，包含 `ModLastStandTag` / runtime tag layout
- Store / Loot / Debug Console 所需相依

另外會盡力加入五個選配 Buff：

- `ModParryRiposte`：完整 dependency closure 相容時才加入。Riposte 使用唯一 terminal `Char.attack()` 的 entry/completion hook；Parry 則和 Force Hit 共用 selected `Char.hit(...)` helper，直接讓該次 hit check miss；Parry 成功後，所有 `Char` 的 `defenseVerb()` 顯示 call site 會統一經過 `ModParryRiposte` feedback bridge，使用武僧相同的 `parried` 文字與 `HIT_PARRY` 音效，非 SMM Parry 則保持 target 原本的 virtual `defenseVerb()` 行為。Force Hit 先判定，因此會覆蓋 Parry。Buff 本身只繼承一般 `Buff`，不依賴 Champion API。若 target 提供原生 Monk `FocusBuff`，則以 runtime 探測方式掛上 exact Focus，保留原版「無限閃避優先於無限命中」的必中 Parry 語意；舊 fork 沒有 Focus 時則只使用 selected `Char.hit(...)` hook。Direct `Char.hit(...)` 特殊攻擊也會直接觸發 Riposte，不要求經過 `Char.attack()`。
- `ModInstantKill`：只依賴唯一的 terminal `Char.attack()` overload。Force Hit + Instant Kill 在 attack 入口直接結算；否則只有原生 terminal attack 本來就要 `return true` 時才執行 Instant Kill，因此原生 defense side effect 會先跑完。不再提供 `attackProc()` fallback，也不再分析 hit-success branch。
- `ModForceHit` 才擁有 selected hit-check capability；`ModInstantKill` 不再依賴它。已知 direct ABI 依方法是否存在且可安全 patch 來採用：先 `Char.hit(Char, Char, float, boolean)`，再舊版 `Char.hit(Char, Char, boolean)`。只有兩種 direct ABI 都不存在時，才從 terminal `Char.attack()` structural trace 唯一可辨識的 static boolean helper。
- `ModAssassinate`：完整 runtime UI / combat dependency closure 相容時才加入。側邊 Tag 與 map long-press layer 都在 runtime 自行掛載，不需要額外 patch `GameScene` 或 `Char`。
- `ModEnemySurge`：gameplay + 設定視窗 dependency closure 相容時才加入。不需要額外 patch 敵人生成流程；Buff 直接使用 target 既有的 `Level.mobLimit()`、`mobCount()`、`respawnCooldown()`、`spawnMob(...)`。Source build 的 richer overlay 維持 optional，不納入 minimal payload。

五者都可透過 Debug Console 的 `affect ModParryRiposte` / `affect ModInstantKill` / `affect ModForceHit` / `affect ModAssassinate` / `affect ModEnemySurge` 套用。最小注入仍不安裝完整 SMM 選單。

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
- `--ankh-only` 必須保持精簡；ModAnkh + ModLastStand 與 Store / Loot / Debug Console 是保證核心，Parry/Riposte、Instant Kill、Force Hit、Assassinate 與 Enemy Surge 是選配，不能因選配失敗拖垮核心注入。
- 「選配」不代表消極放棄。APK/JAR 的 Riposte 與 Instant Kill 共用唯一 terminal attack 規則；Parry 與 Force Hit 共用 direct-first、structural-fallback 的 selected hit-hook 規則。Parry/Riposte 同時需要安全的 terminal attack 與 selected hit-check；Instant Kill 只需要 terminal attack；Force Hit 只需要 selected hit-check。
- `--ankh-only` 會把 Parry/Riposte、Instant Kill、Force Hit、Assassinate 與 Enemy Surge（包含各自的設定 UI）視為完整的選配 dependency closure 驗證。若某功能的 UI 或 target API 相依不相容，就在 patch `BuffIndicator` 前跳過整個選配功能；不得讓 click bridge 引用已被省略的 payload class。
- Mod 系列 action 文字維持直接由程式碼提供。APK/JAR 若遇到舊版 `WndUseItem` 繞過 `Item.actionName()`、直接呼叫 `Messages.get(...)`，應只對該 legacy call site 做 ABI bridge，讓 `ac_*` 重新走 target 已存在的虛擬 `Item.actionName(action, hero)`；不要為 ModAnkh 修改 `items*.properties`。
- 可設定的 SMM buff 在 source build、APK injection、JAR injection 都共用直接 `BuffIndicator` bridge：短按呼叫該 buff 自己的 `open()` / `openInfo()`，長按保留 target 原本的 buff info 行為。Full SMM 處理 Last Stand、Parry/Riposte、Instant Kill、Force Hit、Assassinate、Enemy Surge；`--ankh-only` 固定處理 Last Stand，並只加入通過相容性檢查的 Parry/Riposte / Instant Kill / Force Hit / Assassinate / Enemy Surge。`ModTotalInfoOverlay` 已移除；`ModLastStandTag` 屬於 narrow Last Stand 的保證核心。
- 完整注入可以使用既有 SMM 選單與 Riposte `Char.attack()` hook。APK/JAR 都選出唯一 terminal `Char.attack()`。Instant Kill patch 成功的 boolean return，並在 attack 入口加上 Force Hit 組合 guard；不再追蹤 hit-success branch。Parry 與 Force Hit 共用 selected hit helper，而且 Force Hit 先判定。Riposte 改走最穩定的 `attack(Char)` wrapper，仍維持普通攻擊命中規則。最小注入不得安裝完整選單，但可以安裝上述狹窄用途的 Parry/Riposte / Instant Kill / Force Hit hook。
- 存檔匯入匯出不得硬連結會隨 SPD 世代變動的 desktop 檔案 API；`FileUtils.getFileHandle(...)`、舊式 `FileUtils.getDir(...)` 與 backing `File` 應在 runtime 解析，避免 APK 根本不會執行的 desktop 路徑先讓 payload compatibility validation 失敗。
- Debug Console 指令可能觸發 target 本身既有的 bug；不要為了讓指令表面成功而順便修改無關的 target 遊戲邏輯。

## 目前命名

刺客 Buff class 已改名為 `ModAssassinate`。舊的 `ModAssassinBuff` 已移除，不保留相容 alias。

## 驗證

代表性 APK/JAR 測試只能證明該 target 的靜態注入通過；必要核心 ABI 無法安全適配時應 fail closed。選配功能則應先嘗試安全的結構式適配，以及既有且明確的 fallback；兩者都不可行時，只跳過該選配功能。
