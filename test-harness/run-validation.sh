#!/usr/bin/env bash
# ChunkGuardAgent validation harness.
#
# Reproduces, on a DISPOSABLE test server, the low-heap chunk-load-failure condition the agent guards
# against ("chunk data will be lost"), then verifies the agent's write barrier keeps the on-disk chunk
# intact. Runs three conditions and preserves every server log under ./evidence/ :
#   * real   (agent enabled)      -> the barrier BLOCKS the sub-full write; chunk stays byte-identical
#   * shadow (agent detect-only)  -> the barrier logs "would-skip" but ALLOWS the write; chunk clobbered
#   * (a no-agent control is equivalent to shadow: the write lands and the chunk is clobbered)
# It requires a heavy target chunk on disk (see test-harness/README.md and mineflayer/build-heavy-chunk.js).
#
# READ-ONLY toward production: run ONLY against a throwaway server. This tool writes nothing to world
# data itself; the sole on-disk change is the vanilla save the agent evaluates.
set -uo pipefail

# ============================= CONFIG — edit for your environment =============================
SERVER_DIR="${SERVER_DIR:-/path/to/your/paper-26.2-test-server}"   # a disposable Paper 26.2 server
PAPER_JAR="${PAPER_JAR:-paper-26.2.jar}"                            # server jar filename inside SERVER_DIR
JAVA="${JAVA:-java}"                                               # JDK 25
JAVAC="${JAVAC:-javac}"
JARBIN="${JARBIN:-jar}"
PAPER_API="${PAPER_API:-/path/to/paper-api.jar}"                   # Paper API jar for compiling the test plugin
CX="${CX:-375}"; CZ="${CZ:-375}"                                   # target heavy chunk coordinate
HEADROOM_MB="${HEADROOM_MB:-40}"; HEAP="${HEAP:--Xms1200m -Xmx1200m}"
N_REAL="${N_REAL:-4}"                                              # consecutive real-mode validations
RCON_CMD="${RCON_CMD:-python3 $SERVER_DIR/rcon.py}"                # any CLI that sends its args as one RCON command
# =============================================================================================

HARNESS="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HARNESS/.." && pwd)"
MCA="$HARNESS/tools/mca_read.py"
# overworld region dir — auto-detect the classic layout vs the dimensions/ layout (override with REGDIR=)
REGDIR="${REGDIR:-$(ls -d "$SERVER_DIR/world/region" "$SERVER_DIR/world/dimensions/minecraft/overworld/region" 2>/dev/null | head -1)}"
REGDIR="${REGDIR:-$SERVER_DIR/world/region}"
REG="$REGDIR/r.$((CX>>5)).$((CZ>>5)).mca"; MCC="$REGDIR/c.$CX.$CZ.mcc"
EV="$HARNESS/evidence"; mkdir -p "$EV"; MAN="$EV/manifest.txt"; : > "$MAN"
BASE="$HARNESS/.baseline"; mkdir -p "$BASE"
log(){ echo "[$(date +%H:%M:%S)] $*" | tee -a "$EV/run.log" >&2; }   # stderr: keeps $(run ...) captures clean
rcon(){ timeout 90 $RCON_CMD "$@" 2>/dev/null; }
CP="$PAPER_API:$(find "$SERVER_DIR/libraries" -name '*.jar' 2>/dev/null | tr '\n' ':')"

LOCK="$HARNESS/.run.lock"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then echo "already running (pid $(cat "$LOCK"))"; exit 3; fi
echo $$ > "$LOCK"; trap 'rm -f "$LOCK"' EXIT

count_srv(){ local n=0 p; for p in $(pgrep -x java 2>/dev/null); do case "$(readlink /proc/$p/cwd 2>/dev/null)" in "$SERVER_DIR"*) n=$((n+1));; esac; done; echo "$n"; }
kill_srv(){ local p; for p in $(pgrep -x java 2>/dev/null); do case "$(readlink /proc/$p/cwd 2>/dev/null)" in "$SERVER_DIR"*) kill -9 "$p" 2>/dev/null;; esac; done; }
stop_all(){ kill_srv; for i in $(seq 1 40); do [ "$(count_srv)" = 0 ] && break; sleep 1; done; tmux kill-session -t cgval 2>/dev/null; rm -f "$SERVER_DIR/session.lock"; sleep 2; }
grace_stop(){ rcon stop >/dev/null 2>&1; for i in $(seq 1 40); do [ "$(count_srv)" = 0 ] && break; sleep 2; done; stop_all; }
boot(){ # $1 logfile  $2 agentargs
  stop_all; : > "$1"
  tmux new-session -d -s cgval "cd '$SERVER_DIR' && $JAVA $2 $HEAP -jar '$PAPER_JAR' nogui > '$1' 2>&1"
  for i in $(seq 1 60); do grep -qaE 'Done \(.*For help' "$1" 2>/dev/null && return 0; sleep 3; done; return 1; }
restore(){ cp "$BASE/chunk.mca" "$REG"; if [ -f "$BASE/chunk.mcc" ]; then cp "$BASE/chunk.mcc" "$MCC"; else rm -f "$MCC"; fi; }
cinfo(){ python3 "$MCA" info "$REG" "$CX" "$CZ"; }                       # -> "status block_entities sections"
cdigest(){ python3 "$MCA" digest "$REG" "$CX" "$CZ"; }                   # -> md5 of the target chunk payload only

# ---------- build agent + test plugin ----------
log "building agent (mvn) + test plugin (javac)"
(cd "$REPO" && mvn -q -DskipTests package) || { log "AGENT BUILD FAILED"; exit 1; }
cp "$REPO/target/ChunkGuardAgent.jar" "$SERVER_DIR/ChunkGuardAgent.jar"
AGENT_MD5=$(md5sum "$SERVER_DIR/ChunkGuardAgent.jar" | cut -c1-12); log "agent md5=$AGENT_MD5"
mkdir -p "$HARNESS/plugin/out"
$JAVAC --release 21 -cp "$CP" -d "$HARNESS/plugin/out" "$HARNESS/plugin/src/io/github/kuohsuanlo/chunkguard/testkit/ChunkGuardTestKit.java" || { log "PLUGIN BUILD FAILED"; exit 1; }
cp "$HARNESS/plugin/src/plugin.yml" "$HARNESS/plugin/out/plugin.yml"
(cd "$HARNESS/plugin/out" && "$JARBIN" cf "$SERVER_DIR/plugins/ChunkGuardTestKit.jar" io plugin.yml)

# ---------- baseline (the heavy target chunk must already exist on disk) ----------
[ -f "$BASE/chunk.mca" ] || { cp "$REG" "$BASE/chunk.mca"; [ -f "$MCC" ] && cp "$MCC" "$BASE/chunk.mcc"; }
restore; BASE_DIGEST=$(cdigest); read BS BB BSEC <<< "$(cinfo)"
log "baseline chunk($CX,$CZ): status=$BS block_entities=$BB sections=$BSEC payload_md5=${BASE_DIGEST:0:12}"
printf "%-14s %-6s %-8s %-6s %-24s %-5s %-7s %-34s\n" condition lost blocked would cstatus cbe cmatch logfile >> "$MAN"

run(){ # $1 label  $2 agentargs  $3 tag
  local cond="$1" agentargs="$2" f="$EV/$3-$(date +%H%M%S).log"
  restore; if ! boot "$f" "$agentargs"; then log "$cond BOOTFAIL"; return; fi
  rcon "reloadchunk $CX $CZ $HEADROOM_MB" >/dev/null; sleep 2; grace_stop
  local lost blocked would cs cb csec cmatch
  lost=$(grep -acE "chunk data will be lost" "$f")
  blocked=$(grep -acE "\[ChunkGuard\] BLOCKED" "$f")
  would=$(grep -acE "would-skip" "$f")
  read cs cb csec <<< "$(cinfo)"; cmatch=$([ "$(cdigest)" = "$BASE_DIGEST" ] && echo yes || echo NO)
  printf "%-14s %-6s %-8s %-6s %-24s %-5s %-7s %-34s\n" "$cond" "$lost" "$blocked" "$would" "$cs" "$cb" "$cmatch" "$(basename "$f")" >> "$MAN"
  log "$cond -> lost=$lost blocked=$blocked would=$would cstatus=$cs cbe=$cb cmatch=$cmatch log=$(basename "$f")"
  echo "$lost $blocked $cmatch $cs"
}

REAL="-javaagent:ChunkGuardAgent.jar -Dchunkguard.verbose=true"
SHADOW="$REAL -Dchunkguard.shadow=true"

log "=== real x$N_REAL (agent enabled — expect BLOCK + chunk preserved) ==="
PASS=0
for i in $(seq 1 "$N_REAL"); do
  read l b m st <<< "$(run "real-$i" "$REAL" "real-$i")"
  [ "$l" -ge 1 ] 2>/dev/null && [ "$b" -ge 1 ] 2>/dev/null && [ "$m" = yes ] && [ "$st" = "minecraft:full" ] && { PASS=$((PASS+1)); log "  real-$i PASS ($PASS/$N_REAL)"; } || log "  real-$i FAIL"
done
log "=== shadow (agent detect-only — expect chunk clobbered, proving the threat) ==="
read l b m st <<< "$(run "shadow" "$SHADOW" "shadow")"
[ "$m" = NO ] && log "  shadow OK: without protection the chunk WAS lost (status=$st)" || log "  shadow UNEXPECTED"
restore
log "=== SUMMARY: real ${PASS}/${N_REAL} passed (each: reproduced loss, blocked write, chunk byte-identical) ==="
