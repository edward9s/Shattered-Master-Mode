# SMM Debug Console 使用指南

[English](debug_console.md)

SMM 的 Debug Console 是給 Shattered Pixel Dungeon 衍生版本使用的遊戲內反射／修改工具，主要用途是測試、檢查物件、快速實驗與除錯存檔，不是一般遊玩機制。

Debug Console 本身是 SMM debug payload 的一部分，**實作上並不綁死在 ModAnkh**。實際從哪個 UI 入口開啟，取決於 SMM 如何整合進目標版本。**ModAnkh -> Console** 是其中一個支援入口，而且是 `--ankh-only` 注入模式的重要入口；其他／完整 SMM 整合也可以直接暴露同一套 Console。

> 可用的 class、field 與 method 會隨目標 SPD fork 與版本不同。碰到目標版本沒有的 API 時，對應指令可能失敗。

## 遊戲內 `help`

實際執行的版本應以遊戲內 `help` 當成最即時的指令索引：

```text
help
help give
help spawn
help affect
help terrain
help @
help !!
help search
help fuzzy
```

`help <topic>` 只會顯示該 command／topic 的說明。這份文件主要解釋常用工作流；如果新版改了語法，應優先相信當下 build 的遊戲內 `help`。

## 核心操作

最重要的四個 reflection 操作是：

```text
inspect @x
get @x field
set @x field value
use @x method args...
```

- `inspect`：列出 field 與 method。
- `get`：讀取 field。
- `set`：寫入 field。
- `use`：呼叫 method。

`get/set` 不會自動退回 method；`use` 也不會在找不到 method 時改成操作 field。

## Handle：`@name`

Handle 可以替目前遊戲中的實際 Java 物件或值取一個暫時名稱：

```text
@
@item inv
@cell cell
@mob char
@obj obj
@hero hero
@level level
@item
@item clear
```

常用形式：

- `@`：列出目前所有 handle。
- `@item inv`：從背包選擇 Item。
- `@cell cell`：選擇地圖 cell，保存其整數 cell 編號。
- `@mob char`：選擇並保存實際存在的 `Char`。
- `@obj obj`：保存該 cell 上的物件；找不到物件時退回保存 cell 編號。
- `@hero hero`：保存目前 Hero。
- `@level level`：保存目前 Level。
- `@item`：顯示 handle 內容。
- `@item clear`：刪除 handle。

Handle 是 process-local 的強引用，不會寫入存檔。換樓層不會自動清除，因此底層物件離開目前遊戲狀態後，handle 可能變成 stale reference。

許多 command 也可以直接把回傳值存進 handle：

```text
@item give PotionOfHealing
@rat spawn Rat
@buff affect Haste
@blob seed Fire 10
@trap trap AlarmTrap
@child get @object someField
@result use @object someMethod
```

## 查看 class 與物件

```text
inspect @item
inspect hero
inspect level
inspect RingOfEnergy
inspect @hero buff
inspect @mob attk
```

可額外指定 query 篩選 field／method 名稱。比對不分大小寫，並支援完全相同、prefix、substring 與 fuzzy subsequence。

`give`、`spawn`、`affect`、`seed`、`trap`、`inspect`、`use` 等需要 class 名稱的 command 也支援 fuzzy assistance。若最佳匹配不唯一，Console 不會直接執行，而是列出 `Similar:` 候選。

## 直接讀寫 field

```text
get @item quantity
get @hero HP
set @item quantity 99
set @hero HP 100
set @object enabled true
set @object ratio 1.5
set @object target @rat
set @object optionalField null
```

Field lookup 會一路往 superclass 找，也能透過 reflection 存取非 public field。輸入值會依實際 Java field type 轉換；型別不相容時直接失敗，不會亂猜。

物件型 field 也可以繼續存成新的 handle：

```text
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

## 用 `use` 呼叫 method

```text
use @item quantity 99
use @item upgrade
use @rat beckon 123
use hero someMethod
use level someMethod
use SomeClass staticMethod 10
```

語法：

```text
use <Class|hero|level|@handle> <method> [args...]
```

參數會依 method 真正的 Java parameter type 轉換，也支援引號字串、handle 與明確的 `new:<Class>`：

```text
use @object rename "test object"
use @object setTarget @rat
use @weapon enchant new:Grim
```

## 武器附魔與防具刻印

```text
@weapon inv
@armor inv

enchant @weapon Grim
enchant @weapon random
enchant @weapon none

inscribe @armor Brimstone
inscribe @armor random
inscribe @armor none
```

`random` 會走目標遊戲原本的無參數操作；`none`／`null` 清除效果。指定 class 時必須是相容的 `Weapon.Enchantment` 或 `Armor.Glyph` subclass。

## 建立物品：`give`

```text
give PotionOfHealing
give ScrollOfUpgrade x10
give Longsword +10
give PotionOfHealing x10 --force
```

語法：

```text
give <Item> [+level] [xquantity] [-f|--force] [method [args...]]
```

`--force` 直接 collect，不走一般 pickup 邏輯。最後也可以指定一個 method，在每個新 Item 被撿起前先呼叫。

## 生成 Mob：`spawn`

```text
spawn Rat
spawn Rat 123
spawn Rat @cell
spawn Rat x1
spawn Rat x10
@rat spawn Rat
```

語法：

```text
spawn <Mob> [cell|@variable|xquantity] [method [args...]]
```

- 單隻 Mob 沒指定位置時，預設開啟 cell selector。
- 指定 cell 編號或數字 handle 時，直接在該格生成。
- `xN` 使用正常合法 respawn cell；`x1` 很適合不希望出現 selector 的 macro。
- 手動／明確指定位置仍保留正常 Mob 落點安全檢查，刻意比 `warp` 嚴格。

某些特殊 Mob 無法只靠裸 constructor 正確建立，Console 會補上必要的 debug 初始化。

## 套用 Buff：`affect`

```text
affect Haste
affect Haste 20
@buff affect Haste 20
```

語法：

```text
affect <Buff> [duration] [method [args...]]
```

輸入後選擇一個角色。現在的 `affect` 對**完全相同的 Buff class 採 toggle 語意**：

- 該 class 已存在：detach；
- 該 class 不存在：apply；
- duration／method 參數只在 apply 時使用。

因此重複操作時的行為和 Journal 的 Buff toggle 一致。

Duration 的實際處理方式取決於目標 Buff implementation。單純的 duration API 可以直接處理；語意特殊的 fork-only Buff 若無法安全辨識，就應維持原本預設行為或使用其專用初始化 method，而不是亂猜內部數值欄位。

注入版也可能隨 injection payload 帶入 SMM 自己的支援 Buff／class；實際可用內容取決於所選 injection mode 與目標版本相容性。

## Blob：`seed`

```text
seed Fire
seed Fire 20
@gas seed ToxicGas 100
```

語法：

```text
seed <Blob> [amount]
```

輸入後選擇目標 cell。`amount` 預設為 `1`，會傳進目標遊戲的 blob seed API；它的實際意義由各 Blob class 定義，不能一律當成 duration。

目前 Console 在 seed 並把 Blob 加入 scene 後，會**立刻額外呼叫一次 `Blob.act()`**。這是刻意的，讓火焰、氣體等效果可以立即作用在選定 cell，而不必再等下一個遊戲回合。

前面加 handle 可以保存實際建立出的 Blob instance。

## Trap：`trap`

```text
trap AlarmTrap
trap alarm
@trap trap RockfallTrap
```

`trap` 會建立 Trap、設定 cell、reveal、加入目前 Level、把 tile 改成 `Terrain.TRAP`，並刷新地圖與視野狀態。

## 地形：`terrain`

```text
terrain LOCKED_DOOR
terrain CHASM
terrain WATER
terrain WALL
terrain CHASM @cell
terrain WALL 123
```

語法：

```text
terrain <Terrain|id> [cell|@variable]
```

名稱不分大小寫並支援 fuzzy matching。如果 fork-specific 名稱不存在或被 R8 移除，也可以直接使用 raw numeric terrain ID。

`terrain` 會呼叫目標 Level 的 terrain setter，接著刷新 map、observe 與 fog，所以一般 terrain 會立即更新。需要額外物件／狀態的機制，例如 Trap、transition、scripted gate、special room，仍必須另外建立其配套狀態。

## 移動

同樓層 debug teleport：

```text
warp
warp 123
warp @cell
```

`warp` 刻意允許進入牆壁、pit 等不正常但仍位於地圖內的 terrain，方便除錯；仍會拒絕非法 cell 或已被其他角色佔據的 cell。

切換樓層／branch：

```text
goto 10
goto 10 0
where
```

`goto <depth> [branch]` 使用目標遊戲本身的 interlevel transition 機制；`where` 顯示目前 depth 與 branch。

## 數值搜尋

Value Search 和物件 field 的 `get/set` 是兩套不同功能：搜尋結果用 `#id`，物件 handle 用 `@name`。

```text
search 100
results
search changed
search increased
search decreased
search unchanged
search 80
results #12
get #12
set #12 999
clear
```

搜尋器會掃描可到達的遊戲 model 物件與 numeric field，並設 traversal limit。結果只屬於目前 session；owner object 消失後可能失效。

## Macro

```text
macro
macro test
```

- `macro`：列出目前 macro。
- `macro name`：開啟該 macro 編輯器。
- 一行一條 debug command。
- 空白行與 `#` 開頭行會忽略。
- `%1` 到 `%9` 是位置參數。
- 儲存空內容等於刪除 macro。

範例 macro：

```text
give PotionOfHealing x%1
warp %2
```

執行：

```text
test 10 123
```

Macro 可以呼叫其他 macro，但有巢狀限制。會開互動式 selector 的 command 必須放在最後，除非已用明確 cell／handle 避免 selector。

Macro 與一般遊戲存檔分開持久保存；Android 使用 app-private storage，因此清除 app data 或解除安裝會移除它們。

## 上一條指令：`!!`

```text
use @weapon upgrade
!!
!! 100
```

- `!!`：把上一條 command 額外再執行一次。
- `!! N`：額外執行 N 次，受遊戲內上限限制。
- 批次重播不允許一次開出多個互動式 selector。

頂層 Console 仍保留 ScrollOfDebug 風格的 inline `!!` 文字展開：

```text
give PotionOfHealing
!! x10

give Longsword
!! +10
```

## 存檔傳輸

```text
save
load
```

Android 上，`save` 會把 app save files 匯出到 `Download/<package>`；`load` 匯回後重新啟動 app。實際 storage／permission 行為會受 Android 版本與 target package 影響。

## 重要限制

- Handle 是記憶體 reference，可能隨底層物件消失而失效。
- Reflection 可以繞過遊戲 invariant；型別正確的值仍可能形成邏輯上不可能的遊戲狀態。
- Boss、scripted NPC、特殊樓層、fork-only class 可能依賴 generic debug command 無法重建的隱藏狀態。
- 不同 SPD 版本／fork 的 class、field、method 與 API 可能不同。遇到不確定情況先用 `inspect` 與遊戲內 `help`。
- `warp` 刻意允許異常 terrain；手動／明確指定位置的 `spawn` 則刻意保留正常 Mob 落點限制。

做破壞性實驗前，建議使用可丟棄存檔或先匯出備份。

## 致謝

SMM Debug Console 的設計部分受到 [Zrp200 的 ScrollOfDebug](https://github.com/Zrp200/ScrollOfDebug) 啟發；它以 reflection 為核心的 command interface 與遊戲內除錯工具，為這類 developer tooling 提供了重要參考。感謝 Zrp200 與 ScrollOfDebug contributors 的工作、貢獻與啟發。
