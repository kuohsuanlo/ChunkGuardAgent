# regression/ — 離線回歸測試(不需要 Minecraft 伺服器)

用假的 CompoundTag / ChunkPos / storage 直接驅動真實的 `NbtReflect` + `ChunkGuardRuntime`,
驗證判定路徑。跑一次只要幾秒,改動 decide()/statusOf() 後務必跑。

## Issue180Test — 實體/POI 存檔不得被寫入屏障碰觸

背景:`RegionFileStorage` 被 terrain **與 entities/POI 儲存共用**
(`EntityStorage → SimpleRegionStorage → RegionFileStorage`),而實體/POI 根標籤沒有 `Status`。
26.2-4 以前 `statusOf()` 讀不到 Status 時回空字串,導致每筆實體存檔都被當成「非-full terrain
chunk」,低記憶體時被丟棄(正式環境 13 台命中、單台 1,598 次)。

```bash
JDK=/path/to/jdk25
$JDK/bin/javac -cp ../../target/classes -d /tmp/cgtest Issue180Test.java
$JDK/bin/java -cp "../../target/classes:/tmp/cgtest" \
    -Dchunkguard.lowHeapMB=999999 io.github.kuohsuanlo.chunkguard.Issue180Test
```

`-Dchunkguard.lowHeapMB=999999` 強制走低記憶體分支(bug 現形的那條)。10 項全綠才算過。
拿舊 jar 當 classpath 可驗證此測試的鑑別力(26.2-4 會在第 1、5 項失敗)。

**驗證邊界**:本測試證明根因(`statusOf` 回傳值)與判斷路徑(entities 零接觸硬碟);
「實際丟棄寫入」那一步在單元環境無法完整模擬(需要真的 NMS ChunkPos/RegionFile),
該環節由正式環境 log 佐證(`BLOCKED … incoming= disk=exists(failsafe)`)。
