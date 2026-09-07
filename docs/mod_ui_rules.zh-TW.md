# SMM Mod UI 規則

這些規則用來讓 Master Mode 的 UI 元素與原版 SPD 保持足夠辨識度，同時維持對支援 fork 的二進位注入相容性。

## 重用原版 Buff 圖示

- Mod buff 可以重用原版 `BuffIndicator` 圖示，但圖形應與功能語意合理對應。
- 重用原版圖示時，必須讓外觀與原版用途有明顯區別，通常應覆寫 `tintIcon()`。
- tint 應盡量避開該原版圖示正常可能出現的完整色域。
- 永久 Mod buff 彼此之間也應透過圖形、色相或兩者維持明顯辨識度。
- `iconTextDisplay()` 只能作為第二辨識手段；若重用圖示本身容易與原版混淆，不可只靠文字區分。
- 若 tint 仍無法可靠區分，才考慮換圖；但換圖必須先確認 injection 相容性。

## Injection 相容性

- 不可只為外觀而新增 target-owned 的 `BuffIndicator.*` 常數引用，除非已確認其編譯後整數 index 在所有支援的 injection target 上都穩定一致。
- `public static final` primitive 常數會被 `javac` 在編譯期直接內聯；編譯後 injector 無法再驗證該符號欄位在另一個 fork 是否仍存在、或是否仍對應同一張圖。
- 若跨 fork 穩定性不確定，應保留目前已由支援 payload 使用過的 icon index，改用 tint 與／或文字來區分 Mod buff。
- 純視覺修改不應額外增加不必要的 target API dependency。

## 目前永久 Mod buff 配色

- Assassin：`PREPARATION`，紫色 `0xB06CFF`。
- Loot：`AMULET`，冷銀藍 `0xDDEEFF`。
- Last Stand：`BERSERK`，淡暖金 `0xFFF0A8`（為 injection 相容性保留既有圖示）。
- Parry：`DUEL_GUARD`，青藍 `0x55CCFF`。
- Riposte：`DUEL_CLEAVE`，粉紅紅 `0xFF5577`。
- Instant Kill：`DUEL_CLEAVE`，亮綠 `0x66FF33`，並顯示 `K`。
