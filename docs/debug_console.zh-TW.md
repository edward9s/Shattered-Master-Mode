# SMM Debug Console 使用指南

[English](debug_console.md)

SMM 的 Debug Console 是給 Shattered Pixel Dungeon 衍生版本使用的遊戲內反射／修改工具，主要用途是測試、檢查物件、快速實驗與除錯存檔，不是一般遊玩機制。

Console 由 **ModAnkh** 提供。使用 ModAnkh，選擇 **Console**，然後一次輸入一條指令。

> 可用的 class、field 與 method 會隨目標 SPD fork 與版本不同。碰到目標版本沒有的 API 時，對應指令可能失敗。

## 先理解這四件事

最重要的四個操作是：

```text
inspect @x
get @x field
set @x field value
use @x method args...
```

- `inspect`：查看物件或 class 的 field 與 method。
- `get`：直接讀取 field。
- `set`：直接寫入 field。
- `use`：呼叫 method。

`get/set` 不會偷偷改成呼叫 method；`use` 也不會在找不到 method 時偷偷操作 field。即使某個 class 同時有同名 field 與 method，語意也仍然明確。

## Handle：`@name`

`@` handle 可以替目前遊戲中的實際 Java 物件或值取一個暫時名稱。Handle 只存在於目前程序記憶體，不會寫進遊戲存檔。

### 建立與查看 handle

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

用途如下：

- `@`：列出目前所有 handle。
- `@item inv`：打開背包選擇器，把選到的 Item 存成 `@item`。
- `@cell cell`：選一格地圖，把該 cell 的整數編號存起來。
- `@mob char`：選擇有角色的 cell，把那一個實際存在的 `Char` 物件存起來。
- `@obj obj`：選一格地圖，優先抓該格的角色或其他物件；若找不到物件，就存 cell 編號。
- `@hero hero`：保存目前的 Hero 物件。
- `@level level`：保存目前的 Level 物件。
- `@item`：顯示 handle 目前指向的值。
- `@item clear`：刪除該 handle。

Handle 最大的價值是能指定「遊戲裡那一個實際 instance」。例如 `use Rat ...` 可能針對 class／新建立的 instance 操作，而 `use @rat ...` 一定是對你剛剛從地圖上選到的那隻 Rat 操作。

也可以把部分指令回傳的物件直接存成 handle：

```text
@item give PotionOfHealing
@rat spawn Rat -p
@buff affect Haste
@blob seed Fire 10
@trap trap AlarmTrap
@child get @object someField
@result use @object someMethod
```

只有在指令真的回傳非 `null` 物件時，目的 handle 才會被更新。

## 查看物件與 class

```text
inspect @item
inspect hero
inspect level
inspect RingOfEnergy
```

`inspect` 會列出可找到的 field 與 method，也包含繼承自 superclass 的成員。不知道實際欄位或方法名稱時，通常先跑 `inspect` 最有效。

也可以在 target 後面加一個 query，同時篩選 field 與 method 名稱：

```text
inspect @item quan
inspect @hero buff
inspect @mob attk
```

搜尋不分大小寫，結果依符合程度排序：完全相同、前綴相同、包含字串，最後才是 fuzzy subsequence match。模糊匹配只要求 query 的字元依序出現在名稱中，所以 `attk` 也能找到 `attack` 一類的名稱。結果數量不設上限；如果搜尋太廣，直接把 query 打得更精確即可。

不帶 query 時，`inspect` 仍維持原本的完整列表行為。

Class 名稱可用簡名，例如 `Rat`、`RingOfEnergy`，也可以輸入完整 Java class 名稱。

## 直接讀寫 field

### 讀取 field

```text
get @item quantity
get @hero HP
```

Field lookup 會一路往 superclass 找，而且會透過 reflection 存取非 public field。

若 field 本身也是一個物件，可以再把它抓成新的 handle：

```text
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

若讀到的 field 是 `null`，目的 handle 不會被覆蓋。

### 寫入 field

```text
set @item quantity 99
set @hero HP 100
set @object enabled true
set @object ratio 1.5
set @object target @rat
set @object optionalField null
```

設定值會依 field 真正的 Java 型別轉換。支援常見 primitive／boxed 數值、boolean、char、String、enum、reference field 的 `null`，以及型別相容的其他 `@handle`。

如果型別不相容，指令會直接失敗，不會自行猜測。有些 field 即使 reflection 能碰到，也可能在 JVM 或遊戲邏輯上實際不可安全修改。

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

參數會依 method 的 Java parameter type 自動轉換，也支援引號字串與 handle：

```text
use @object rename "test object"
use @object setTarget @rat
```

找不到相容 method 時就會報錯。`use` 不會把 method 名稱當 field 名稱來處理。

## 建立物品：`give`

```text
give PotionOfHealing
give ScrollOfUpgrade x10
give Weapon +5
give PotionOfHealing x10 --force
```

語法：

```text
give <Item> [+level] [xquantity] [-f|--force] [method [args...]]
```

- `+5` / `-2`：適用時設定 Item level。
- `x10`：建立多份物品。
- `--force`：直接 collect，不走一般 pickup 邏輯。
- 最後可額外指定一個 method，在每個新 Item 被撿起前先呼叫。

例如：

```text
give Longsword +10
@item inv
set @item quantity 2
```

如果要改現有 stack，可以先 `@item inv` 選中它，再用 `set @item quantity 99` 直接修改 quantity field。至於某個特殊 Item 是否真的適合 quantity > 1，仍由該 Item class 的遊戲邏輯決定。

## 生成 Mob：`spawn`

```text
spawn Rat
spawn Rat x5
spawn Rat -p
@rat spawn Rat -p
```

語法：

```text
spawn <Mob> [xquantity|-p|--place] [method [args...]]
```

- 不加 `-p` 時，由遊戲自己找正常 respawn cell。
- `-p` 可手動選一個合法的 Mob 落點。
- 可在生成完成後額外呼叫指定 method。

手動生成 Mob 仍遵守正常的落點安全條件，這部分刻意比 `warp` 嚴格。

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

輸入指令後，再從地圖選擇要套用 Buff 的角色。`duration` 對 `FlavourBuff` 類型的暫時效果特別有用，也可在建立後再呼叫相容的初始化 method。

## Blob 與 Trap

### 產生 Blob

```text
seed Fire
seed Fire 20
@blob seed Fire 20
```

語法：

```text
seed <Blob> [amount]
```

輸入後再選地圖 cell。

### 放置 Trap

```text
trap AlarmTrap
@trap trap AlarmTrap
```

語法：

```text
trap <Trap>
```

輸入後再選地圖 cell。Debug 指令會建立並 reveal 該陷阱。

## 移動

### `warp`：同樓層自由瞬移

```text
warp
warp 123
warp @cell
```

`warp` 是同一樓層內的 debug teleport。沒有參數時會打開 cell selector。

它不像原版 Scroll of Teleportation 那樣要求落點可正常行走。只要是地圖內 cell，牆壁、障礙、pit 等地形都可以故意進去測試。仍然會拒絕地圖外 cell，以及已被其他角色佔據的 cell。

### `goto`：切換樓層／branch

```text
goto 10
goto 10 0
```

語法：

```text
goto <depth> [branch]
```

branch 預設為 `0`。這會使用目標遊戲本身的 interlevel transition 機制，所以比 `warp` 更依賴該 fork 的內部實作。

### 查看目前樓層

```text
where
```

顯示目前 depth 與 branch。

## 數值搜尋

Value Search 與物件 field 的 `get/set` 是兩套不同功能：搜尋結果用 `#id`，物件 handle 用 `@name`。

先搜尋目前看到的數字：

```text
search 100
results
```

接著在遊戲裡讓數值發生變化，再逐步縮小候選：

```text
search changed
search increased
search decreased
search unchanged
```

也可以直接用另一個精確數字繼續過濾：

```text
search 80
```

查看或修改搜尋結果：

```text
results #12
get #12
set #12 999
```

清除目前搜尋 session：

```text
clear
```

典型用法：

```text
search 20
# 回到遊戲讓數值改變
search decreased
# 再改一次
search decreased
results
get #7
set #7 999
```

搜尋器會掃描可到達的遊戲 model 物件與數值 field，並設有限制避免無界限遍歷。搜尋結果只屬於目前 session；如果原本的 owner object 消失，結果可能會過期。

## Macro

```text
macro
macro test
```

- `macro`：列出目前保存的 macro。
- `macro name`：打開該 macro 的編輯器。
- 一行放一條 debug command。
- `%1` 到 `%9` 是位置參數。
- 儲存空內容等於刪除 macro。

例如 macro 內容：

```text
give PotionOfHealing x%1
warp %2
```

之後執行：

```text
test 10 123
```

Macro 可以呼叫其他 macro，但有遞迴上限。會打開互動式 selector 的指令必須放在 macro 最後一行，因為 selector 的完成是非同步的。

Macro 會獨立持久化，不依賴一般遊戲存檔。

## 重複上一條指令：`!!`

```text
!!
```

`!!` 會展開成上一條 Debug command。它也可以出現在一行指令中，只要展開後的內容語法合理即可。

## 存檔傳輸

```text
save
load
```

Android 上：

- `save`：把 app save files 匯出到 `Download/<package>`。
- `load`：把存檔匯回 app，然後重新啟動。

這主要是開發測試與轉移存檔用途。不同 Android 版本與 target package 的儲存權限行為可能不同。

## 常用工作流

### 直接修改一個物品

```text
@item inv
inspect @item
get @item quantity
set @item quantity 99
use @item identify
```

### 操作地圖上的某一隻 Mob

```text
@rat char
inspect @rat
get @rat HP
set @rat HP 1
use @rat someMethod
```

### 沿著物件關係一路往下找

```text
@hero hero
@belongings get @hero belongings
@backpack get @belongings backpack
inspect @backpack
```

### 記住某個 cell 再瞬移回去

```text
@home cell
warp @home
```

## 重要限制

- Handle 是記憶體中的 reference，不會序列化。如果底層遊戲物件被移除或替換，原 handle 可能失效。
- Reflection 可以繞過遊戲原本的 invariant。即使型別正確，塞進邏輯上不可能的數值仍可能破壞遊戲狀態或存檔。
- Boss、劇情 NPC、特殊樓層與 fork 特有 class 常依賴額外隱藏狀態，單靠 debug command 不一定能重建。
- 不同 SPD 版本／fork 的 class、field、method 名稱可能不同。遇到不確定的情況先 `inspect`，不要假設其他版本的 API 一定存在。
- `warp` 是刻意允許異常地形的 debug 工具；`spawn -p` 則刻意保留正常 Mob 落點限制。

如果要做破壞性 field 修改，建議先用可丟棄的存檔，或先匯出備份。

## 致謝

SMM Debug Console 的設計部分受到 [Zrp200 的 ScrollOfDebug](https://github.com/Zrp200/ScrollOfDebug) 啟發；它以 reflection 為核心的命令介面與遊戲內除錯工具，為這類開發工具提供了重要參考。感謝 Zrp200 與 ScrollOfDebug contributors 的工作、貢獻與啟發。
