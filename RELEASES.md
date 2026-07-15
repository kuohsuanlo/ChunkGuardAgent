# Releases / 版本下載

預編譯好的 agent jar 放在 [`dist/`](dist/)，下載即用，不必自己 build。
Prebuilt agent jars live in [`dist/`](dist/) — download and use, no build required.

## 目前版本 / Current release

| | |
|---|---|
| **版本 Version** | `26.2-1` |
| **檔案 File** | [`dist/ChunkGuardAgent-26.2-1.jar`](dist/ChunkGuardAgent-26.2-1.jar) |
| **MD5** | `b02b4cfae60c22cf2e91656b42f9813f` |
| **SHA-256** | `6201e25ad1f06a4e4d4bde10a8d55ded0deec3fc9d051cfe409c9ac9b6dbf82a` |
| **驗證對象 Validated on** | Paper 26.2 (JDK 25) |
| **Bytecode target** | Java 21（純 JDK + relocated ASM，零 NMS 編譯依賴 / pure JDK + relocated ASM, no compile-time NMS dependency） |
| **發布日期 Date** | 2026-07-15 |

> **這顆 jar 就是驗證報告裡的那顆。** [`docs/chunkguard-validation-logs/RESULTS.txt`](docs/chunkguard-validation-logs/RESULTS.txt)
> 記錄的 `agent rebuilt md5=b02b4cfa` 與上表 MD5 一致 —— 你下載的二進位檔，位元組層級等同於
> 通過 4/4 保全驗證與 shadow A/B 對照的那顆。
> **This jar is the exact binary from the validation run**: the `md5=b02b4cfa` recorded in
> `RESULTS.txt` matches the checksum above, so the download is byte-identical to the build
> that passed the 4/4 preservation runs and the shadow A/B control. See
> [`docs/VALIDATION.md`](docs/VALIDATION.md) for the full report.

## 安裝 / Install

**試跑但不影響區塊**（shadow 模式：只記錄、不攔截，行為與沒裝時完全相同，建議先跑幾天）
**Trial run, zero impact** (shadow mode: detect-only, recommended for the first days):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-1.jar -Dchunkguard.shadow=true -jar paper-26.2.jar nogui
```

**真的阻擋區塊毀損**（正式啟用）/ **Actually block chunk corruption** (production):

```bash
java -Xms4G -Xmx4G -javaagent:ChunkGuardAgent-26.2-1.jar -jar paper-26.2.jar nogui
```

`-Xms4G -Xmx4G` 換成你原本的記憶體設定；已有啟動腳本的話，只要在 `java` 後面插入
`-javaagent:ChunkGuardAgent-26.2-1.jar`，其他參數照舊。重啟生效。
Swap the heap flags for your own; with an existing start script, just insert the
`-javaagent:` part after `java` and keep everything else. Restart to arm.

| system property | 預設 default | 說明 / description |
|---|---|---|
| `chunkguard.enabled` | `true` | 總開關 / master switch |
| `chunkguard.shadow` | `false` | 只偵測不攔，印「本來會擋」但放行 / detect-only |
| `chunkguard.verbose` | `false` | 每 60 秒印計數 / print counters every 60s |
| `chunkguard.lowHeapMB` | `192` | free heap 低於此值改走零解壓 fail-safe 判定 / low-heap fail-safe threshold |

完整說明見 [`README.md`](README.md)。

## 版本紀錄 / Changelog

### 26.2-1 — 2026-07-15

首個公開版本。First public release.

- **寫入屏障 write barrier**：instrument `RegionFileStorage`（moonrise `finishWrite` 主閘門 + vanilla `write` fallback），載入失敗的空白 proto-chunk 不再存回蓋掉硬碟上完整的 chunk。
  Blocks a load-failed blank proto-chunk from being saved over a good `full` chunk on disk.
- **零誤殺鐵則 iron rule**：只在 `incoming != full` **且** `disk == full` 時攔；chunk status 只前進不倒退，玩家自己挖空的 chunk 永遠是 `full`，不會被誤攔。其餘一律 fail-open 放行。
- **低記憶體 fail-safe**：毀損正是在 heap 見底時發生，此時解壓硬碟 chunk 比對也會 OOM。free heap 低於 `lowHeapMB` 或讀取失敗時，改用只讀 region header（8 KB、零解壓）的存在性檢查，**存在即攔（fail-safe）**。
  Under low heap the decompress-and-compare itself would OOM; the agent falls back to a header-only existence check and blocks (fail-safe) instead of failing open.
- **驗證 validation**：確定性重現 `chunk data will be lost` 後連續 4/4 攔截成功、目標 chunk（1144 chests / 17,160 items）位元組級完整；shadow 對照組證明不攔就會被蓋成空殼。另在真實遊玩負載下累計 inspected 21,022 次、零誤殺、零反射錯誤。`lowHeapMB` 門檻捷徑亦在真實低記憶體（free 111MB < 192）下歸因實證觸發。詳見 [`docs/VALIDATION.md`](docs/VALIDATION.md)（歸因矩陣在 Appendix D）。
