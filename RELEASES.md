# Releases / 版本下載

預編譯好的 agent jar 放在 [`dist/`](dist/)，下載即用，不必自己 build。
Prebuilt agent jars live in [`dist/`](dist/) — download and use, no build required.

## 目前版本 / Current release

| | |
|---|---|
| **版本 Version** | `26.2-5` ⚠️ **重大修復,強烈建議立即升級** |
| **檔案 File** | [`dist/ChunkGuardAgent-26.2-5.jar`](dist/ChunkGuardAgent-26.2-5.jar) |
| **MD5** | `7f00ae68be9d7010eef120b2ff40516c` |
| **SHA-256** | `6655a226256078e62f815ba0055c038b8681cc4010402d91ce67dabc8b9b2d42` |
| **驗證對象 Validated on** | Paper 26.2 (JDK 25) |
| **Bytecode target** | Java 21（純 JDK + relocated ASM，零 NMS 編譯依賴） |
| **發布日期 Date** | 2026-08-16 |

> 🔴 **26.2-4 以前的所有版本都會在低記憶體時丟棄實體/POI 存檔**（見 Changelog 26.2-5）。
> 若你正在跑舊版，請升級；無法立即升級的止血：`-Dchunkguard.lowHeapMB=0`
> （保留 terrain 保護、entities 放行）或 `-Dchunkguard.enabled=false`（全關）。

（歷史版本保留於 dist/：26.2-4 `24c347e7571d10c6a2a8fc54ae1baae0`、26.2-3 `0aca6dc163cff7b1ef80e3b288cd4c34`、26.2-2 `7552d3a7319463989d8a65b036e0bb6e`、26.2-1 `b02b4cfae60c22cf2e91656b42f9813f`。）

## 安裝 / Install

**試跑但不影響區塊**（shadow 模式：只記錄、不攔截，行為與沒裝時完全相同，建議先跑幾天）
**Trial run, zero impact** (shadow mode: detect-only, recommended for the first days):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-5.jar -Dchunkguard.shadow=true -jar paper-26.2.jar nogui
```

**真的阻擋區塊毀損**（正式啟用）/ **Actually block chunk corruption** (production):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-5.jar -jar paper-26.2.jar nogui
```

`-Xms4G -Xmx4G` 換成你原本的記憶體設定；已有啟動腳本的話，只要在 `java` 後面插入
`-javaagent:ChunkGuardAgent-26.2-5.jar`，其他參數照舊。重啟生效。
Swap the heap flags for your own; with an existing start script, just insert the
`-javaagent:` part after `java` and keep everything else. Restart to arm.

| system property | 預設 default | 說明 / description |
|---|---|---|
| `chunkguard.enabled` | `true` | 總開關 / master switch |
| `chunkguard.shadow` | `false` | 只偵測不攔，印「本來會擋」但放行 / detect-only |
| `chunkguard.verbose` | `false` | 每 60 秒印計數 / print counters every 60s |
| `chunkguard.lowHeapMB` | `192` | free heap 低於此值改走零解壓 fail-safe 判定 / low-heap fail-safe threshold |
| `chunkguard.inhabitedGuard` | `true` | 里程倒退檢查，擋重生成假 full / mileage-regression fake-full guard |
| `chunkguard.readGuard` | `true` | 讀取防線：治癒貼錯標籤的屍體 chunk / READ-side heal for mislabeled ex-full chunks |

完整說明見 [`README.md`](README.md)。

## 版本紀錄 / Changelog

### 26.2-5 — 2026-08-16 🔴 重大修復：停止丟棄實體/POI 存檔

**26.2-4 以前的所有版本都有此缺陷。** 正式環境代價：13 台分流命中、單台最高 1,598 次丟棄，
且**全機隊 100% 的攔截都是這個誤判**——那條「真的讀過磁碟、驗證過內容」的攔截路徑一次都沒觸發過。

- **根因**：攔截點 `RegionFileStorage` **同時被實體與 POI 儲存共用**
  （`EntityStorage → SimpleRegionStorage → RegionFileStorage`），而實體/POI 的根標籤**本來就沒有
  `Status` 欄位**。`NbtReflect.statusOf()` 讀不到 Status 時回傳**空字串**而非 `null`，`decide()`
  只在 `null` 時提早放行 ⇒ `"" != "full"` ⇒ 每筆實體/POI 存檔都被當成「非-full 的 terrain chunk」，
  在 free heap < `lowHeapMB` 時走保險絲、只確認「檔案存在」就**丟棄整筆寫入**。
  症狀簽名：`BLOCKED … chunk(x,z) incoming= disk=exists(failsafe)`（`incoming=` 後面是空的）。
- **最糟的時機**：這條保險絲設計成「偶爾」觸發，但在記憶體吃緊的分流上是常態——伺服器正因為
  存不完而窒息、最需要保住資料時，它把存檔丟了（生產實證：OOM 觸發後 3 秒、watchdog 堆疊正卡在
  `ChunkHolderManager.autoSave → ChestBlockEntity.saveAdditional` 的卸載存檔路徑）。
- **修法**：①`statusOf()` 找不到 Status → 回 **`null`**（= 不是 terrain chunk／讀不出來 → 屏障
  完全不介入）②低記憶體時不再盲目「檔案存在就擋」，改用**有界串流掃描**讀出硬碟實際 status，
  只有 `disk=full` 才攔；掃不到時僅在「incoming 確實是空殼（sections<4）」才做保守攔截，
  讀不到 sections 一律放行 ③訊息不再對未讀取磁碟的路徑宣稱 `kept good disk data`
  ④新增計數器 `nonTerrainAllowed`（實體/POI 正確放行數，可在 log 直接確認修復生效）。
- **驗證**：新增離線回歸測試 [`test-harness/regression/Issue180Test.java`](test-harness/regression/)，
  10 項全綠；同一支測試對 26.2-4 執行會在「statusOf 回 null」與「entities 零接觸硬碟」兩項失敗
  （證明測試具鑑別力）。terrain 判定路徑（proto 仍查磁碟、高里程 full 快速放行）無回歸。
  ⚠️ 驗證邊界：單元環境無法模擬「實際丟棄」那一步（需真 NMS ChunkPos/RegionFile），該環節由
  正式環境 log 佐證。
- 感謝紅隊審查交付 #155 / #180 的 bytecode 逐條 + 生產日誌互證分析。

### 26.2-4 — 2026-07-18

**開機上膛加固 arming hardening**（修正正式站觀測到的靜默失效）：

- 根因:transformer 的 `transform()` 對「每一個」載入的類都會被呼叫,26.2-3 把 `ChunkGuardRuntime.enabled()` 檢查放在類名比對之前——於是在 Runtime 自身(或與其初始化交錯的類)載入期間觸發 `ClassCircularityError`,例外落在 try 之外被 JVM 吞掉、`RegionFileStorage` 保持 vanilla = **寫入屏障靜默沒掛上**(s21 2026-07-17 案:重啟後 write barrier 未 arm,讀取防線正常)。
- 修法:①`transform()` 類名檢查移到最前面,非目標類零接觸 `ChunkGuardRuntime`;②premain 先 `preloadOwnClasses()` 強制初始化全部自家類,確保沒有 agent 類會「透過 transformer」載入;③`isTarget()` 改 public(跨 app/bootstrap classloader 存取,package-private 會拋 `IllegalAccessError`——動態 attach 既有隱藏地雷,一併修)。
- 實測:連續 5 次冷開機,每次「寫入屏障 + 讀取防線」雙雙 armed、零 `ClassCircularityError`、伺服器正常啟動(5/5)。
- 純啟動路徑加固;判定邏輯(status 鐵則 / 里程防護 / 讀取治癒)與計數器一字未動。


### 26.2-3 — 2026-07-17

**讀取防線 READ-GUARD**（`-Dchunkguard.readGuard`，預設開啟 / default ON）：

- 寫入端管不到「已躺在硬碟上的舊地雷」：status 停在半成品(step 1-10)的損毀 chunk 被讀到時，worldgen 會**從那一步續跑生成**、把殘存資料滅掉。讀取防線在 `SerializableChunkData.parse` 入口攔截：發現「半成品 status 卻帶里程」（合法半成品里程恆為 0——源碼實證只有 ticking full chunk 會累積）且內容完整（sections 健全）→ **把 Status 治癒回 full 再交給遊戲**，資料當場生還。
  READ-side heal: a proto-status chunk carrying InhabitedTime is the corpse of an ex-full chunk (legit protos always carry zero). If its content is intact, Status is rewritten to full before parse so the game loads the data instead of regenerating over it.
- 內容不完整的屍體治不了 → 只大聲告警（`readGuardAlerts`），交給備份還原。任何不確定照原版走。新增計數器 `readGuardHealed` / `readGuardAlerts`；shadow 模式只記錄不治癒。
- **實測**：9,000 萬 ticks 里程的 1144 箱怪物 chunk 標籤改壞成 `biomes`——防線開：`READ-GUARD HEALED biomes → full`、1144 箱完整生還、存檔後硬碟恢復 full；防線關（對照）：原版從 biomes 續跑、載入卡死、屍體不可用。兩輪其他 chunk 載入零誤報。


### 26.2-2 — 2026-07-17

**里程防護 InhabitedTime guard**（`-Dchunkguard.inhabitedGuard`，預設開啟 / default ON）：

- 新增第二條單調性規則：擋「載入失敗後被倖存伺服器**重生成的假 full**」蓋掉有人住過的真 chunk——status 鐵則看不見這種冒充者（雙方都是 `full`、尺寸正常），只有里程（InhabitedTime，只增不減、跟著資料走）能拆穿。
  Second monotonicity rule: blocks a regenerated **fake-full** impostor (load-failed chunk the surviving server regenerated) from overwriting a lived-in chunk. Only mileage tells them apart.
- 觸發門檻保守：`incoming full 且里程 < 1 小時`＋`disk full 且里程 ≥ max(20 分鐘, 來者×50)`；低記憶體自動跳過；讀不到一律放行。新增計數器 `fakeFullBlocked`。
- 動機：正式環境兩週內實證 4 例假 full 受害者（詳見 [`docs/INHABITED-TIME.md`](docs/INHABITED-TIME.md) 第五章——含當初「蓄意不做」的完整推演與決策反轉紀錄）。
- **串流掃描 streaming scan**：硬碟基準的里程/status 用 64KB 視窗邊解壓邊搜位元組簽名讀取——**不建 NBT 樹、記憶體有界**。怪物 chunk（實測 1144 箱者全樹解壓需 >876MB）整棵解壓會 OOM 導致讀不到基準而放行——正是最值錢的倉庫反而防不住；串流掃描讓任何大小的 chunk（含外部 `.mcc`）都讀得動，agent 也永遠不會成為 OOM 幫兇。
  Disk baseline is read via a bounded 64KB streaming byte-signature scan (no NBT tree) — works on monster chunks (incl. external `.mcc`) whose full decompress would OOM.
- **實測 validation**：以 9,000 萬 ticks 高里程怪物 chunk（1144 箱、外部 .mcc）製造假 full 存檔：guard ON → `BLOCKED ... inhabited=0<90000000 (mileage regression)`、chunk 位元組級保住；guard OFF 對照 → 假 full 落地（digest 改變）。兩輪全程其他存檔零誤擋。測試套件新增 `holdchunk`/`releasechunk`/`setmileage` 指令使此劇本可確定性重現。
- 裝了會合法歸零里程的外掛（海島/礦區重置、難度重置類）請設 `false` 停用本檢查。
  Disable with `-Dchunkguard.inhabitedGuard=false` if you run chunk-regen / difficulty-reset plugins.

### 26.2-1 — 2026-07-15

首個公開版本。First public release.

- **寫入屏障 write barrier**：instrument `RegionFileStorage`（moonrise `finishWrite` 主閘門 + vanilla `write` fallback），載入失敗的空白 proto-chunk 不再存回蓋掉硬碟上完整的 chunk。
  Blocks a load-failed blank proto-chunk from being saved over a good `full` chunk on disk.
- **零誤殺鐵則 iron rule**：只在 `incoming != full` **且** `disk == full` 時攔；chunk status 只前進不倒退，玩家自己挖空的 chunk 永遠是 `full`，不會被誤攔。其餘一律 fail-open 放行。
- **低記憶體 fail-safe**：毀損正是在 heap 見底時發生，此時解壓硬碟 chunk 比對也會 OOM。free heap 低於 `lowHeapMB` 或讀取失敗時，改用只讀 region header（8 KB、零解壓）的存在性檢查，**存在即攔（fail-safe）**。
  Under low heap the decompress-and-compare itself would OOM; the agent falls back to a header-only existence check and blocks (fail-safe) instead of failing open.
- **驗證 validation**：確定性重現 `chunk data will be lost` 後連續 4/4 攔截成功、目標 chunk（1144 chests / 17,160 items）位元組級完整；shadow 對照組證明不攔就會被蓋成空殼。另在真實遊玩負載下累計 inspected 21,022 次、零誤殺、零反射錯誤。`lowHeapMB` 門檻捷徑亦在真實低記憶體（free 111MB < 192）下歸因實證觸發。詳見 [`docs/VALIDATION.md`](docs/VALIDATION.md)（歸因矩陣在 Appendix D）。
