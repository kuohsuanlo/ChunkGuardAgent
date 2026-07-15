#!/usr/bin/env bash
# Build ChunkGuardAgent: mvn package is all — the runtime reads chunk NBT purely by REFLECTION,
# so there is NO compile-against-NMS template step.
#   -> agent classes (AgentMain/Runtime/Transformer/NbtReflect) + shaded/relocated ASM + agent manifest
# 26.2 Paper runs on Java 25; the agent classes target release 21 (pure JDK + relocated ASM).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jdk-25.0.3+9}"; export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
mvn -q -B clean package
JAR="target/ChunkGuardAgent.jar"
[ -f "$JAR" ] || { echo "ERROR: $JAR not produced" >&2; exit 1; }
echo "-- manifest --"; unzip -p "$JAR" META-INF/MANIFEST.MF | grep -E 'Premain|Agent-Class|Retransform|Redefine' || true
echo "-- relocated ASM entries --"; unzip -l "$JAR" | grep -c 'chunkguard/asm/' || true
echo "OK: $JAR"
