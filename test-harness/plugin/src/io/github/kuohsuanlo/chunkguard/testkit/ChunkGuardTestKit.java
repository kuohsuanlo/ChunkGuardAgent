package io.github.kuohsuanlo.chunkguard.testkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ChunkGuardTestKit — a controlled test harness that reproduces the exact low-heap chunk-load-failure
 * condition ChunkGuardAgent is designed to guard against, so the agent's write barrier can be
 * validated against a real event on an isolated test server. It creates a transient, self-releasing
 * low-heap condition and then requests a fresh load of a chosen chunk; under that condition the
 * server's own chunk system logs "chunk data will be lost" and substitutes an empty proto-chunk —
 * the situation whose write-back the agent must intercept.
 *
 * <p>This plugin is a validation tool only. It performs no writes to world data itself; the only
 * on-disk change comes from the vanilla save path, which is precisely what the agent evaluates. Run
 * it exclusively on a disposable test server. Commands:</p>
 * <ul>
 *   <li>{@code /heapfill [seconds] [threads] [mbPerAlloc]} — apply a transient, self-releasing heap
 *       load (retained {@code byte[]} on daemon threads; an independent daemon thread releases it
 *       after {@code seconds}, so it survives a stop-the-world pause on the main thread).</li>
 *   <li>{@code /heaprelease} — release the retained memory immediately.</li>
 *   <li>{@code /reloadchunk <cx> <cz> [headroomMB] [world]} — reduce free heap to about
 *       {@code headroomMB}, then force a fresh load of chunk (cx,cz) to STRUCTURE_STARTS (the first
 *       status above EMPTY, so the on-disk data is actually read + decompressed). A heavy chunk's
 *       decompress exceeds the headroom, the load fails as it would under real memory exhaustion, the
 *       server substitutes an empty proto-chunk, and we mark it unsaved so the shutdown save attempts
 *       the sub-full write the agent should skip.</li>
 * </ul>
 */
public final class ChunkGuardTestKit extends JavaPlugin {

    static final List<byte[]> RETAINED = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean active = false;

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("heaprelease")) {
            release("manual");
            return true;
        }
        if (name.equals("reloadchunk")) {
            try {
                return doReload(a);
            } catch (Throwable t) {
                getLogger().warning("reloadchunk failed: " + t);
                t.printStackTrace();
                return true;
            }
        }
        // setmileage: overwrite a LOADED chunk's InhabitedTime and mark it unsaved — deterministically
        // manufactures the defining state of a fake-full impostor (near-zero mileage in memory, big
        // mileage on disk) without needing an OOM. Load the chunk first (holdchunk).
        if (name.equals("setmileage")) {
            if (a.length < 3) { getLogger().info("usage: /setmileage <cx> <cz> <ticks> [world]"); return true; }
            try {
                int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]);
                long ticks = Long.parseLong(a[2]);
                World w = Bukkit.getWorld(a.length > 3 ? a[3] : "world");
                Object level = w.getClass().getMethod("getHandle").invoke(w);
                Object cache = level.getClass().getMethod("getChunkSource").invoke(level);
                Class<?> csC = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");
                Object full = csC.getField("FULL").get(null);
                Method get = cache.getClass().getMethod("getChunk", int.class, int.class, csC, boolean.class);
                Object chunk = get.invoke(cache, cx, cz, full, true); // already loaded → returns instantly
                chunk.getClass().getMethod("setInhabitedTime", long.class).invoke(chunk, ticks);
                Method mark = findMethod(chunk.getClass(), "markUnsaved", 0);
                if (mark != null) { mark.setAccessible(true); mark.invoke(chunk); }
                else setBoolField(chunk, "unsaved", true);
                System.out.println("[testkit] setmileage (" + cx + "," + cz + ") -> " + ticks + " marked unsaved");
            } catch (Throwable t) {
                getLogger().warning("setmileage failed: " + t);
                t.printStackTrace();
            }
            return true;
        }
        // holdchunk/releasechunk: player-like ASYNC full-chunk driver — keeps a load-failed proto's
        // holder demanded at full so worldgen REGENERATES it (the fake-full reproduction).
        // Do NOT use /forceload or a bare addPluginChunkTicket for this: both load synchronously and
        // park the main thread, which stalls the generation pipeline itself (observed: Chunk wait
        // watchdog spam, generationTask=null). getChunkAtAsync leaves the main thread free — the
        // same shape as a real player ticket; the plugin ticket is added only after load completes.
        if (name.equals("holdchunk") || name.equals("releasechunk")) {
            if (a.length < 2) { getLogger().info("usage: /" + name + " <cx> <cz> [world]"); return true; }
            final int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]);
            final World w = Bukkit.getWorld(a.length > 2 ? a[2] : "world");
            if (w == null) { getLogger().warning("no world"); return true; }
            if (name.equals("releasechunk")) {
                boolean ok = w.removePluginChunkTicket(cx, cz, this);
                System.out.println("[testkit] releasechunk (" + cx + "," + cz + ") -> " + ok);
                return true;
            }
            System.out.println("[testkit] holdchunk async request (" + cx + "," + cz + ") ...");
            w.getChunkAtAsync(cx, cz, true).thenAccept(c -> {
                w.addPluginChunkTicket(cx, cz, this);
                System.out.println("[testkit] holdchunk (" + cx + "," + cz + ") loaded+ticketed, chunk=" + c);
            }).exceptionally(t -> {
                System.out.println("[testkit] holdchunk (" + cx + "," + cz + ") async failed: " + t);
                return null;
            });
            return true;
        }
        // heapfill
        int seconds = a.length > 0 ? parse(a[0], 30) : 30;
        int threads = a.length > 1 ? parse(a[1], 8) : 8;
        int mb      = a.length > 2 ? parse(a[2], 8) : 8;
        if (active) { getLogger().info("already active"); return true; }
        active = true;
        RETAINED.clear();
        final int unit = mb * 1024 * 1024;
        System.out.println("[testkit] heap load START: " + threads + " threads x " + mb
                + "MB, auto-release in " + seconds + "s");
        getLogger().info("heapfill armed: " + seconds + "s");

        Thread releaser = new Thread(() -> {
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            while (System.nanoTime() < end) {
                try { Thread.sleep(50); } catch (InterruptedException e) { break; }
            }
            release("timer(" + seconds + "s)");
        }, "testkit-releaser");
        releaser.setDaemon(true);
        releaser.start();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                while (active) {
                    try {
                        byte[] b = new byte[unit];
                        b[0] = 1; b[b.length - 1] = 1;
                        RETAINED.add(b);
                    } catch (OutOfMemoryError oom) {
                        try { Thread.sleep(1); } catch (InterruptedException e) { return; }
                    }
                }
            }, "testkit-alloc-" + i);
            t.setDaemon(true);
            t.start();
        }
        return true;
    }

    /** Reduce free heap to ~headroomMB, then force a fresh sub-full load of (cx,cz) so its on-disk
     *  data is read + decompressed. A heavy chunk's decompress exceeds the headroom, the load fails
     *  as under real memory exhaustion, the server substitutes an empty proto-chunk; we then release
     *  the retained memory and mark the proto unsaved so a shutdown save exercises the barrier. */
    private boolean doReload(String[] a) throws Exception {
        if (a.length < 2) { getLogger().info("usage: /reloadchunk <cx> <cz> [headroomMB] [world]"); return true; }
        int cx = Integer.parseInt(a[0]);
        int cz = Integer.parseInt(a[1]);
        int headroomMB = a.length > 2 ? parse(a[2], 15) : 15;
        String wn = a.length > 3 ? a[3] : "world";
        World w = Bukkit.getWorld(wn);
        if (w == null) { getLogger().warning("no world " + wn); return true; }
        Object level = w.getClass().getMethod("getHandle").invoke(w);              // ServerLevel
        Object cache = level.getClass().getMethod("getChunkSource").invoke(level); // ServerChunkCache
        Class<?> csC = Class.forName("net.minecraft.world.level.chunk.status.ChunkStatus");
        Object structureStarts = csC.getField("STRUCTURE_STARTS").get(null);
        Method get = cache.getClass().getMethod("getChunk", int.class, int.class, csC, boolean.class);

        long targetFree = headroomMB * 1024L * 1024L;
        List<byte[]> retained = new ArrayList<>();
        try {
            while (freeHeap() > targetFree) { retained.add(new byte[1024 * 1024]); }   // 1MB steps
        } catch (OutOfMemoryError oom) {
            for (int i = 0; i < 8 && !retained.isEmpty(); i++) retained.remove(retained.size() - 1);
        }
        System.out.println("[testkit] free heap ~=" + (freeHeap() / 1024 / 1024)
                + "MB (target " + headroomMB + "MB); forcing reload of (" + cx + "," + cz + ") ...");

        Object chunk;
        try {
            chunk = get.invoke(cache, cx, cz, structureStarts, true);   // off-main decompress may fail here
        } finally {
            retained.clear();
            // recover heap so the server survives to a clean shutdown save (where the barrier runs)
            for (int i = 0; i < 8; i++) { System.gc(); try { Thread.sleep(120); } catch (InterruptedException e) { break; } }
            System.out.println("[testkit] heap recovered: free~=" + (freeHeap() / 1024 / 1024) + "MB");
        }
        if (chunk == null) { getLogger().warning("[testkit] null chunk"); return true; }

        Object st = chunk.getClass().getMethod("getPersistedStatus").invoke(chunk);
        System.out.println("[testkit] holder chunk class=" + chunk.getClass().getSimpleName()
                + " persistedStatus=" + st + " isUnsaved(before)=" + call(chunk, "isUnsaved"));

        Method mark = findMethod(chunk.getClass(), "markUnsaved", 0);
        if (mark != null) { mark.setAccessible(true); mark.invoke(chunk); }
        else setBoolField(chunk, "unsaved", true);
        System.out.println("[testkit] marked unsaved; isUnsaved(after)=" + call(chunk, "isUnsaved")
                + " -> now stop the server");
        return true;
    }

    private static Object call(Object o, String n) {
        try { Method m = findMethod(o.getClass(), n, 0); m.setAccessible(true); return m.invoke(o); }
        catch (Throwable t) { return "?"; }
    }

    private static Method findMethod(Class<?> c, String n, int argc) {
        for (; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(n) && m.getParameterCount() == argc) return m;
        return null;
    }

    private static void setBoolField(Object o, String n, boolean v) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            try { Field f = c.getDeclaredField(n); f.setAccessible(true); f.setBoolean(o, v); return; }
            catch (NoSuchFieldException ignored) { }
    }

    private static void release(String why) {
        active = false;
        int n = RETAINED.size();
        RETAINED.clear();
        System.gc();
        System.out.println("[testkit] heap load RELEASED (" + why + "), freed " + n + " blocks");
    }

    private static long freeHeap() {
        Runtime r = Runtime.getRuntime();
        return r.maxMemory() - (r.totalMemory() - r.freeMemory());
    }

    private static int parse(String v, int dflt) {
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return dflt; }
    }
}
