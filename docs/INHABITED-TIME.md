# InhabitedTime「里程表」規則 — 從蓄意不做到正式實裝的完整歷程

> ⚠️ **2026-07-17 更新:本文描述的規則已於 26.2-2 實作**(`-Dchunkguard.inhabitedGuard`,
> **預設開啟**)。本文第一~四章保留當初「蓄意不做」的完整推演(2026-07-16 寫成),第五章記錄
> 讓決策反轉的正式環境證據與實作細節。閱讀價值:這是一份「證據改變、結論就改變」的實錄。

> **TL;DR (EN):** A regenerated-to-`full` impostor chunk can slip past the status iron rule
> (`full` over `full` is always allowed). Comparing `InhabitedTime` — a monotonic, data-carried
> counter — would catch it: a lived-in chunk carries millions of ticks, a freshly regenerated one
> carries ~0. We understand this design completely and **deliberately ship without it**: its
> false-positive surface overlaps with legitimate ecosystem tools (chunk-regen plugins,
> difficulty-reset plugins, online trim tools), and the observed real-world incidents are already
> covered by the status rule. This document records the full design so anyone who needs it can
> implement or request it.

---

## 一、它要堵的洞:假 full

status 鐵則(「半成品不准蓋 full」)有一個已知邊界:

1. OOM 當下讀取失敗,空白半成品頂上——此後只要它還是半成品,每次存檔都被擋,硬碟資料安全。
2. **大多數情況**伺服器幾秒內死掉或重啟,下次開機從硬碟讀回好資料,玩家無感。
3. **少數情況**:伺服器撐過 OOM 沒死、玩家又剛好站在那格附近——worldgen 會把空白半成品當新地形,
   從種子重新生成一路推到 `full`。
4. 這個「假 full」存檔時,鐵則的快速通道(`full` 一律放行)會放它過——**重生成的新原野蓋掉硬碟裡的
   真城堡**。status 層面兩者完全無法區分。

(殘酷的細節:走到第 3 步時,玩家在遊戲裡看到的已經是原野——損害在記憶體裡已經發生,寫入只是把它
永久化。)

## 二、里程表規則長什麼樣

chunk NBT 裡有兩種「時間」:

- **時鐘(`LastUpdate`、region timestamp):不能用。** 記的是「上次何時寫入」——真城堡和假原野
  的存檔都發生在「現在」,寫完都是現在的時間,分不出血統。
- **里程表(`InhabitedTime`):可以用。** 它是計數器不是時鐘:玩家在這格附近每 tick 累加、
  **永遠只加不減**,而且**存在 chunk 資料裡、跟著資料走**——從硬碟讀出時被繼承,之後只會更大。

規則:

```
SKIP iff incoming 是 full
      AND disk.InhabitedTime - incoming.InhabitedTime 差距懸殊(例如 > 1 小時 = 72,000 ticks)
      AND incoming.InhabitedTime 趨近零(容忍重生成期間玩家在場累積的幾分鐘)
```

真城堡被住了幾十小時 = 幾百萬 ticks;假原野連同重生成期間頂多幾千 ticks。同樣都是 `full`、
同樣都是「現在」存檔——看里程數,一眼拆穿。

這個規則有兩個漂亮的性質:

- **無狀態**——不用維護任何名單,只是判斷時多讀一個 long,與 v1 架構相同。
- **恰好只保護值得保護的格子**——沒人住過的荒野兩邊都趨近零、測不出倒退,但荒野被重生成也沒有
  玩家損失;玩家住越久的格子,倒退落差越大、越好抓。

誤擋的代價也不對稱:被擋只是「這一次沒存進硬碟」+ 一行 log——不會炸伺服器、不掉任何資料。

## 三、那為什麼不做?——衝突面盤點

會與這條規則衝突的,只有一種行為模式:**在伺服器運轉中,把一格「有里程的舊 chunk」換成
「零里程的新內容」還要存回硬碟。** 逐一盤點:

| 工具/行為 | 衝突? | 說明 |
|---|---|---|
| 離線工具(MCASelector、離線 trim CLI、備份還原) | 無關 | 伺服器關著,agent 不在場 |
| WorldEdit `//regen`(現代版) | 大概率不衝 | 貼方塊進原 chunk,chunk 本體沒換,里程原封不動(未實測) |
| 線上 trim 工具(直接清 region 檔目錄表) | 短暫誤擋窗 | 清完後硬碟無資料、規則無從比對→放行;但 Paper 記憶體快取殘留舊資料的幾分鐘內,重生成存檔會被誤擋(零資料損失,晚點落盤) |
| **舊式「整格重生成」API 的外掛**(海島重置、礦區重置、資源世界重置) | **衝** | 整個 chunk 換新、里程歸零,每次存檔都會被擋 |
| **難度控制類外掛**(故意歸零 InhabitedTime 壓低區域難度) | **衝** | 每次動手都是「合法的里程倒退」 |
| Paper `fixed-chunk-inhabited-time` 設定 | 規則失效 | 里程被釘成常數,比不出差距——不是衝突,是靜默不動作 |

要讓這些合法場景可用,就必須配套:旁路機制(例如 touch 一個 marker 檔暫停規則)、外掛白名單、
被攔時的引導訊息……這些都做得出來,但每一件都違反本專案的核心原則——**零誤殺、零設定、
掛上就忘**。

## 四、決策

**目前版本蓄意不實作里程表規則。** 理由:

1. 實際在正式環境觀測到的毀損案例,全部是「伺服器幾秒內死亡」型態——status 鐵則已完整覆蓋,
   假 full 需要「伺服器帶病存活 + 玩家在場等重生成完成」這個少見組合才會發生。
2. 規則本身乾淨,但它的**衝突面落在別人的外掛上**:裝了 chunk 重生成類或難度控制類外掛的
   伺服器,會遇到需要理解與設定的誤擋——這把「掛上就忘」變成「掛上要讀文件」,我們不願意。
3. 同一個洞另有一條血統精確的路(provenance hook:在讀取失敗當下把座標記入中毒名單,此後該
   座標連 full 都擋,直到成功讀取才除名)——同樣在 roadmap 上,同樣未實作,同樣因為複雜度。

如果你的伺服器正好處在高風險組合(長時間高記憶體壓力下持續運轉、不重啟),歡迎開 issue 討論,
或按本文件的規則描述自行實作——設計已經完整,缺的只是一個真的需要它的場景。

> **(2026-07-17 註:那個場景隔天就出現了,而且一次四個。見第五章。)**

## 五、決策反轉:正式環境的實證與 26.2-2 實作

### 讓結論改變的證據

第四章的核心假設是「假 full 罕見,因為伺服器通常幾秒內死掉、死掉資料反而活」。上線觀測兩週後,
這個假設被推翻:**正式艦隊找到 4 格真實的假 full 受害者**——

| 案例 | live 里程 | 事發當日凍結備份里程 | 附註 |
|---|---|---|---|
| s16 overworld (-67,-59) | 1,029 ticks(51 秒) | 16,508,199(229 小時) | 玩家回報「領地破洞」後驗出 |
| s16 overworld (-68,-62) | 319(16 秒) | 870,208(12 小時) | 巡檢挖出,無人回報 |
| s16 overworld (-66,-46) | 0 | 7,748,632(108 小時) | 巡檢挖出,無人回報 |
| s73 the_end (23,-275) | 2,778(2.3 分鐘) | 92,941,857(**1,290 小時**) | 巡檢挖出,無人回報 |

為什麼「罕見」不成立:這座艦隊配備了看門貓與 OOM 疏散機制,**伺服器在記憶體風暴中的存活能力
被刻意強化**——而「伺服器倖存」正是假 full 誕生的必要條件。救活伺服器的本事越強,假 full 越常見。
對任何有 OOM 韌性設計的伺服器,這個結論都適用。

同時,4 例中 3 例無人回報——假 full 外觀是一片正常的新地形,沒有玩家踩進去對照記憶,它就永遠
隱形。「等有人回報再說」不是可行的偵測策略。

### 26.2-2 實作(`-Dchunkguard.inhabitedGuard`,預設開啟)

規則(完全按第二章的設計,門檻取保守值):

```
SKIP iff incoming.Status == full
      AND incoming.InhabitedTime < 72,000(1 小時)         ← 里程夠大的來者直接信任,零額外 IO
      AND disk.Status == full
      AND disk.InhabitedTime >= max(24,000(20 分鐘), incoming.InhabitedTime × 50)
```

- **成本與怪物 chunk**:只有「里程 < 1 小時的 full 存檔」需要讀一次硬碟基準;基準不走整棵
  NBT 解壓(實測 1144 箱怪物 chunk 全樹解壓需 >876MB,自己就會 OOM——導致讀不到基準而放行,
  **最值錢的倉庫反而防不住**,此盲點是實作期測試逼出來的),改用 **64KB 視窗串流掃描**位元組
  簽名直接取出 InhabitedTime 與 Status:記憶體有界、任何大小的 chunk(含外部 `.mcc`)都讀得動。
  低記憶體(free < `lowHeapMB`)時整個檢查仍自動跳過(fail-open)——假 full 的存檔發生在記憶體
  恢復之後,不衝突。
- **fail 方向**:讀不到里程、讀不到硬碟基準 → 一律放行。誤擋的最壞後果仍然只是「這次沒存」+
  一行說明原因的 BLOCKED log。
- **關閉時機**(第三章盤點的衝突面,原文保留於上):裝了整格重生成類外掛(海島/礦區/資源世界
  重置)或難度重置類外掛的伺服器,設 `-Dchunkguard.inhabitedGuard=false`;status 鐵則不受影響。
- 新增計數器 `fakeFullBlocked`;shadow 模式同樣適用(只記錄不攔,可先試跑觀察)。

### 實測(verify server, Paper 26.2)

以 9,000 萬 ticks(=1,250 小時)高里程的怪物 chunk(1144 箱/17,160 物品、外部 `.mcc`)確定性
製造假 full 存檔(測試外掛把載入後的記憶體副本里程歸零並標髒——正是冒充者的定義性狀態):

- **guard ON**:`BLOCKED corrupting write [moonrise] chunk(375,375) incoming=full disk=full
  inhabited=0<90000000 (mileage regression)`——硬碟怪物 chunk 位元組級保住;其中 `disk=full` 與
  `90000000` 由串流掃描從 10.5MB 外部 `.mcc` 讀出,證明怪物 chunk 路徑可用。
- **guard OFF 對照**:同一劇本假 full 落地(digest 改變)——旗標可完全回復舊行為。
- 兩輪 boot 全程其他 chunk 存檔零誤擋。
- 測試套件新增 `holdchunk` / `releasechunk` / `setmileage` 指令,此劇本任何人可重現
  (見 [`test-harness/`](../test-harness/))。

### 事後稽核的配套

agent 擋的是「未來的」假 full;「已經躺在硬碟裡的」歷史假 full 由掃描端負責:比對 live 與
事發當日凍結備份的里程,live 趨近零而備份很大即冒充者(上表 3 個暗傷正是這樣挖出來的)。
兩層各司其職:事前攔截(agent)+事後稽核(掃描)。

## 六、同一支驗鈔燈的第三個用途:讀取防線(26.2-3)

里程的單調性還能反過來用。寫入端抓的是「新車冒充老屋」(full 但里程近零);讀取端抓的是
**「老屋被貼錯門牌」**:一個 status 停在半成品(step 1-10)的硬碟 chunk,卻帶著幾百小時的里程——
**合法半成品的里程恆為 0**(源碼實證:只有 ticking 中的 full chunk 會累積,`ServerChunkCache`
tick 迴圈),所以這種組合零誤判空間地等於「曾經是 full 的屍體」(壞寫入/位元腐蝕/agent 部署前
的舊傷)。這種屍體被讀到時,worldgen 會以為它還沒生成完、**從壞掉的那一步續跑**,把殘存的真
資料滅掉——引爆點在「讀取」。

26.2-3 的讀取防線(`-Dchunkguard.readGuard`,預設開啟)在 `SerializableChunkData.parse` 入口
攔截:屍體且**內容完整**(sections 健全)→ 把 Status 原地治癒回 `full` 再交給遊戲,資料當場
生還;內容不完整(真空殼)→ 治不了,只大聲告警(`readGuardAlerts`)留給備份還原;任何不確定
→ 照原版走。實測:9,000 萬 ticks 里程的 1144 箱怪物 chunk 標籤改壞成 `biomes`——防線開,
`READ-GUARD HEALED biomes → full`、1144 箱完整生還;防線關(對照),原版從 biomes 續跑、載入
卡死、屍體不可用。

至此同一個單調量守住三個門:寫入端擋半成品蓋 full(status 單調)、寫入端擋假 full 蓋真 full
(里程單調)、讀取端救貼錯標籤的屍體(里程單調的逆向應用)。

---

*ChunkGuardAgent — crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net*
