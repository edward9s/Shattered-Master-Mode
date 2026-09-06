# Shattered Master Mode Binary Injection 開發規範

[English](smm_injection_rules.md)

## 目的

Shattered Master Mode（SMM）必須同時在兩種環境下成立：

1. 作為正常從 source 編譯的 SMM 遊戲；
2. 把已編譯的 SMM payload 移植進另一個相容的 SPD 衍生 APK 或 desktop JAR。

Binary injection 比一般 source integration 更嚴格。一個改動即使能在 SMM 本體正常編譯與執行，仍可能在 package rebase、target API adaptation、DEX/JAR overlay，或另一個 SPD fork 的 runtime 上失敗。

目前公開的 injector 入口只有：

- `scripts/inject_apk.py`
- `scripts/inject_jar.py`

`_inject_apk_core.py` 與 `_inject_jar_core.py` 是內部 implementation module，不是另一套公開 injection mode。

## 1. Full-SMM payload 邊界

完整注入以編譯後的 `com.spd.mod.*` class 作為 SMM payload。

`com.spd.mod` 內部彼此引用是正常的，因為整個 SMM payload 會一起移植。但這不代表 donor 裡所有可觸及的 dependency 都能安全搬進 target。

規則：

- `com.spd.mod.*` 可以依賴其他 `com.spd.mod.*` class。
- 不要讓 SMM payload 依賴無關的 donor-only application class。
- 不得假設 donor 中的 obfuscated class name 在 target 有相同意義。
- 不得只是為了消除 compatibility error，就把任意 donor dependency closure 一起搬進 target。
- 優先使用跨 fork 較穩定的 SPD API。
- 對 optional 或 fork-sensitive API，適合時使用明確 adapter，或聚焦的 reflection / capability probing。

Injector 會依 target class 結構偵測 SPD-family package root。Donor 中原本編譯成 `com.shatteredpixel.shatteredpixeldungeon` 的引用，在需要時會 rebase 到 target fork 的 package；`com.spd.mod.*` 本身不會改名。

## 2. Injection Kit 與 donor artifact

公開的 Injection Kit 以 **SMM 版本**命名：

```text
SMM-m<version>-InjectKit.zip
```

例如：

```text
SMM-m0.3.0-InjectKit.zip
```

用哪個 SPD 版本編譯 donor，只是 donor build baseline，不是 InjectKit 的公開版本。是否能注入某個 target，應由實際 target compatibility 決定，而不是看 kit 名稱是否和 target SPD 版本一致。

Kit 內包含兩個專用 donor：

- `smm-inject-donor.apk`
- `smm-inject-donor.jar`

以及兩組 injector：

- `inject_apk.py`
- `_inject_apk_core.py`
- `inject_jar.py`
- `_inject_jar_core.py`

## 3. APK donor 必須是 non-minified

APK donor 必須使用 Injection Kit workflow 產生的 **non-minified debug APK**。

不能拿一般 release APK 代替。R8/minification 可能 rename、merge、outline，或把 executable code rebind 到 donor-only obfuscated helper。結果可能是某個 `com.spd.mod` class 在 bytecode 裡依賴短名混淆 class，而那些 class 在 target 中不存在，或代表完全不同的東西。

因此 full SMM APK injection 刻意使用 `android:assembleDebug` 作為 donor source。

正常給玩家安裝的 SMM APK 仍然是 release/minified build；debug donor 只用來做 binary transplantation。

## 4. 公開使用方式

Injection Kit 裡只應使用：

```bash
python inject_apk.py smm-inject-donor.apk TARGET.apk --out TARGET-SMM.apk
python inject_jar.py smm-inject-donor.jar TARGET.jar --out TARGET-SMM.jar
```

現在已不存在分開的 `inject_smm_apk.py` 或 `inject_smm_jar.py` 公開模式。`inject_apk.py` 與 `inject_jar.py` 預設就是 full-SMM injection。

Private core 檔必須和公開 script 放在一起，因為 public entry point 會 import 它們。

## 5. 遊戲入口整合

Full binary injection 使用正常的 SMM game-menu integration，不再靠開局自動給 Hero 一個 ModAnkh。

APK 與 JAR injector 都會 patch target 的 `WndGame` 無參數 constructor，插入：

```text
com.spd.mod.ModGame.installInjectedMenu(Object)
```

`installInjectedMenu` 依 method signature 尋找 target 裡接收單一 `RedButton` 的 insertion method，而不是依賴 private method 名稱。這是刻意設計，因為 R8 可能改掉 private method name。

舊的 `Dungeon.init()` / startup-ModAnkh hook 已不是 full-SMM 的入口。

## 6. Target API 相容性

不要假設所有 SPD-derived fork 都有完全相同的 binary API。

規則：

- 要看 compiled descriptor，不只看 Java source signature。
- 不同 fork 的 API 差異應視為正常 compatibility work。
- 優先使用已知跨 target 穩定的 API。
- 對 optional 或容易變動的 API，若 direct dependency 會降低相容性，優先使用 reflection / capability check。
- Injector 若回報真正缺少 executable method、field 或 type，應修 payload 或增加明確 adapter，不得只是放寬 validator。

目前已遇過的差異包括 `Char.buff(Class)`、optional enum constant、`LevelTransition.Type`、Sheep initialization，以及較舊版 `Item.setCurrent(Hero)` 行為。

## 7. APK injector invariants

除非刻意改架構，應維持：

- target APK 始終是 base artifact；
- target package identity 不變；
- 原始 target DEX byte-for-byte 保留，並往後移讓 injected overlay DEX 位於前方；
- overlay 只承載 injected / patched SMM payload；
- 除 injector 明確擁有的 manifest 修改外，不隨意改 target resource；
- self-containment 或 compatibility 無法解決時直接停止 injection；
- 最終 APK 只做必要 rebuild/sign，不從 source 重編整個 target game。

不要用「廣泛重建 target APK」來繞過 payload compatibility 問題。

## 8. JAR injector invariants

Desktop JAR injector 同樣採 target-as-base 原則：

- target JAR 保持 base；
- donor JAR 的完整 `com.spd.mod` payload 會被移植；
- 需要時 rebase target package reference；
- patch target `WndGame`；
- 已知 API 差異使用明確 compatibility adaptation；
- 無關 target entry 應保留；
- repack 時可移除失效的 signature / index metadata。

JAR 路徑目前沿用成熟的 legacy bytecode adaptation/validation machinery 處理已知 compatibility case，同時由 representative-target CI 提供整合驗證。某一個 target 成功，不代表所有未知 fork 都必然相容。

## 9. 驗證要求

任何會影響 injection 的改動完成前，至少應：

1. 從目前 SMM source 重新編譯 donor。
2. APK injection 必須確認 donor 是 non-minified debug APK，而不是 release APK。
3. 驗證實際 Injection Kit 打包後的 layout，不只直接跑 repo 裡的 script。
4. 確認 payload dependency 沒有逃到無關 donor-only class。
5. 共用程式碼有變動時，同時測 APK 與 JAR。
6. 實務可行時，至少測一個官方 SPD 與一個差異較大的 SPD fork。
7. 不得壓掉真正的 compatibility failure。
8. 實務可行時，在 injected build 中實際走過受影響的 runtime feature。

目前 CI representative set 包含官方 Shattered Pixel Dungeon 與 Rat King Adventure，APK/JAR 兩條路徑都會測。

## 10. Failure diagnosis

### 缺少 target method / field / type

先判斷它是：

- 合理的 target API 差異，需要 adapter；
- 不穩定 API，應改 reflection / capability probing；
- 或本來就不該存在的 accidental donor dependency。

### Donor-only obfuscated dependency

如果 APK payload 突然引用短名 obfuscated owner 或無關 donor class，第一件事先確認是否錯用了 release donor。不要為了讓 closure 通過就把任意混淆 donor class 一起複製。

### Injection 成功但 runtime 壞掉

Packaging success 不代表 runtime 一定安全。應檢查真正執行到的 path 是否依賴 fork-sensitive descriptor、field、enum constant、reflection assumption 或 target UI structure。

## 11. Repository write 規則

Repository 修改必須遵守專案的 public attribution 規則：公開可見的 repository attribution 只能顯示 `edward9s`。

除非使用者明確要求，不建立 branch、PR、throwaway ref、bot commit 或其他公開作者身分。

## 12. 設計原則

在聰明但難稽核的寫法，與稍微明確但 dependency graph 容易理解的寫法之間，優先選後者。

一般 SMM source code 可能只要 source-level correctness 就夠；但 full binary injection 的 compatibility contract 同時包含 compiled APK/JAR 形狀、donor build mode、target ABI 與 injection pipeline。
