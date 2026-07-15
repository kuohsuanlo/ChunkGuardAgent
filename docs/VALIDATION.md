# ChunkGuardAgent — Reproduction & Validation Report / 重現與驗證報告

**Date:** 2026-07-15 · **Env:** Paper 26.2 (26.2-26-dev, Moonrise chunk system), JDK 25, `-Xmx1200m` ·
**Agent jar md5:** `b02b4cfa` · **Target chunk:** overworld `(375,375)` = a deliberately heavy chunk
(**1144 chests / 17160 items**, stored external `.mcc`).

> TL;DR — We reproduced the real OOM‑during‑chunk‑load corruption deterministically, discovered that the
> **original guard was defeated by the very OOM it exists to catch** (it fail‑opened), fixed it with a
> memory‑free check, and re‑validated: **4/4 consecutive runs blocked the corrupting write and left the
> chunk byte‑identical**, while the **guard‑off control clobbered all 1144 chests to a blank stub**.

| Condition | Guard | `chunk will be lost`? | Corrupting write | On‑disk chunk after | Result |
|---|---|:--:|---|---|:--:|
| **real ×4** (enabled) | ON | yes | **BLOCKED** | `full`, **1144 chests, byte‑identical** | ✅ preserved |
| **shadow** (detect‑only) | detect | yes | allowed (logged only) | `structure_starts`, **0 chests** | ❌ clobbered |

Raw logs this report cites are included in this repo under
[`chunkguard-validation-logs/`](chunkguard-validation-logs/) (`r-real.log`, `r-shadow.log`, `r-final.log`,
`RESULTS.txt`, `DIAG.txt`, and the threshold-attribution runs `r-lowheap-*.log`); key excerpts with
line numbers are in the Appendix.

---

## 中文

### 白話人類版（給任何人）

伺服器記憶體爆掉（OOM）的那一刻，一個正在被讀取的區塊（chunk）會因為配不到記憶體而**讀失敗**，
遊戲就拿一個**空白替代品**頂上；接著這個空白被**存回硬碟，蓋掉原本的好資料**——玩家的建築就沒了。

我們用一格**塞了 1144 個帶字箱子**的重區塊，把記憶體逼到只剩 40MB，故意重現這個災難：log 真的噴出
`chunk data will be lost`，而且那格被重新生成為半成品（`structure_starts`）準備蓋回去。

**我們發現一件關鍵的事：原本的 ChunkGuard 擋不住。** 因為它要「解壓硬碟上那格來比對」才能判斷，
但解壓也要記憶體——災難正好發生在記憶體用光時，所以它自己也 OOM，然後就「放行」了。**等於被同一
場 OOM 廢掉。**

**修法：** 改成不用解壓、只讀硬碟的目錄表（8KB header）問一句「這格本來就有資料嗎？」。有、而且來的
是半成品 → 就擋下（寧可擋錯也不放行）。修好後連跑 4 次：**每次都擋下、那 1144 個箱子一個都沒少**；
把 guard 關掉當對照組，同一招就把 1144 箱清成 0——黑白分明。

### 技術版（給工程師）

**重現路徑**（`ChunkGuardTestKit` 測試外掛 `/reloadchunk`）：填 heap 到 headroom 40MB → 對重 chunk 呼叫
`ServerChunkCache.getChunk(x,z,STRUCTURE_STARTS,true)` → off‑main 的 `MoonriseRegionFileIO` 在
`NbtIo.read`/`CompoundTag.load`（fastutil hashmap）處 `OutOfMemoryError` → Moonrise catch → 記
`chunk data will be lost` → 頂替空 `ProtoChunk` → 被 ticket 逼到 `structure_starts` 並 `markUnsaved()` →
shutdown save 嘗試寫回 = 非‑full 蓋 full 的真毀損。

**發現的缺陷：** ChunkGuard 原本在 `finishWrite` 判斷時呼叫 `readDisk()`（= `RegionFileStorage.read` →
`NbtIo.read` **解壓整個** chunk 比對 Status）。但毀損發生在 heap 枯竭時，`readDisk` 自己就 `OutOfMemoryError`
→ 反射層 catch → 回 null → `decide()` **fail‑open 放行**。log 佐證：寫入當下整台在 OOM‑spiral（RCON/tick
皆 OOM）。**守門的判斷依賴了正被耗盡的資源。**

**修法（現行版本已含）：** 新增 `NbtReflect.diskChunkExists()`，用
`RegionFileStorage.moonrise$getRegionFileIfLoaded/IfExists` + `RegionFile.hasChunk`——**只讀 region header
（~8KB，零解壓）**。`decide()` 改為：incoming 非‑full 時，若 free heap < `LOW_HEAP_MB`（預設 192，
`-Dchunkguard.lowHeapMB` 可調）**或** `readDisk` 回 null → 走 existence check → 該座標**已有 chunk 就
fail‑SAFE（擋）**，而非 fail‑open。新增計數器 `lowHeapFailsafe`。最壞情況（真的 worldgen 中途的 chunk 在
記憶體壓力下被擋）= 該格保留原狀、下次載入重生成，無資料遺失。

**結果：** `real` 連 4 次 `lost=1 blocked=1 cmatch=yes cstatus=minecraft:full`（目標 chunk payload md5 與
baseline 完全相同、1144 chests / 17160 items 不變）；擋下走的是新路徑（log `disk=exists(failsafe)`、
counter `skipped=1 lowHeapFailsafe=1 inspectErrors=0`）。`shadow` 對照：`SHADOW would-skip` 但放行 →
chunk 被蓋成 `structure_starts` / 0 block_entities（`cmatch=NO`）。

**門檻分支歸因（後續補測）：** `disk=exists(failsafe)` 有兩個入口——free heap < `lowHeapMB` 的**捷徑**、
與 `readDisk` 回 null 的**殊途**——log 字樣相同無法區分，故以只加歸因輸出的 debug build 重跑歸因
（見 Appendix D）。結論：真實記憶體壓力下（free = 111MB < 預設 192）**門檻捷徑如設計觸發**、擋下、
chunk 位元組級完好；同時解開懸案——歷次驗證走 failsafe 的真因是**解壓 1144 箱重 chunk 的 NBT 樹
即使在 free ≈ 876MB 下也會 OOM**（`readDisk null-cause=threw OutOfMemoryError`）。free heap 估計
說「安全」時解壓照樣會炸——所以兩個入口互為保險，且各自都已獨立實證。

### AI 閱讀版（結構化）

```yaml
experiment: chunkguard-oom-corruption-repro-and-fix
date: 2026-07-15
env: {server: paper-26.2-26-dev, chunk_system: moonrise, jdk: 25, heap: -Xmx1200m}
target_chunk: {pos: [375,375], dim: overworld, status_baseline: full, block_entities: 1144,
               item_ids: {minecraft:chest: 1144}, total_items: 17160, storage: external_mcc,
               payload_md5: 8984b154135e...}
reproduction:
  trigger: ChunkGuardTestKit /reloadchunk <cx> <cz> <headroomMB>
  mechanism: fill heap to ~40MB free; getChunk(x,z,STRUCTURE_STARTS,true) forces disk read+parse
  oom_site: off-main MoonriseRegionFileIO -> NbtIo.read/CompoundTag.load (fastutil) -> OutOfMemoryError
  result: "chunk data will be lost" -> empty ProtoChunk -> regen to structure_starts -> markUnsaved
  corrupting_write: non-full(structure_starts) over full(1144-chest) at shutdown save
defect_found:
  where: ChunkGuardRuntime.decide -> NbtReflect.readDisk (RegionFileStorage.read -> NbtIo.read, DECOMPRESSES)
  failure_mode: readDisk needs heap; corruption occurs at heap exhaustion -> readDisk OOMs -> caught ->
                null -> decide() FAILS OPEN -> corrupting write allowed
  evidence: server OOM-spiral at write time (RCON OOM, tick ReportedException OOM)
fix:
  add: NbtReflect.diskChunkExists() via moonrise$getRegionFileIfLoaded/IfExists + RegionFile.hasChunk
       (region header only, NO decompress, allocation-light)
  decide_change: non-full incoming AND (freeHeapMB < LOW_HEAP_MB OR readDisk==null)
                 -> if chunk exists on disk -> SKIP (fail-SAFE) else ALLOW
  tunable: -Dchunkguard.lowHeapMB (default 192)
  counter_added: lowHeapFailsafe
validation:
  real_runs: 4 consecutive; each {lost: 1, blocked: 1, cmatch: yes, cstatus: minecraft:full, block_entities: 1144}
  block_path: "disk=exists(failsafe)"  counters: {skipped: 1, lowHeapFailsafe: 1, inspectErrors: 0}
  content_check: final on-disk chunk byte-identical to baseline (1144 chests / 17160 items)
  shadow_control: {blocked: 0, would_skip: 1, cstatus: minecraft:structure_starts, block_entities: 0, cmatch: NO}
  threshold_attribution:  # Appendix D — debug build w/ flag-gated path prints; release jar unchanged
    forced_shortcut: {lowHeapMB: 999999, free_mb_at_decide: 876, entry: lowheap-shortcut, blocked: 1, cmatch: yes}
    genuine_trigger: {lowHeapMB: 192, free_mb_at_decide: 111, entry: lowheap-shortcut, blocked: 1, cmatch: yes}
    fallthrough_cause: decompressing the 1144-chest chunk OOMs even at ~876MB free -> readDisk null -> same failsafe
verdict: reproduced=true, guard_effective_after_fix=true, content_preserved=byte_identical (4/4)
```

---

## English

### Layperson version

When a server runs out of memory (OOM), a chunk being **loaded** can fail to allocate memory, so the game
substitutes a **blank placeholder** — which then gets **saved back over the good data on disk**. The
player's builds are gone.

Using a deliberately heavy chunk (**1144 lore‑stuffed chests**) squeezed down to 40MB of free heap, we
reproduced this on purpose: the log really printed `chunk data will be lost`, and that chunk was about to
overwrite the good copy with a half‑generated stub.

**We found the guard didn't work.** To decide, it had to *decompress the on‑disk chunk* — but decompressing
also needs memory, and the disaster happens exactly when memory is gone, so the guard OOM'd too and then
**let the bad write through. It was defeated by the same OOM it was built to stop.**

**The fix:** instead of decompressing, just read the disk's tiny 8KB index and ask "did a chunk already
exist here?" If yes and the incoming write is a stub → block it (fail safe, not fail open). After the fix,
4 runs in a row blocked it and **all 1144 chests survived intact**; with the guard off, the same attack
wiped 1144 chests down to 0. Night and day.

### Technical version

**Reproduction** (`ChunkGuardTestKit` `/reloadchunk`): fill heap to a 40MB headroom → call
`ServerChunkCache.getChunk(x,z,STRUCTURE_STARTS,true)` on the heavy chunk → off‑main `MoonriseRegionFileIO`
hits `OutOfMemoryError` inside `NbtIo.read`/`CompoundTag.load` (fastutil hashmaps) → Moonrise catches, logs
`chunk data will be lost` → substitutes an empty `ProtoChunk` → a ticket drives it to `structure_starts`
and `markUnsaved()` → the shutdown save attempts a **non‑full‑over‑full** write = the corruption.

**Defect:** the guard's `decide()` called `readDisk()` = `RegionFileStorage.read` → `NbtIo.read`, which
**decompresses the whole chunk** to compare Status. But the write happens under heap exhaustion, so
`readDisk` itself OOMs → reflection catches → returns null → `decide()` **fails open** and allows the
corrupting write. The logs show the whole server in an OOM spiral at write time (RCON OOM, tick
`ReportedException`/OOM). **The guard's decision depended on the resource being exhausted.**

**Fix (included in the current release):** `NbtReflect.diskChunkExists()` uses
`RegionFileStorage.moonrise$getRegionFileIfLoaded/IfExists` + `RegionFile.hasChunk` — **region header only,
no decompress, allocation‑light**. `decide()` now: for a non‑full incoming write, if free heap
< `LOW_HEAP_MB` (default 192; `-Dchunkguard.lowHeapMB`) **or** `readDisk` returned null → use the existence
check → if a chunk already exists at that coordinate → **SKIP (fail‑safe)** instead of fail‑open. New
counter `lowHeapFailsafe`. Worst case (a genuinely mid‑worldgen chunk skipped under pressure): it is left
as‑is and regenerated on next load — no data loss.

**Result:** `real` × 4 consecutive → `lost=1 blocked=1 cmatch=yes cstatus=minecraft:full` (target chunk
payload md5 identical to baseline; 1144 chests / 17160 items intact); the block took the new path
(`disk=exists(failsafe)`, counters `skipped=1 lowHeapFailsafe=1 inspectErrors=0`). `shadow` control:
`SHADOW would-skip` but allowed → chunk overwritten to `structure_starts` / 0 block_entities (`cmatch=NO`),
proving the threat is real and the guard is what prevents it.

**Threshold-branch attribution (follow-up):** the `disk=exists(failsafe)` verdict has two entries —
the `freeHeap < lowHeapMB` **shortcut** and the `readDisk`-returned-null **fallthrough** — with
identical log text, so a debug build with flag-gated attribution prints re-ran the scenario
(Appendix D). Under genuine pressure (111 MB free < the default 192) **the threshold shortcut fired
as designed**, blocked the write, and left the chunk byte-identical. It also resolved why every
earlier run took the fallthrough: decompressing the 1144-chest chunk's NBT tree OOMs even at
~876 MB free (`readDisk null-cause=threw OutOfMemoryError`) — the heap estimate can say "safe"
while the decompress still dies. The two entries back each other up, and each is now proven
independently.

### AI‑oriented version

See the structured `AI 閱讀版（結構化）` YAML block above — it is language‑neutral and authoritative.
Key deltas for a model reasoning about the codebase:
`ChunkGuardRuntime.decide()` no longer relies solely on `NbtReflect.readDisk` (decompressing);
under `freeHeapMB() < LOW_HEAP_MB` or `readDisk==null` it consults `NbtReflect.diskChunkExists()`
(header‑only) and fails **safe**. Counter surface gains `lowHeapFailsafe`. Note that a decompressing
`readDisk` disk‑compare alone is unreliable precisely under the OOM the guard targets — hence the
header‑only fail‑safe path.

---

## Appendix — raw log excerpts (auditable)

> The `[cgtest]` prefix in these logs comes from the test-plugin build used during the validation run;
> the published `ChunkGuardTestKit` prints `[testkit]`. Same commands, same behaviour.

### A. `real` (guard ON) — BLOCKED, chunk preserved  · `r-real.log`
```
[01:08:38] [cgtest] heap free~=39MB (target 40MB); forcing reload of (375,375) ...  (line 86)
[01:08:40] [MoonriseRegionFileIO] Failed to read chunk data for task ... at (375,375)  (line 90)
           java.lang.OutOfMemoryError: Java heap space
           (at DataInputStream.readUTF / StringTag$1.load / CompoundTag.readNamedTagData)
[01:08:40] [ChunkLoadTask] ... chunk data will be lost                              (line 124)
[01:08:41] [cgtest] heap recovered: free~=879MB                                     (line 158)
[01:08:41] [cgtest] holder chunk class=ProtoChunk persistedStatus=minecraft:structure_starts isUnsaved(before)=true  (line 159)
[01:08:44] [ChunkGuard] BLOCKED corrupting write [moonrise] (kept good disk data)
             chunk(375,375) incoming=structure_starts disk=exists(failsafe)         (line 163)
[01:11:37] [ChunkGuard] inspected=82 skipped=1 shadowWouldSkip=0 allowedNewOrEmpty=0 lowHeapFailsafe=1 inspectErrors=0   (r-final.log line 171)
```

### B. `shadow` (guard detect‑only = OFF) — write ALLOWED, chunk clobbered  · `r-shadow.log`
```
[01:09:49] [cgtest] heap free~=40MB (target 40MB); forcing reload of (375,375) ...
[01:09:51] [MoonriseRegionFileIO] Failed to read chunk data ... OutOfMemoryError   (line 98)
[01:09:53] [cgtest] heap recovered: free~=880MB                                     (line 143)
[01:09:53] [cgtest] holder chunk ... structure_starts isUnsaved(before)=true        (line 144)
[01:09:56] [ChunkGuard] SHADOW would-skip corrupting write [moonrise]
             chunk(375,375) incoming=structure_starts disk=exists(failsafe)         (line 147)
[01:09:56] [ChunkHolderManager] Saved 1 block chunks ...  <- the stub WAS written   (line 176)
result: on-disk chunk(375,375) -> status=structure_starts block_entities=0 (cmatch=NO)
```

### C. Consecutive validation summary · condensed from `RESULTS.txt`
```
tune        : lost=1 blocked=1 cstatus=minecraft:full cbe=1144 csec=25 cmatch=yes
guard-check : lost=1 blocked=1 cstatus=minecraft:full cbe=1144 csec=25 cmatch=yes
validate #1 : lost=1 blocked=1 ... cbe=1144 cmatch=yes   -> PASS (1/4)
validate #2 : lost=1 blocked=1 ... cbe=1144 cmatch=yes   -> PASS (2/4)
validate #3 : lost=1 blocked=1 ... cbe=1144 cmatch=yes   -> PASS (3/4)
validate #4 : lost=1 blocked=1 ... cbe=1144 cmatch=yes   -> PASS (4/4)
shadow      : lost=0 blocked=0 would=1 cstatus=minecraft:structure_starts cbe=0 cmatch=NO
FINAL       : status=full block_entities=1144 total_items=17160  BYTE-IDENTICAL to baseline
              BASELINE: block_entities=1144 total_items=17160 ids={minecraft:chest: 1144}
              FINAL   : block_entities=1144 total_items=17160 ids={minecraft:chest: 1144}
SUMMARY: reproduced=yes validations_passed=4/4 content_preserved=yes
```

### D. `lowHeapMB` threshold attribution · `r-lowheap-*.log`

The `BLOCKED … disk=exists(failsafe)` verdict has two entries — the low-heap **shortcut**
(`freeHeap < lowHeapMB` skips the decompress entirely) and the `readDisk`-returned-null
**fallthrough** — and the release log line is identical for both. To attribute which entry fires,
these runs used a **debug build**: the release source plus flag-gated attribution prints
(`-Dchunkguard.debugPaths=true`; full delta below — no logic change, release jar untouched).
Every run reproduced the corruption, blocked the write, and left the chunk byte-identical.

| run · log | `lowHeapMB` | free heap at decide | entry fired |
|---|---|---|---|
| [`r-lowheap-default.log`](chunkguard-validation-logs/r-lowheap-default.log) | 192 (default) | 876 MB | `readDisk` → **OOM** → null → fail-safe (lines 164–167) |
| [`r-lowheap-forced.log`](chunkguard-validation-logs/r-lowheap-forced.log) | 999999 (forced) | 876 MB | **low-heap shortcut** (lines 175–176) |
| [`r-lowheap-genuine.log`](chunkguard-validation-logs/r-lowheap-genuine.log) | 192 (default) | **111 MB** | **low-heap shortcut — genuine trigger** (lines 178, 206, 209) |

```
r-lowheap-genuine.log (genuine pressure: heapfill queued right behind /reloadchunk on the console,
so the ~2s-later Moonrise autosave of the marked-unsaved proto lands inside the pressure window):
[21:35:06] [ChunkGuard-DBG] chunk(375,375) in=structure_starts freeHeapMB=111 threshold=192 entry=lowheap-shortcut   (line 178)
[21:35:35] [ChunkGuard] BLOCKED corrupting write [moonrise] (kept good disk data)
             chunk(375,375) incoming=structure_starts disk=exists(failsafe)                                          (line 206)
[21:35:49] [ChunkGuard] inspected=82 skipped=1 shadowWouldSkip=0 allowedNewOrEmpty=0 lowHeapFailsafe=1 inspectErrors=0 (line 209)

r-lowheap-default.log (why earlier runs took the fallthrough):
[18:50:26] [ChunkGuard-DBG] readDisk null-cause=threw java.lang.OutOfMemoryError: Java heap space   (line 164)
[18:50:26] [ChunkGuard-DBG] chunk(375,375) in=structure_starts freeHeapMB=876 threshold=192 entry=readDisk result=null (line 165)
```

Two findings:

1. **The default 192 threshold fires as designed under genuine memory pressure** — no decompress
   attempted, write blocked, chunk byte-identical.
2. **Decompressing the 1144-chest chunk's NBT tree OOMs even with ~876 MB free** — the free-heap
   estimate can say "safe" while the decompress still dies. This is exactly why both entries feed
   the same fail-safe: belt and suspenders, now each proven independently.

Debug-build delta (attribution prints only, gated behind `-Dchunkguard.debugPaths=true`):

```diff
  ChunkGuardRuntime:
+   static final boolean DEBUG_PATHS = Boolean.getBoolean("chunkguard.debugPaths");
    decide():
+     after the readDisk attempt:  print "… entry=readDisk result=ok|null"
+     at the low-heap branch:      print "… entry=lowheap-shortcut"
  NbtReflect.readDisk():
+     on each null return:         print "readDisk null-cause=…"
```
