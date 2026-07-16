# ChunkGuardAgent

一個 Java agent：**在 chunk 存檔的最後一刻攔截,如果發現要寫入的是「載入失敗的空白產物」、
而硬碟上還是好資料,就 SKIP 這次寫入** —— 好資料因此存活。專治高壓/OOM 下「4KB 空殼蓋掉好
chunk」的真毀損。判斷用**內容來歷(chunk status)不是檔案大小**,零誤殺。

*crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net*

- 目標:Paper / Folia,MC 26.2(1.21.11);設計上最大程度不綁版本(見〔跨版本〕)
- 下載:[`dist/ChunkGuardAgent-26.2-1.jar`](dist/ChunkGuardAgent-26.2-1.jar)(預編譯;checksum 與驗證報告裡的 jar 一致,版本說明見 [`RELEASES.md`](RELEASES.md))
- 安裝:`-javaagent:ChunkGuardAgent.jar`,重啟生效
- 實測:一台大型外掛環境的 Paper 26.2 測試伺服器—— 檢視 10,073 次真實存檔、**0 誤殺**(skipped=0)、0 反射錯誤、1,988 次非-full chunk 正確放行

ChunkGuardAgent 會吃多少記憶體？

常駐成本：幾乎為零。 幾 MB 的 Metaspace（agent 類別＋內嵌的 ASM，一次性、不佔 heap）、幾十 bytes 的計數器、兩條沉睡的 daemon thread。heap 上沒有任何常駐資料結構——它沒有快取、沒有名單、無狀態。

每次存檔的動態成本，分三種情況：

1. 絕大多數存檔（full 快速通道）：反射讀一個 status 字串就返回，臨時分配約幾十 bytes。實測 21,022 次存檔全走這條，無感。
2. 非-full 存檔＋記憶體充足：會解壓硬碟上那格來比對——暫時吃「該 chunk 解壓後的大小」，用完即成垃圾。日常這種情況是探索時的 worldgen 推進存檔，硬碟上是小的半成品（幾十~幾百 KB），成本小。大解壓只發生在「毀損正在發生」的瞬間（非-full 要蓋大型 full chunk 時），一次性、也正是它該花力氣的時刻。
3. 非-full 存檔＋記憶體吃緊（free < 192MB）：刻意零解壓，只讀 8KB region 目錄表。這是設計核心——在最缺記憶體的時刻，它幾乎不花記憶體，lowHeapMB 門檻就是為此存在。

一句話：平時 ≈ 0，災難時刻 ≈ 8KB。它不會成為 OOM 的幫兇

---

## 一、白話版

### 問題是什麼

伺服器記憶體爆掉(OOM)的時候,一個正在被讀取的 chunk 會因為配不到記憶體而**讀取失敗**,
遊戲的 chunk 系統就用一個**空白的替代品**頂上。糟糕的是:接下來關機存檔時,這個空白替代品會被
**寫回硬碟,蓋掉原本的好資料** —— 硬碟上那格 chunk 就變成一個 4KB 的空殼,玩家的建築/地形沒了。

我們在正式站觀察到一個「自然自救」的現象:如果伺服器在那次存檔**跑到之前**就先死掉,硬碟沒被覆寫,
好資料就活了下來。**這個 agent 就是把那個「自救」變成確定性的:失敗的空白,不准存回去。**

### 它怎麼判斷「這是空白產物、不是玩家真的把 chunk 挖空了」

不是看大小(玩家蓋在虛空的房子也可以很小)。看的是 chunk 的 **status(狀態)**。

Minecraft 的 chunk 狀態只會**前進**(empty → … → full),永遠不會倒退。一個「載入失敗的空白」是
`empty`/proto 狀態;而玩家挖空的 chunk **仍然是 `full`**。所以規則很乾淨:

> **只有當「要寫入的是非-full」而「硬碟上現存的是 full」時,才 SKIP。**

full 不可能合法地退回 empty,所以這條規則**永遠不會誤殺**一次正常存檔 —— 實測 10,073 次存檔,誤殺 0 次。

---

## 二、技術版

### 攔截點(單一 chokepoint)

instrument `net.minecraft.world.level.chunk.storage.RegionFileStorage`:

| 方法 | 角色 |
|---|---|
| `moonrise$finishWrite(int x, int z, WriteData)` | **主 SKIP 閘門**。Paper(Moonrise)所有排程 chunk 存檔的最後共同必經點。`void` 方法入口早退 = 完美跳過:區域檔零接觸、上游視為寫入成功 —— 正是「自救」的人工重現。 |
| `write(ChunkPos, CompoundTag)`(vanilla) | 保險 fallback(IOWorker / 世界升級 / 非 Moonrise build)。 |

注入的 bytecode 只做一件事:方法入口 `if (ChunkGuardRuntime.shouldSkip…(…)) return;`。

> ⚠️ **鐵則**:絕不透過回傳/偽造 `WriteData.WriteResult.DELETE` 來實作跳過 —— 那會觸發
> `regionFile.clear(pos)`,把硬碟好資料**清掉**,與目標完全相反。我們也刻意不碰 finishWrite 裡的
> 合法 DELETE 分支。

### 判定(iron rule,零誤殺)

```
incomingStatus = statusOf(要寫入的 CompoundTag)     // 反射 CompoundTag.getStringOr("Status", "")
if incomingStatus == "full"   → ALLOW               // 快路徑:full 一律放行(含玩家挖空的 chunk)
diskTag = storage.read(new ChunkPos(x, z))          // 同 IO 執行緒、synchronized,安全
if diskTag == null            → ALLOW               // 硬碟沒資料 = 正在生成的新 chunk
if statusOf(diskTag) == "full" → SKIP               // 非-full 要蓋掉 full = 毀損,保住硬碟
else                          → ALLOW               // 硬碟也非-full = 正常 worldgen 推進
```

### 零 NMS 編譯依賴(這就是跨版本的關鍵)

注入的 bytecode 只把 NMS 物件(`RegionFileStorage`/`ChunkPos`/`CompoundTag`/`WriteData`)當
`java.lang.Object` 傳給 bootstrap classloader 上的 `ChunkGuardRuntime`;runtime **用反射**讀 NBT
(對物件自己的 class,經 Paper classloader 看得到 `net.minecraft.*`)。所以:

- 本 jar **不編譯任何 NMS 符號**,不需要任何「對真 NMS 編譯 template」的前置步驟 —— `mvn package` 就是全部。
- 反射帶 fallback(`getStringOr` → `getString`+Optional 拆包;`input()` record accessor → 掃描回傳 CompoundTag 的方法),同一顆 jar 容忍版本漂移。

### 安裝與 config

**懶人包 —— 兩種情境,整行直接複製:**

① **試跑但不影響區塊**(shadow 模式:只記錄、不攔截,伺服器行為與沒裝時 100% 相同,建議先跑幾天):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-1.jar -Dchunkguard.shadow=true -jar paper-26.2.jar nogui
```

② **真的阻擋區塊毀損**(正式啟用):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-1.jar -jar paper-26.2.jar nogui
```

- `-Xms4G -Xmx4G` 換成你原本的記憶體設定;jar 檔名對應 [`dist/`](dist/) 下載的檔案。
- 已經有自己的啟動腳本?只要在 `java` 後面插入 `-javaagent:ChunkGuardAgent-26.2-1.jar` 這一段(試跑再多加 `-Dchunkguard.shadow=true`),其他參數全部照舊。
- 試跑判讀:log 出現 `SHADOW would-skip` = 它抓到一次毀損寫入(正式模式下會被擋);關機時 `inspectErrors=0`、平常存檔無異狀 → 可安心轉正式。

**進階微調(通常不用動):**

| system property | 預設 | 作用 |
|---|---|---|
| `chunkguard.enabled` | `true` | 總開關 |
| `chunkguard.shadow` | `false` | **只偵測不 skip** —— 印出「本來會擋」但仍放行。上線初期先跑幾天確認 0 誤殺 |
| `chunkguard.verbose` | `false` | 背景執行緒每 60 秒印計數 |
| `chunkguard.lowHeapMB` | `192` | 估計 free heap 低於此值(MB)時,跳過會 OOM 的 `readDisk` 解壓,改走零解壓的 header-only existence check → fail-**safe** |

後台署名:開機 banner + `chunkguard.author` system property + 一條帶署名的休眠 daemon thread(spark/thread dump 看得到)。

### 觀測計數

`inspected`(看過的存檔)/ `skipped`(擋下的毀損寫入)/ `shadowWouldSkip`(shadow 模式本來會擋)/
`allowedNewOrEmpty`(非-full 但硬碟也空 → 放行的新 chunk)/ `lowHeapFailsafe`(低記憶體時走 header-only
existence check 擋下的)/ `inspectErrors`(反射失敗 → fail-open)。
關機時印一次總結。

### 驗證(Paper 26.2)—— 見 [`docs/VALIDATION.md`](docs/VALIDATION.md) 完整報告(中英×白話/技術/AI)

**零誤殺(allow 面):** 大型外掛環境實測 `inspected=10073 skipped=0 inspectErrors=0 allowedNewOrEmpty=1988`。

**真毀損攔截(skip 面):** 用一格 **1144 箱/17160 物品** 的重 chunk、`-Xmx1200m` +
headroom 40MB **確定性重現** `chunk data will be lost`(OOM during load → empty proto → regen 到
`structure_starts` → 蓋回 full 好資料)。**連 4 次驗證全過**:每次 `blocked=1`、目標 chunk **byte-identical
（1144 箱一個沒少）**;shadow 對照(關掉 guard)同一招把 1144 箱清成 0 —— A/B 證明毀損為真、guard 為保。

> ⚠️ **過程中發現並修掉一個真缺陷(本版已修):** 原本 `decide()` 靠 `readDisk`(**解壓**硬碟 chunk 比
> Status)判斷,但毀損正好發生在 heap 枯竭時 → `readDisk` 自己 OOM → fail-**open** 放行,**guard 被同一場
> OOM 廢掉**。修法:低記憶體(free heap < `chunkguard.lowHeapMB`)或 `readDisk` 失敗時,改用**只讀 region
> header、零解壓**的 `diskChunkExists()`(`moonrise$getRegionFileIfLoaded/IfExists` + `RegionFile.hasChunk`),
> 該座標已有 chunk 就 fail-**SAFE(擋)**。實測擋下走的正是此路徑(log `disk=exists(failsafe)`、
> counter `lowHeapFailsafe=1`)。

**門檻歸因補測:** failsafe 有兩個入口(free heap < 門檻的捷徑、`readDisk` 回 null 的殊途),已用 debug build
分別歸因實證:真實低記憶體(free 111MB < 預設 192)下**門檻捷徑如設計觸發**並擋下(byte-identical);且解壓
重 chunk 即使 free 876MB 也會 OOM —— 兩入口互為保險、各自獨立驗證過。完整矩陣見
[`docs/VALIDATION.md`](docs/VALIDATION.md) Appendix D。

**自行重現:** 完整可執行的驗證套件在 [`test-harness/`](test-harness/)(拋棄式測試機上跑,含測試外掛、
harness、自帶 Anvil 讀取器);它會重現 `chunk data will be lost` 並比較 agent 開/關的區塊下場。詳見
[`test-harness/README.md`](test-harness/README.md) 與 [`docs/VALIDATION.md`](docs/VALIDATION.md)。

### 已知邊界與蓄意不做的設計

status 鐵則有一個已知邊界:讀取失敗的空白半成品若在**伺服器沒死、玩家在場**的少見組合下被
worldgen 重新生成推到 `full`,這個「假 full」(一片重生成的新原野)存檔時會被快速通道放行、
蓋掉硬碟上的真資料——status 層面兩者無法區分。

封這個洞的設計我們已完整推演——比對 `InhabitedTime`(「里程表」:玩家待在該格附近就累加、
永遠只加不減、跟著 chunk 資料走;真城堡幾百萬 ticks vs 假原野趨近 0,一眼拆穿)——**但蓄意
保留不做**:它的誤擋面正好落在生態圈的合法工具上(整格重生成類外掛、難度控制類外掛、線上
trim 工具的快取殘留窗),配套的旁路與白名單會把「掛上就忘」變成「掛上要讀文件」。實際觀測的
毀損案例全是「伺服器幾秒內死亡」型態,status 鐵則已覆蓋。完整設計、衝突面盤點與決策理由見
[`docs/INHABITED-TIME.md`](docs/INHABITED-TIME.md);若你的環境正好是高風險組合,歡迎開 issue。

---

## 三、給 AI 的結構化脈絡(machine-oriented)

```yaml
artifact:
  type: java-agent (ASM, relocated; pure-JDK runtime; reflection over NMS — no compile-time NMS dep)
  deploy: -javaagent:ChunkGuardAgent.jar   # premain + agentmain(dynamic attach 亦可)
  target: Paper/Folia MC 26.2 (1.21.11); Moonrise chunk system
  build: mvn package (JDK 25; maven.compiler.release=21; no NMS template step)

problem:
  symptom: 高壓/OOM 下 chunk 讀取失敗 → 空白頂替 → 存檔把空白寫回硬碟蓋掉好資料 = 4KB 空殼真毀損
  evidence: 正式站 log 'Failed to decompress chunk data'(MoonriseRegionFileIO)+ 'chunk data will be
            lost'(ChunkLoadTask);正式站實測「JVM 在存檔前先死→硬碟好資料存活」的自救現象 = 解法方向

mechanism:
  hook_primary: net.minecraft.world.level.chunk.storage.RegionFileStorage#moonrise$finishWrite(IIL…WriteData;)V
                entry-guard: if (ChunkGuardRuntime.shouldSkipMoonrise(this,x,z,writeData)) return;  // void 早退=乾淨 skip
  hook_fallback: RegionFileStorage#write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V
  disk_compare: RegionFileStorage#read(ChunkPos) 反射呼叫(同 IO 執行緒 synchronized,安全)
  provenance_hook_deferred: MoonriseRegionFileIO#finishRead / ChunkLoadTask 標記 poisoned 座標(v2,best-effort)
  inhabitedtime_rule_deferred: 比對 InhabitedTime 單調性可攔「重生成假 full」;蓄意不做——誤擋面
    與整格重生成/難度控制類外掛重疊,違反零設定原則;完整設計見 docs/INHABITED-TIME.md

decision_iron_rule:
  # chunk status 只前進不倒退;full 永不合法退回 proto → 零誤殺
  SKIP iff statusOf(incoming) != "full" AND statusOf(disk.read(x,z)) == "full"
  status_read: CompoundTag.getStringOr("Status","") -> strip "minecraft:" -> lowercase
  never_use: file size / sector count as the primary signal; isLightOn (Paper starlight clobbers it)

hard_rules:
  - 絕不回傳/偽造 WriteData.WriteResult.DELETE 來 skip → 會 regionFile.clear(pos) 毀掉好資料
  - fail-open:任何反射/判定不確定 → ALLOW(絕不 block 合法存檔)
  - transform 全身 try/catch → 例外回原 bytes(該類維持 vanilla),找不到方法 → 不 arm 也不炸開機

cross_version:
  - 純字串名比對 target;缺席即靜默降級 fail-open
  - Paper >=1.20.5 mojang-mapped(名稱穩);<=1.20.4 spigot-mapped → 比對不到 → fail-open(正確)
  - NBT 扁平格式(無 Level 包裝、sections 小寫)自 1.18(DataVersion>=2842)穩定;Status 值 strip namespace
  - CompoundTag API:26.2 getStringOr/getString(Optional);反射層對回傳做 Optional 拆包 fallback
  - Moonrise 類名(ca.spottedleaf.*)是 1.21 世代(前身 io.papermc.paper.chunk.system.*)→ 只當主 hook,
    找不到就退 vanilla write;主判斷(內容+disk-compare)不依賴 Moonrise 內部

config: chunkguard.enabled(true) / chunkguard.shadow(false, detect-only) / chunkguard.verbose(false) / chunkguard.lowHeapMB(192, low-heap failsafe 門檻)
counters: inspected / skipped / shadowWouldSkip / allowedNewOrEmpty / lowHeapFailsafe / inspectErrors

related:
  - 架構骨架:ASM relocated + bootstrap classloader + 署名 daemon thread
  - 玩家層互補修法:在偵測到伺服器垂死時、從一條仍存活的執行緒擋掉新玩家連線(掐掉毒源);ChunkGuard = chunk 層最後一道網
```

---

*ChunkGuardAgent — crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net*
