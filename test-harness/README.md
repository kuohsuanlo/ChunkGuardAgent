# ChunkGuardAgent — validation test-harness / 驗證測試套件

A self-contained harness that **reproduces the exact condition ChunkGuardAgent protects against** and
**proves the agent keeps the chunk intact**. Everything here is validation tooling for a **disposable
test server** — it writes nothing to world data itself; the only on-disk change is the vanilla save
the agent evaluates. See the full report in [`../docs/VALIDATION.md`](../docs/VALIDATION.md).

> ⚠️ Run only against a throwaway Paper server. The harness deliberately drives one server to a low-heap
> state to trigger a chunk-load failure — that is the whole point of the test — so never point it at
> anything you care about.

## What it demonstrates / 這在證明什麼

Under memory exhaustion, a chunk being **loaded** can fail to allocate, so the server substitutes an
empty proto-chunk and logs `chunk data will be lost`; that stub then gets saved back over the good
full chunk — silent data loss. The harness reproduces this deterministically on a heavy chunk and
compares these conditions (the script runs real ×N + shadow; the no-agent row is an equivalence — without
the agent the write lands exactly as in shadow):

低記憶體時,正在**載入**的區塊配不到記憶體 → 伺服器頂替一個空 proto-chunk 並印 `chunk data will be
lost` → 這個空殼被存回、蓋掉原本完整的區塊 = 靜默資料遺失。本套件在一格重區塊上確定性重現,並比較以下
條件(腳本實際執行 real ×N 與 shadow;no-agent 列為等價推論——沒有 agent 時寫入落地,下場與 shadow 相同):

| condition | agent | on-disk chunk after | 結果 |
|---|---|---|---|
| **real** (agent enabled) | blocks the write | **byte-identical** (preserved) | ✅ |
| **shadow** (`-Dchunkguard.shadow=true`) | logs "would-skip", allows | clobbered to a blank stub | ❌ |
| **no-agent** (control) | — | clobbered to a blank stub | ❌ |

Pass criterion per real run: reproduced the loss **and** blocked the write **and** the target chunk's
payload is byte-identical to baseline **and** its status is still `full`.

## Layout

```
test-harness/
├── run-validation.sh            main harness (config block at top — EDIT for your environment)
├── plugin/src/…/ChunkGuardTestKit.java   test plugin: /heapfill /heaprelease /reloadchunk
├── plugin/src/plugin.yml
├── tools/mca_read.py            self-contained Anvil reader (info / digest), no third-party deps
└── mineflayer/build-heavy-chunk.js   optional bot to build the heavy target chunk
```

The test plugin exposes three console/RCON commands (validation only):
- `/heapfill [seconds] [threads] [mbPerAlloc]` — apply a transient, self-releasing heap load.
- `/heaprelease` — release it immediately.
- `/reloadchunk <cx> <cz> [headroomMB] [world]` — reduce free heap to ~headroomMB, then force a fresh
  load of that chunk; on a heavy chunk the load fails as under real memory exhaustion.

## How to run / 執行步驟

1. **Prepare a disposable Paper 26.2 server** with WorldEdit installed and RCON enabled. JDK 25.
   The harness sends console commands via `$RCON_CMD` (default: `python3 $SERVER_DIR/rcon.py`) —
   point `RCON_CMD` at any CLI that sends its arguments as a single RCON command.
2. **Build a heavy target chunk** once (a chunk whose decompressed NBT clearly exceeds the heap
   headroom — e.g. ~1000+ chests with item lore). Use `mineflayer/build-heavy-chunk.js`
   (`npm install` first, needs a lore-chest schematic in WorldEdit), or build one by hand, then
   `/save-all`. Confirm it is heavy: `python3 tools/mca_read.py info <region.mca> <cx> <cz>`.
3. **Edit the CONFIG block** at the top of `run-validation.sh` (`SERVER_DIR`, `PAPER_JAR`, `JAVA`,
   `PAPER_API`, `CX`/`CZ`, `HEADROOM_MB`).
4. `bash run-validation.sh` — it rebuilds the agent (`mvn`) + the test plugin, then runs
   `real ×N` + `shadow`, preserving every server log under `evidence/` with a `manifest.txt`.

Tune `HEADROOM_MB` (default 40) / `HEAP` so the load fails on your chunk: the target's decompressed
size must exceed the headroom while the server still boots.

## Expected result

`real` runs log `[ChunkGuard] BLOCKED …` and the target chunk stays byte-identical; `shadow` logs
`… would-skip …` but the chunk is overwritten with a blank stub. That contrast is the proof.
