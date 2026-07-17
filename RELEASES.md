# Releases / 版本下載

預編譯好的 agent jar 放在 [`dist/`](dist/)，下載即用，不必自己 build。
Prebuilt agent jars live in [`dist/`](dist/) — download and use, no build required.

## 目前版本 / Current release

| | |
|---|---|
| **版本 Version** | `26.2-3` |
| **檔案 File** | [`dist/ChunkGuardAgent-26.2-3.jar`](dist/ChunkGuardAgent-26.2-3.jar) |
| **MD5** | `0aca6dc163cff7b1ef80e3b288cd4c34` |
| **SHA-256** | `56fa09174d40aa8f7dff02c1a66f3e1626732e31dc7f7dd3110dd0f7af569cae` |
| **驗證對象 Validated on** | Paper 26.2 (JDK 25) |
| **Bytecode target** | Java 21（純 JDK + relocated ASM，零 NMS 編譯依賴 / pure JDK + relocated ASM, no compile-time NMS dependency） |
| **發布日期 Date** | 2026-07-17 |

（歷史版本保留於 dist/：26.2-2 MD5 `7552d3a7319463989d8a65b036e0bb6e`、26.2-1 MD5 `b02b4cfae60c22cf2e91656b42f9813f`。）

> **出處鏈 provenance**:26.2-1(`md5=b02b4cfa`)是 [`RESULTS.txt`](docs/chunkguard-validation-logs/RESULTS.txt)
> 記錄的 4/4 保全驗證原顆二進位檔;26.2-2 增加里程防護、26.2-3 增加讀取防線,status 鐵則與
> failsafe 路徑程式碼未變,各自通過獨立實測(見 Changelog)。

## 安裝 / Install

**試跑但不影響區塊**（shadow 模式：只記錄、不攔截，行為與沒裝時完全相同，建議先跑幾天）
**Trial run, zero impact** (shadow mode: detect-only, recommended for the first days):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-3.jar -Dchunkguard.shadow=true -jar paper-26.2.jar nogui
```

**真的阻擋區塊毀損**（正式啟用）/ **Actually block chunk corruption** (production):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-3.jar -jar paper-26.2.jar nogui
```

`-Xms4G -Xmx4G` 換成你原本的記憶體設定；已有啟動腳本的話，只要在 `java` 後面插入
`-javaagent:ChunkGuardAgent-26.2-3.jar`，其他參數照舊。重啟生效。
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
