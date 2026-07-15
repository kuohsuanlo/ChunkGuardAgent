# InhabitedTime「里程表」規則 — 一個蓄意保留不做的功能

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

---

*ChunkGuardAgent — crafted by 廢土貓大 LogoCat · 廢土 · mcfallout.net*
