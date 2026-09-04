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
- JAR injector 明確指定的 helper payload，例如 `ModValueSearch`、`ModSaveTransfer`

不要假設只要某個 class 能從上述 class 觸及，就適合一起複製到 target。

### 規則

- 優先把 helper 保持在既有 payload family 內並使其 self-contained。
- 若必須新增 helper，應明確確認 APK 與 JAR injector 都會以預期方式包含它。
- Payload 不得依賴無關的 donor-only application class。
- 不得假設 donor 中經過混淆的 class name 在 target 中具有相同意義。
- 不得只是為了讓 compatibility check 通過，就無限制擴大 payload。

## 2. R8 / desugaring 安全規則

Binary donor 對 compiler-generated synthetic class 很敏感。R8/D8 可能把 synthetic helper 合併或共用到原本毫不相關的程式碼中。如果 injector 將這類 helper 判定成 donor-only dependency 並一路收進 closure，就可能把無關的 donor 程式碼一起拖進 Debug payload。

我們已經實際遇過 `ModDebug$Donor_*` 意外引用 donor 專屬 LibGDX member，以及其他無關 SMM 程式碼的情況。

### Injectable payload 中應避免

若能以簡單明確的寫法取代，應避免容易產生 shared desugar helper 的結構，尤其是：

- lambda（`x -> ...`）
- method reference（`Type::method`）
- `Collection.removeIf(...)`
- `Map.computeIfAbsent(...)`
- 其他 Java 8 collection/default-interface convenience method

### 優先使用

- 一般迴圈
- 明確的 `get` / `put` / `containsKey`
- 一般 helper method
- 需要 callback/comparator 時使用匿名 inner class

只要 anonymous inner class 明確留在 payload family 內，例如 `ModDebug$Console$1`，這種形式是可接受的。

這些限制的原因是 binary donor isolation，而不是 Java 語言本身不支援相關功能。

## 3. Target API 相容性

不要假設所有 SPD fork 都與目前 SMM donor 暴露完全相同的 source API。

### 規則

- 將 target API 差異視為正常情況。
- 只有在 API 對支援的 target 足夠穩定時才直接呼叫。
- 對已知且可安全對應的 API 差異，優先在 injector adapter 層處理，而不是複製大量 target-specific 程式碼。
- 對 optional 或容易變動的 API，適合時優先使用 reflection / capability probing。
- Reflection 必須保持聚焦，不要因此在 compile time 拉進大型而無關的 type graph。
- Compatibility validator 若回報真正缺少 executable reference，應修 payload 或增加明確 adapter；不得只是把 validator 放寬。

例如：若 target 沒有 `Item.setCurrent(Hero)`，但仍有舊版的 `curUser` / `curItem` field，injector 可以做明確的相容轉換。

## 4. Debug Console 一致性

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
4. unique fuzzy subsequence
5. 若有歧義：顯示 `Similar:`，不得猜測

Exact match 永遠優先。尤其當某個 method 名稱本身精確存在、但參數不合法時，不得偷偷跳去另一個 fuzzy-matched method。

以下仍刻意保持 exact：

- command verb
- `@handle` 名稱
- 數字、cell、quantity、duration
- 一般字串參數

## 5. APK injector 不變量

除非未來有明確的架構決策改變，應維持以下性質：

- target APK 仍是基底 artifact
- target package identity 維持不變
- 原始 target DEX 內容 byte-for-byte 保留，只向後位移到 overlay DEX 之後
- overlay 只包含 patched/injected payload
- target resources 除 injector 已明確負責的 manifest 修改外，不應任意重建或改寫
- 打包前必須先驗證 target API compatibility
- unresolved/self-containment error 必須中止 injection

不得只是為了解決 payload 問題，就重建或大量修改整個 target APK，除非這成為明確的新架構決策。

## 6. JAR injector 不變量

Desktop JAR injector 也遵循相同的 isolation 原則：

- target JAR 是基底
- 只複製明確選定的 ModAnkh / debug payload
- 在已知 anchor 修改 `Dungeon.init()`
- 對相容的 API 差異可進行明確 adapter
- 其他 target entry 應保持不變
- repack 所需時可以移除失效的 signature/index metadata

只要 payload structure 有變，就必須同時考慮 APK 與 JAR injector；APK 成功不代表 JAR 一定安全。

## 7. 驗證要求

在認定 payload 改動完成之前：

1. 編譯變更過的 Java source。
2. 視情況掃描 injectable payload source，確認沒有意外加入 lambda、method reference 或會造成 shared desugar helper 的 default-method 用法。
3. 重新 build 一份新的 SMM donor artifact；舊 donor 仍然包含舊的 R8 輸出。
4. 至少對一個具代表性的 target 執行對應 injector。
5. 確認沒有出現非預期的 `ModDebug$Donor_*` 或其他 relocated helper。
6. 若真的出現 relocated helper，先追查它為何進入 dependency closure，不要直接 whitelist。
7. Compatibility validation 必須在沒有壓掉真實錯誤的情況下通過。
8. 能做到時，應在 injected build 中實際操作受影響的功能。

對 binary-injection 相關變更而言，只做 source compile 是必要條件，但不是充分條件。

## 8. 失敗診斷

Injection 失敗時，先分類問題，再修改程式。

### `missing target method/field/type`

先判斷該 reference 是：

- 合法的 target API 差異，需要 adapter；
- 不穩定 API，應改成 reflection/probing；或
- 根本不應進入 payload 的 donor dependency。

### `ModDebug$Donor_*` 出現大量無關 LibGDX/API error

優先懷疑 shared R8/desugar synthetic dependency。檢查近期 payload source 是否加入 lambda、method reference、collection default method 或其他 compiler-generated helper。

### `donor-only dependency ... is not present`

表示 payload 引用了 closure 無法安全攜帶的 donor class。應移除該 dependency、明確重新設計 payload 邊界，或讓 dependency 真正 self-contained。

不得以關掉 self-containment check 的方式處理這些問題。

## 9. Repository attribution 剛性規定

此 repository 有以下剛性規定：

> 任何 repository 變更造成的公開可見 attribution，都只能出現 `edward9s`。

這包含 commit author/committer，以及使用的工作流程可能產生的公開 contributor/activity identity。

因此：

- 不得使用 GitHub Actions/bot 進行 commit 或 push
- 不得使用其他帳號或 GitHub App identity 寫入 repository
- 不得加入其他 identity 的 `Co-authored-by`
- 除非未來明確允許，避免會產生其他公開 attribution 的 PR/merge 工作流程
- 只能使用能確認最終 author 與 committer 都是 `edward9s` 的寫入路徑

Force-push 或 history rewrite 本身並不被禁止。真正的硬規則是 attribution identity。

## 10. 設計原則

當「較聰明、較簡短的寫法」與「較明確、編譯後 dependency graph 更容易理解的寫法」之間需要取捨時，injectable payload 應優先選後者。

對一般 SMM 程式碼而言，source-level elegance 往往足夠；但對 ModAnkh injectable payload 而言，編譯後 APK/JAR 的 dependency graph 本身就是 API 的一部分。
