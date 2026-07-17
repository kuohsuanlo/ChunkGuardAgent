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
 *
 * <p><b>Second rule (26.2-2, {@code -Dchunkguard.inhabitedGuard}, default ON):</b> {@code
 * InhabitedTime}(里程)is the other monotonic quantity — it only accumulates and travels with the
 * data. A {@code full} chunk with near-zero mileage overwriting a {@code full} chunk carrying
 * hours of it is a load-failed chunk the surviving server regenerated (fake full) — the one
 * impostor the status rule cannot see. Observed in production 4× (s16 ×3, s73 ×1, 2026-07).</p>
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
    /** SKIPs decided via the InhabitedTime mileage-regression check (regenerated fake-full). */
    public static final AtomicLong fakeFullBlocked = new AtomicLong();
    /** READ-GUARD: mislabeled ex-full chunks healed on load (status rewritten to full). */
    public static final AtomicLong readGuardHealed = new AtomicLong();
    /** READ-GUARD: proto-with-mileage corpses detected but NOT healable (content incomplete) — alert only. */
    public static final AtomicLong readGuardAlerts = new AtomicLong();

    /** {@code -Dchunkguard.readGuard=false} disables the READ-side heal (default ON).
     *  InhabitedTime only ever accrues on ticking FULL chunks (ServerChunkCache tick loop) — a
     *  legitimate mid-generation proto can never carry mileage. So an on-disk chunk whose Status
     *  says step 1-10 but which carries mileage is the corpse of an ex-full chunk (bad write /
     *  bit-rot / pre-agent damage). Loading it as-is makes worldgen RESUME from that step and wipe
     *  whatever content survived. If the content still looks complete we rewrite Status→full
     *  in-place BEFORE parse, so the game loads the data instead of erasing it. */
    private static final boolean READ_GUARD =
            !"false".equalsIgnoreCase(System.getProperty("chunkguard.readGuard", "true"));

    /** Mileage floor for the read guard: legit protos carry exactly 0; 1200 ticks (1 min) absorbs
     *  any exotic edge while still catching every real corpse. */
    private static final long READ_GUARD_MIN_IT = 1_200L;

    /** Below this many MB of estimated free heap we skip the heap-heavy disk decompress and use the
     *  memory-free existence check — the corruption happens precisely under memory exhaustion, when a
     *  decompress would OOM and force a fail-open. {@code -Dchunkguard.lowHeapMB=N} to tune. */
    private static final long LOW_HEAP_MB = Long.getLong("chunkguard.lowHeapMB", 192L);

    /** {@code -Dchunkguard.inhabitedGuard=false} disables the fake-full mileage check (default ON).
     *  Catches the impostor the status rule cannot see: a load-failed chunk that the surviving server
     *  REGENERATED all the way to {@code full}, about to overwrite the real lived-in chunk. Both are
     *  {@code full}; only {@code InhabitedTime}(里程,monotonic, travels with the data) tells them
     *  apart — the real chunk carries hours of it, the freshly regenerated impostor carries ~none.
     *  Known legitimate mileage resets that this check would block (disable the flag, or expect a
     *  BLOCKED log + the write simply not persisting): whole-chunk regeneration plugins (island/mine
     *  reset), difficulty-reset plugins that zero InhabitedTime, online region-trim cache windows. */
    private static final boolean INHABITED_GUARD =
            !"false".equalsIgnoreCase(System.getProperty("chunkguard.inhabitedGuard", "true"));

    /** Incoming full chunks with at least this much mileage are never treated as impostors (no disk
     *  IO spent on them). 72,000 ticks = 1 hour of accumulated player presence. */
    private static final long IT_TRUSTED = 72_000L;

    /** The on-disk chunk must carry at least this much mileage (20 min) — and at least 50× the
     *  incoming mileage — before a regression is called corruption. Fresh worldgen areas where both
     *  sides are near zero never trigger. */
    private static final long IT_DISK_MIN = 24_000L;

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

    /**
     * READ GUARD — called at the entry of {@code SerializableChunkData.parse(...)} with the freshly
     * deserialized chunk CompoundTag, BEFORE the game interprets its Status. May mutate the tag
     * in-place (Status → full). Never throws; every uncertain path leaves the tag untouched.
     */
    public static void onChunkNbtRead(Object nbt) {
        if (!ENABLED || !READ_GUARD || nbt == null) {
            return;
        }
        try {
            String st = NbtReflect.statusOf(nbt);
            if (st == null || st.isEmpty() || "full".equals(st)) {
                return; // unreadable or already full → nothing to judge
            }
            long it = NbtReflect.inhabitedTimeOf(nbt);
            if (it < READ_GUARD_MIN_IT) {
                return; // legit protos carry zero mileage → this is normal worldgen data
            }
            int x = NbtReflect.intOf(nbt, "xPos");
            int z = NbtReflect.intOf(nbt, "zPos");
            int sections = NbtReflect.sectionCount(nbt);
            if (sections < 8) {
                // Corpse confirmed (mileage on a proto) but the content is gone — healing cannot
                // conjure blocks back. Alert loudly; restore must come from a backup.
                readGuardAlerts.incrementAndGet();
                System.out.println("[ChunkGuard] READ-GUARD ALERT chunk(" + x + "," + z + ") status=" + st
                        + " inhabited=" + it + " sections=" + sections
                        + " — proto-with-mileage corpse, content incomplete: NOT healed, restore from backup");
                return;
            }
            if (SHADOW) {
                readGuardAlerts.incrementAndGet();
                System.out.println("[ChunkGuard] READ-GUARD SHADOW would-heal chunk(" + x + "," + z
                        + ") status=" + st + " inhabited=" + it + " sections=" + sections);
                return;
            }
            if (NbtReflect.putStatusFull(nbt)) {
                readGuardHealed.incrementAndGet();
                System.out.println("[ChunkGuard] READ-GUARD HEALED chunk(" + x + "," + z + "): status " + st
                        + " → full (inhabited=" + it + ", sections=" + sections
                        + ") — mislabeled ex-full chunk rescued before regeneration could wipe it");
            }
        } catch (Throwable t) {
            failOpen(t);
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
            // Fast path: a full chunk is almost always a legitimate save (incl. player-emptied).
            // The one impostor: a load-failed chunk the SURVIVING server regenerated to full
            // (status identical, size normal). Only its mileage gives it away.
            if (!INHABITED_GUARD) {
                return null;
            }
            long inIt = NbtReflect.inhabitedTimeOf(incomingNbt);
            if (inIt < 0 || inIt >= IT_TRUSTED) {
                return null; // unreadable (fail open) or ≥1h of presence → certainly not fresh regen; zero extra IO
            }
            if (freeHeapMB() < LOW_HEAP_MB) {
                return null; // stay conservative under pressure; fake-full saves happen after recovery anyway
            }
            // Streaming byte-scan of the on-disk payload (bounded 64KB window, NO NBT tree): a full
            // readDisk decompress of a monster chunk can itself OOM (measured >876MB for a 1144-chest
            // chunk) — precisely the lived-in chunks this rule must protect. The scan cannot OOM.
            long[] scan = NbtReflect.diskScanMileage(storage, x, z);
            if (scan == null) {
                return null; // no baseline to compare → fail open (full writes are normally legit)
            }
            long diskIt = scan[0];
            if (diskIt >= Math.max(IT_DISK_MIN, inIt * 50L) && scan[1] == 1L) {
                // A lived-in full chunk about to be replaced by a near-zero-mileage full chunk:
                // mileage never legitimately regresses → regenerated impostor. Keep the real one.
                fakeFullBlocked.incrementAndGet();
                return "chunk(" + x + "," + z + ") incoming=full disk=full inhabited=" + inIt + "<" + diskIt
                        + " (mileage regression)";
            }
            return null;
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
                + " fakeFullBlocked=" + fakeFullBlocked.get()
                + " readGuardHealed=" + readGuardHealed.get()
                + " readGuardAlerts=" + readGuardAlerts.get()
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
