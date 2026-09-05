# ModAnkh 可注入 Payload 開發規範

[English](modankh_payload_rules.md)

## 目的

ModAnkh 以及其 Debug Console、儲存等支援程式碼，不是一般的應用程式碼。它們必須同時在兩種環境下成立：

1. 作為 Shattered Master Mode 本體的一部分正常編譯與執行；
2. 從已編譯的 SMM donor APK/JAR 中抽出，再注入另一個 SPD 衍生版本後仍可正常運作。

因此，一個改動即使在 SMM 本體中可以編譯、甚至可以正常執行，也仍可能破壞 binary injection。所有可能進入 injectable payload 的 class，都應視為一個比一般遊戲程式碼具有更嚴格限制的小型相容性函式庫。

目前 `scripts/inject_apk.py` 與 `scripts/inject_jar.py` 的實作，是 payload 邊界與相容性檢查的最終依據。

## 1. Payload 邊界

Payload 應盡可能小，而且其邊界必須是刻意設計的。

目前重要的 payload family 包含：

- `com.spd.mod.items.ModAnkh`
- `com.spd.mod.items.ModAnkhStore` 及其 `$*` class
- `com.spd.mod.mechanics.ModDebug` 及其 `$*` class
- 明確支援的 helper，例如 `ModValueSearch`、`ModSaveTransfer`
- `com.spd.mod.mechanics.ModAssassinBuff`，以及 Debug Console `affect` 所需的 `ModAssassin`／`ModFlash` class family

不要假設只要某個 donor class 能從上述 class 觸及，就適合一起複製到 target。

### 規則

- 優先把 helper 保持在既有 payload family 內並使其 self-contained。
- 若必須新增 helper，應明確確認 APK 與 JAR injector 都會以預期方式包含它。
- Payload 不得依賴無關的 donor-only application class。
- 不得假設 donor 中經過混淆的 class name 在 target 中具有相同意義。
- 不得只是為了讓 compatibility check 通過，就無限制擴大 payload。

## 2. R8 / desugaring 安全規則

Binary donor 對最佳化後 bytecode 的形狀很敏感。R8/D8 可能重新命名、合併、outline，或共用原本分屬不同程式碼的 compiler-generated helper。若 injector 對 donor-only dependency 不加區別地一路追蹤，就可能把無關的 donor 程式碼一起拖進 payload，最後在 target compatibility validation 時爆炸。

典型症狀是出現一串 `ModDebug$Donor_*` relocated class，卻意外引用與 Debug payload 無關的 LibGDX member 或其他 donor-only API。

### 這次 fuzzy console 事件的重要經驗

擴大 fuzzy identifier 支援之後，編譯後的 bytecode 形狀改變，確實觸發了這個問題；但 **fuzzy 功能本身並沒有被證明是根因**。

尤其是：在 fuzzy 擴充前，一個能正常 inject 的 `ModDebug$Console` 版本本來就已經使用 `removeIf` 與 lambda，卻仍然可以正常工作。因此：

- 不能只因為新版出錯，就直接認定 lambda、method reference 或 Java 8 collection method 是根因；
- 這些寫法只能視為 R8/desugar 相關的風險訊號；
- 第一優先應比較「最後一個可運作 donor」與「失敗 donor」的完整 build pipeline 與 R8 規則是否真的一致。

### 優先選擇容易預測的 payload 寫法

若沒有明確理由使用較間接的語法，仍應優先選擇編譯後 dependency graph 較容易判讀的寫法，例如：

- 一般迴圈
- 明確的 `get` / `put` / `containsKey`
- 一般 helper method
- 需要 callback/comparator 時使用匿名 inner class

這是為了提高 binary donor 的可預測性，不代表 Java 8 語法本身不能使用。

## 3. Donor build 必須與 CI 保持一致

Donor APK 是否可用，不只取決於 source，也取決於它是否經過 injector 所假定的 payload-preservation build 規則。

Android donor release build 在編譯前必須執行 `patch_android.patch_proguard()`。這個 patch 會加入保護 ModAnkh / ModDebug payload 的 R8 `-keep` 規則。

### 這次已證實的失敗原因

本機 `scripts/build.py` 原本會呼叫：

- `patch_gradle()`
- `patch_play_games_version()`
- `patch_manifest()`

卻漏掉了 `patch_proguard()`。

相對地，CI workflow 是直接執行 `scripts/patch_android.py`，而該 script **確實會執行 `patch_proguard()`**。因此，本機 build 與 CI build 其實並不等價，儘管 `build.py` 的設計與註解宣稱流程相同。

在 fuzzy 擴充以前，即使缺少這些 keep rules，本機 donor 的 bytecode 形狀仍剛好能被 injector 正常處理；加入 fuzzy 後，程式結構改變，使 R8 產生不同的最佳化結果，injector 才開始追到帶有無關 LibGDX reference 的 `ModDebug$Donor_*`。

把遺漏的 `patch_proguard()` 補回本機 build 後，重新建置 donor，再執行 injection，即可成功運作。

### 規則

凡是宣稱「本機 build 與 CI 等價」的 script，都必須實際逐項確認 patch sequence，不得只相信註解或設計意圖。

Android donor build 的實際 pre-build 流程必須包含 payload ProGuard patch。

## 4. Target API 相容性

不要假設所有 SPD fork 都與目前 SMM donor 暴露完全相同的 source API。

### 規則

- 將 target API 差異視為正常情況。
- 只有在 API 對支援的 target 足夠穩定時才直接呼叫。
- 對已知且可安全對應的 API 差異，優先在 injector adapter 層處理。
- 對 optional 或容易變動的 API，適合時優先使用 reflection / capability probing。
- Reflection 必須保持聚焦，不要因此在 compile time 拉進大型而無關的 type graph。
- Compatibility validator 若回報真正缺少 executable reference，應修 payload 或增加明確 adapter；不得只是把 validator 放寬。

例如：若 target 沒有 `Item.setCurrent(Hero)`，但仍有舊版的 `curUser` / `curItem` field，injector 可以做明確的相容轉換。

## 5. Debug Console 一致性

Debug Console 的行為不應因指令從哪條路徑進入 executor 而不同。

下列路徑在適用時應保持一致：

- 直接互動式指令
- macro
- `@handle` 結果前綴
- `give`、`spawn`、`affect` 等指令的 optional method
- reflection-based `get` / `set`
- `use`

### Fuzzy identifier 規則

凡是使用者合理上需要「記住名稱」的 identifier，可以使用 fuzzy resolution：

- class name
- field name
- method name
- `Class` 型別的 method argument
- 作為具名值使用的 enum value

比對順序：

1. exact
2. unique prefix
3. unique substring
4. compact ordered subsequence
5. 若最佳結果仍同分有歧義：顯示 `Similar:`，不得猜測

Exact match 永遠優先。尤其當某個 method 名稱本身精確存在、但參數不合法時，不得偷偷跳去另一個 fuzzy-matched method。

#### Ordered fuzzy 比對

Fuzzy 層比單純的 subsequence 搜尋更嚴格：

- query 的字母必須以相同順序出現在候選名稱中；
- 少於 3 個字元的 query 不進行 subsequence fuzzy；
- matched span 內總共跳過的字元數不得超過 `max(4, query 長度)`；
- matched span 長度不得超過 query 長度的 2 倍；
- 分數越低越好，其中跳過字元（gap）的懲罰最大；
- 起始位置越前、CamelCase／word boundary 命中越多、候選名稱越短，會得到較好的次要排序。

對必須實際選出一個 identifier 的操作（class、field、method、enum），只有 fuzzy score 最佳且同分的候選會留下。若最佳分數只有一個候選，可以自動解析；若最佳分數同分，仍視為歧義並列出 `Similar:`。`inspect` 可以顯示多個合格 fuzzy 結果，但會依 match quality 排序。

例如 `goem` → `Golem`、`potheal` → `PotionOfHealing`、`ringenergy` → `RingOfEnergy`、`atk` → `attack` 應保持可用；若 query 的字母只是零散分布在一個很長、無關的 identifier 中，會因 gap/span 門檻被排除。

#### Class name 搜尋範圍

未限定 package 的 class query，fuzzy **只能比對 simple class name**；package path 不得提供字母來湊 fuzzy match。

只有當 query 本身明確包含 `.` 或 `$` 時，才視為 qualified query，允許完整 qualified/binary class name 參與 fuzzy 比對。

因此 `etchain` 不應再因為 `com.shatteredpixel...items.scrolls.ScrollOfRecharging` 的 package path 可以湊出字母，就把 `ScrollOfRecharging` 列為 Similar。普通 `etchain` 查詢只會拿 `ScrollOfRecharging` 本身來比，而它不符合該 fuzzy match。

以下仍刻意保持 exact：

- command verb
- `@handle` 名稱
- 數字、cell、quantity、duration
- 一般字串參數

## 6. APK injector 不變量

除非未來有明確的架構決策改變，應維持以下性質：

- target APK 仍是基底 artifact
- target package identity 維持不變
- 原始 target DEX 內容 byte-for-byte 保留，只向後位移到 overlay DEX 之後
- overlay 只包含 patched/injected payload
- target resources 除 injector 已明確負責的 manifest 修改外，不應任意重建或改寫
- 打包前必須先驗證 target API compatibility
- unresolved/self-containment error 必須中止 injection

不得只是為了解決 payload 問題，就重建或大量修改整個 target APK，除非這成為明確的新架構決策。

## 7. JAR injector 不變量

Desktop JAR injector 也遵循相同的 isolation 原則：

- target JAR 是基底
- 只複製明確選定的 ModAnkh / debug payload
- 在已知 anchor 修改 `Dungeon.init()`
- 對相容的 API 差異可進行明確 adapter
- 其他 target entry 應保持不變
- repack 所需時可以移除失效的 signature/index metadata

只要 payload structure 有變，就必須同時考慮 APK 與 JAR injector；APK 成功不代表 JAR 一定安全。

## 8. 驗證要求

在認定 payload 改動完成之前：

1. 編譯變更過的 Java source。
2. 確認 donor build path 與 CI 套用了同一套 payload-preservation patch；Android release 特別要確認 `patch_proguard()`。
3. 除了確認 repository 裡存在 `patch_proguard()`，還要確認實際使用的 build path **真的呼叫到了它**，必要時直接檢查最終 ProGuard rules。
4. 重新 build 一份新的 SMM donor artifact；舊 donor 仍然包含舊的 R8 輸出。
5. 至少對一個具代表性的 target 執行對應 injector。
6. 確認沒有出現非預期的 `ModDebug$Donor_*` 或其他 relocated helper。
7. 若真的出現 relocated helper，先追查它為何進入 dependency closure，不要先改 allowlist 或放寬 validator。
8. Compatibility validation 必須在沒有壓掉真實錯誤的情況下通過。
9. 能做到時，應在 injected build 中實際操作受影響的功能。

對 binary-injection 相關變更而言，只做 source compile 是必要條件，但不是充分條件。

## 9. 失敗診斷

Injection 失敗時，先分類問題，再修改程式。

### `missing target method/field/type`

先判斷該 reference 是：

- 合法的 target API 差異，需要 adapter；
- 不穩定 API，應改成 reflection/probing；或
- 根本不應進入 payload 的 donor dependency。

### `ModDebug$Donor_*` 出現大量無關 LibGDX/API error

**不要先怪罪最後一個 source 改動。**

應依序檢查：

1. donor 是否確實由目前 source 重新 build？
2. 實際使用的 build path 是否執行 `patch_proguard()`？
3. 最終 Android ProGuard 設定是否真的包含預期的 payload `-keep` 規則？
4. local build 與 CI build 是否有任何流程差異？
5. 上述都正確後，才檢查最近 source 是否新增 R8/desugar-sensitive 寫法或新的 dependency edge。

這次 fuzzy console 事件證明：一個 source 改動有時只是把早已存在的 build-pipeline bug 暴露出來。

### `donor-only dependency ... is not present`

表示 payload 引用了 closure 無法安全攜帶的 donor class。應移除該 dependency、明確重新設計 payload 邊界，或讓 dependency 真正 self-contained。

不得以關掉 self-containment check 的方式處理這些問題。

## 10. Repository 寫入剛性規則

此 repository 有以下剛性規定：

> 任何 repository 變更造成的公開可見 attribution，都只能出現 `edward9s`。

這包含 commit author/committer，以及使用的工作流程可能產生的公開 contributor/activity identity。

因此：

- 除非使用者明確要求，不得建立 branch
- 除非使用者明確要求，不得開 PR
- 不得建立臨時／測試 ref、no-op commit 或 throwaway workflow
- 不得使用 GitHub Actions/bot 進行 commit 或 push
- 不得使用其他帳號或 GitHub App identity 寫入 repository
- 不得加入其他 identity 的 `Co-authored-by`
- 只能使用能確認最終 author 與 committer 都是 `edward9s` 的寫入路徑

Force-push 或 history rewrite 本身並不被禁止。真正的硬規則是公開 identity，以及避免不必要的公開 repository 紀錄。

## 11. 設計原則

當「較聰明、較簡短的寫法」與「較明確、編譯後 dependency graph 及 build requirements 更容易稽核的寫法」之間需要取捨時，injectable payload 應優先選後者。

對一般 SMM 程式碼而言，source-level correctness 往往足夠；但對 ModAnkh injectable payload 而言，編譯後 APK/JAR 的 dependency graph **以及 donor build pipeline 本身**，都是相容性契約的一部分。
