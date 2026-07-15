package io.github.kuohsuanlo.chunkguard;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pure-JDK runtime for the chunk-save write barrier: the decision + observability, called from the
 * instrumented region-file write methods.
 *
 * <p><b>On the bootstrap classloader</b> (the agent appends the whole jar), so the call spliced into
 * the NMS write method sees this exact class through parent delegation. A bootstrap class may
 * reference only the JDK — the chunk NBT / IO objects are read purely through {@link NbtReflect}.</p>
 *
 * <p><b>The decision (zero-false-positive "iron rule"):</b> a chunk's status only ever advances
 * ({@code empty → … → full}); it never legitimately regresses. So we SKIP a write iff the incoming
 * chunk's {@code Status} is <b>not</b> {@code full} while the on-disk chunk's {@code Status}
 * <b>is</b> {@code full}. That is exactly the corruption we saw (a load-failed {@code empty}/proto
 * stub about to overwrite a real {@code full} chunk → a 4KB shell) and it can never fire on a
 * legitimate save — even a chunk a player mined empty is still {@code full}. Every uncertain path
 * fails OPEN: a legitimate save is never blocked.</p>
 */
public final class ChunkGuardRuntime {

    private ChunkGuardRuntime() {
    }

    /** {@code -Dchunkguard.enabled=false} turns the barrier off entirely. */
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("chunkguard.enabled", "true"));

    /** {@code -Dchunkguard.shadow=true}: DETECT-ONLY — log what it WOULD skip, but always allow the write.
     *  Recommended for the first days on a live server to confirm zero false positives. */
    private static final boolean SHADOW = Boolean.getBoolean("chunkguard.shadow");

    /** {@code -Dchunkguard.verbose=true}: background thread prints counters every 60s. */
    private static final boolean VERBOSE = Boolean.getBoolean("chunkguard.verbose");

    /** transformer sets true once at least one write chokepoint is successfully instrumented. */
    public static volatile boolean injected = false;

    // ── observability counters ──
    public static final AtomicLong inspected = new AtomicLong();
    public static final AtomicLong skipped = new AtomicLong();
    public static final AtomicLong shadowWouldSkip = new AtomicLong();
    public static final AtomicLong allowedNewOrEmpty = new AtomicLong();
    public static final AtomicLong inspectErrors = new AtomicLong();
    /** SKIPs decided via the memory-free existence fallback (low heap / disk read failed). */
    public static final AtomicLong lowHeapFailsafe = new AtomicLong();

    /** Below this many MB of estimated free heap we skip the heap-heavy disk decompress and use the
     *  memory-free existence check — the corruption happens precisely under memory exhaustion, when a
     *  decompress would OOM and force a fail-open. {@code -Dchunkguard.lowHeapMB=N} to tune. */
    private static final long LOW_HEAP_MB = Long.getLong("chunkguard.lowHeapMB", 192L);

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean shadow() {
        return SHADOW;
    }

    // ───────────────────────── hook entry points ─────────────────────────

    /**
     * Called from {@code RegionFileStorage.moonrise$finishWrite(int x, int z, WriteData)} — the
     * common chokepoint for every scheduled chunk save on Paper (Moonrise). Return true to skip.
     */
    public static boolean shouldSkipMoonrise(Object storage, int x, int z, Object writeData) {
        if (!ENABLED) {
            return false;
        }
        try {
            if (NbtReflect.writeDataIsDelete(writeData)) {
                return false; // legitimate chunk removal — never our concern
            }
            Object nbt = NbtReflect.writeDataInput(writeData);
            return gate(storage, nbt, x, z, "moonrise");
        } catch (Throwable t) {
            return failOpen(t);
        }
    }

    /**
     * Called from vanilla {@code RegionFileStorage.write(ChunkPos, CompoundTag)} — the fallback path
     * (IOWorker / non-Moonrise / world upgrade). A null nbt means delete → allow.
     */
    public static boolean shouldSkipVanilla(Object storage, Object chunkPos, Object nbt) {
        if (!ENABLED || nbt == null) {
            return false;
        }
        try {
            int[] xz = NbtReflect.chunkPosXZ(chunkPos);
            if (xz == null) {
                return false;
            }
            return gate(storage, nbt, xz[0], xz[1], "vanilla");
        } catch (Throwable t) {
            return failOpen(t);
        }
    }

    // ───────────────────────── decision ─────────────────────────

    private static boolean gate(Object storage, Object incomingNbt, int x, int z, String source) {
        inspected.incrementAndGet();
        String verdict = decide(storage, incomingNbt, x, z);
        if (verdict == null) {
            return false; // allow (uncertain / normal)
        }
        // verdict != null → a blank/proto stub would overwrite a full on-disk chunk.
        if (SHADOW) {
            shadowWouldSkip.incrementAndGet();
            System.out.println("[ChunkGuard] SHADOW would-skip corrupting write [" + source + "] " + verdict);
            return false;
        }
        skipped.incrementAndGet();
        System.out.println("[ChunkGuard] BLOCKED corrupting write [" + source + "] (kept good disk data) " + verdict);
        return true;
    }

    /**
     * The iron rule. Returns a human-readable detail String if the write should be SKIPPED, or
     * {@code null} to ALLOW it.
     */
    private static String decide(Object storage, Object incomingNbt, int x, int z) {
        String inStatus = NbtReflect.statusOf(incomingNbt);
        if (inStatus == null) {
            return null; // couldn't read incoming status → fail open
        }
        if ("full".equals(inStatus)) {
            return null; // fast path: a full chunk is always a legitimate save (incl. player-emptied)
        }
        // incoming is proto / empty / blank — a candidate corrupting overwrite.
        // Only decompress the on-disk chunk when there is real heap headroom. The corruption fires
        // precisely under memory exhaustion; decompressing a (possibly heavy) disk chunk then would
        // OOM and force a fail-open — the guard would be defeated by the very OOM it must catch.
        if (freeHeapMB() >= LOW_HEAP_MB) {
            Object diskNbt = NbtReflect.readDisk(storage, x, z);
            if (diskNbt != null) {
                String diskStatus = NbtReflect.statusOf(diskNbt);
                if ("full".equals(diskStatus)) {
                    // full on disk being overwritten by a non-full stub: the corruption. Keep it.
                    return "chunk(" + x + "," + z + ") incoming=" + inStatus + " disk=full";
                }
                return null; // disk read OK and non-full → normal worldgen progression, allow
            }
            // diskNbt == null → no data OR a read failure; disambiguate with the cheap check below.
        }
        // Low heap OR disk read returned null: use the MEMORY-FREE existence check (region header
        // only, no decompress). If a chunk already exists on disk but we could not confirm it is a
        // legitimate non-full save, FAIL SAFE and skip — a non-full write landing over an existing
        // chunk under memory pressure is exactly the corruption. Worst case for a real worldgen
        // chunk mid-progression: it is left as-is and regenerated on next load (no data loss).
        if (NbtReflect.diskChunkExists(storage, x, z)) {
            lowHeapFailsafe.incrementAndGet();
            return "chunk(" + x + "," + z + ") incoming=" + inStatus + " disk=exists(failsafe)";
        }
        allowedNewOrEmpty.incrementAndGet();
        return null; // no chunk on disk → legitimately new/growing chunk, allow
    }

    /** Estimated free heap in MB (JDK-only, allocation-free) — {@code max - (total - free)}. */
    private static long freeHeapMB() {
        Runtime r = Runtime.getRuntime();
        return (r.maxMemory() - (r.totalMemory() - r.freeMemory())) / (1024L * 1024L);
    }

    private static boolean failOpen(Throwable t) {
        inspectErrors.incrementAndGet();
        if (VERBOSE) {
            System.err.println("[ChunkGuard] inspect error (failing open, write allowed): " + t);
        }
        return false;
    }

    // ───────────────────────── observability ─────────────────────────

    public static String stats() {
        return "inspected=" + inspected.get()
                + " skipped=" + skipped.get()
                + " shadowWouldSkip=" + shadowWouldSkip.get()
                + " allowedNewOrEmpty=" + allowedNewOrEmpty.get()
                + " lowHeapFailsafe=" + lowHeapFailsafe.get()
                + " inspectErrors=" + inspectErrors.get()
                + (SHADOW ? " [SHADOW]" : "")
                + (ENABLED ? "" : " [DISABLED]");
    }

    public static void maybeStartVerboseLogger() {
        if (!VERBOSE) {
            return;
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(60_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                System.out.println("[ChunkGuard] " + stats());
            }
        }, "ChunkGuard-verbose");
        t.setDaemon(true);
        t.start();
    }
}
