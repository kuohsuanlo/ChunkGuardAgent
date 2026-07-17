package io.github.kuohsuanlo.chunkguard;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-tolerant, allocation-light reflection over Paper's chunk NBT / IO objects.
 *
 * <p>This class lives on the bootstrap classloader, so it may reference only the JDK. Every NMS /
 * Moonrise object arrives as {@code java.lang.Object}; we reflect against the object's OWN class
 * (loaded by Paper's classloader, which can see {@code net.minecraft.*}). Resolved
 * {@link Method}/{@link Constructor}/{@link Field} handles are cached per class/classloader.
 * Everything is fail-safe: any failure returns a benign value so the write barrier fails OPEN.</p>
 *
 * <p>Cross-version notes baked in: {@code CompoundTag.getStringOr(String,String)} is used when
 * present (Paper 1.21.5+), else {@code getString(String)} unwrapping an {@link Optional}; the
 * {@code Status} value's {@code minecraft:} namespace is stripped; {@code WriteData}'s first record
 * component (the CompoundTag) is read via its {@code input()} accessor with a scan fallback.</p>
 */
final class NbtReflect {

    private NbtReflect() {
    }

    private static final ConcurrentHashMap<Class<?>, Method> GET_STRING_OR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_STRING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_LONG_OR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_LONG = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> READ = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> WD_INPUT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> WD_RESULT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<ClassLoader, Constructor<?>> CHUNKPOS_CTOR = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field[]> CHUNKPOS_XZ = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> RF_LOADED = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> RF_EXISTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> HAS_CHUNK = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_STREAM = new ConcurrentHashMap<>();

    /** Chunk status, namespace-stripped + lowercased ("full" / "empty" / …); "" if absent; null if unreadable. */
    static String statusOf(Object compoundTag) {
        if (compoundTag == null) {
            return null;
        }
        try {
            Class<?> c = compoundTag.getClass();
            String s = null;
            Method or = GET_STRING_OR.computeIfAbsent(c, NbtReflect::findGetStringOr);
            if (or != null) {
                s = (String) or.invoke(compoundTag, "Status", "");
            } else {
                Method g = GET_STRING.computeIfAbsent(c, NbtReflect::findGetString);
                if (g != null) {
                    Object r = g.invoke(compoundTag, "Status");
                    if (r instanceof Optional) {
                        Object v = ((Optional<?>) r).orElse(null);
                        s = v == null ? "" : v.toString();
                    } else {
                        s = r == null ? "" : r.toString();
                    }
                }
            }
            if (s == null || s.isEmpty()) {
                return "";
            }
            int colon = s.indexOf(':');
            return (colon >= 0 ? s.substring(colon + 1) : s).toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code InhabitedTime}(里程)of the chunk NBT — monotonically increasing, travels with the
     *  data. -1 if unreadable (treat as "cannot judge", fail open). */
    static long inhabitedTimeOf(Object compoundTag) {
        if (compoundTag == null) {
            return -1L;
        }
        try {
            Class<?> c = compoundTag.getClass();
            Method or = GET_LONG_OR.computeIfAbsent(c, k -> findMethod2(k, "getLongOr", String.class, long.class));
            if (or != null) {
                Object r = or.invoke(compoundTag, "InhabitedTime", -1L);
                return (r instanceof Number) ? ((Number) r).longValue() : -1L;
            }
            Method g = GET_LONG.computeIfAbsent(c, k -> findMethod1x(k, "getLong", String.class));
            if (g != null) {
                Object r = g.invoke(compoundTag, "InhabitedTime");
                if (r instanceof Optional) {
                    Object v = ((Optional<?>) r).orElse(null);
                    return (v instanceof Number) ? ((Number) v).longValue() : -1L;
                }
                if (r instanceof Number) {
                    return ((Number) r).longValue();
                }
            }
            return -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static final ConcurrentHashMap<Class<?>, Method> PUT_STRING = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_TAG = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> GET_INT_OR = new ConcurrentHashMap<>();

    /** Rewrites the tag's Status to {@code minecraft:full} in place. True on success. */
    static boolean putStatusFull(Object compoundTag) {
        try {
            Method m = PUT_STRING.computeIfAbsent(compoundTag.getClass(),
                    k -> findMethod2(k, "putString", String.class, String.class));
            if (m == null) {
                return false;
            }
            m.invoke(compoundTag, "Status", "minecraft:full");
            return "full".equals(statusOf(compoundTag)); // verify the write took
        } catch (Throwable t) {
            return false;
        }
    }

    /** Number of entries in the tag's {@code sections} list; -1 if unreadable. ListTag extends
     *  java.util.AbstractList, so the size is readable without any NMS reflection. */
    static int sectionCount(Object compoundTag) {
        try {
            Method m = GET_TAG.computeIfAbsent(compoundTag.getClass(),
                    k -> findMethod1x(k, "get", String.class));
            if (m == null) {
                return -1;
            }
            Object o = m.invoke(compoundTag, "sections");
            return (o instanceof java.util.List) ? ((java.util.List<?>) o).size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Int field of the tag ({@code getIntOr}); 0 if unreadable — used for log coordinates only. */
    static int intOf(Object compoundTag, String key) {
        try {
            Method m = GET_INT_OR.computeIfAbsent(compoundTag.getClass(),
                    k -> findMethod2(k, "getIntOr", String.class, int.class));
            if (m == null) {
                return 0;
            }
            Object r = m.invoke(compoundTag, key, 0);
            return (r instanceof Number) ? ((Number) r).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method findMethod2(Class<?> c, String name, Class<?> a1, Class<?> a2) {
        try {
            Method m = c.getMethod(name, a1, a2);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod1x(Class<?> c, String name, Class<?> a1) {
        try {
            Method m = c.getMethod(name, a1);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Streaming scan of the ON-DISK chunk payload for {@code InhabitedTime} + {@code Status} —
     *  builds NO NBT tree, decompresses through a 64KB window (bounded memory), so it works on
     *  monster chunks whose full decompress would OOM (the very chunks most worth protecting).
     *  Returns {@code long[]{inhabitedTime, statusIsFull ? 1 : 0}} or {@code null} on any doubt.
     *  Pure JDK file IO: region path is reconstructed from the storage's folder (reflected by field
     *  type {@link java.nio.file.Path}, name-agnostic). Runs on the same region IO thread as the
     *  intercepted write, before that write touches the file — same safety envelope as readDisk. */
    static long[] diskScanMileage(Object storage, int x, int z) {
        try {
            java.io.File folder = folderOf(storage);
            if (folder == null) {
                return null;
            }
            java.io.File reg = new java.io.File(folder, "r." + (x >> 5) + "." + (z >> 5) + ".mca");
            if (!reg.isFile()) {
                return null;
            }
            int li = (x & 31) + (z & 31) * 32;
            java.io.InputStream payload;
            int comp;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(reg, "r");
            try {
                raf.seek(li * 4L);
                int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), cnt = raf.read();
                int off = (b0 << 16) | (b1 << 8) | b2;
                if (off <= 0 || cnt <= 0) {
                    raf.close();
                    return null;
                }
                raf.seek(off * 4096L);
                int len = raf.readInt();
                comp = raf.read();
                if ((comp & 0x80) != 0) { // oversized: payload lives in external c.<x>.<z>.mcc
                    raf.close();
                    java.io.File mcc = new java.io.File(folder, "c." + x + "." + z + ".mcc");
                    if (!mcc.isFile()) {
                        return null;
                    }
                    payload = new java.io.FileInputStream(mcc);
                    comp &= 0x7f;
                } else {
                    if (len <= 1) {
                        raf.close();
                        return null;
                    }
                    final long limit = len - 1L;
                    final java.io.RandomAccessFile fraf = raf;
                    payload = new java.io.InputStream() { // bounded view over the RAF, closes it
                        long left = limit;
                        @Override public int read() throws java.io.IOException {
                            if (left <= 0) return -1;
                            int v = fraf.read(); if (v >= 0) left--; return v;
                        }
                        @Override public int read(byte[] b, int o, int n) throws java.io.IOException {
                            if (left <= 0) return -1;
                            int v = fraf.read(b, o, (int) Math.min(n, left));
                            if (v > 0) left -= v; return v;
                        }
                        @Override public void close() throws java.io.IOException { fraf.close(); }
                    };
                }
            } catch (Throwable t) {
                try { raf.close(); } catch (Throwable ignored) { }
                throw t;
            }
            java.io.InputStream in;
            if (comp == 1) {
                in = new java.util.zip.GZIPInputStream(payload, 65536);
            } else if (comp == 2) {
                in = new java.util.zip.InflaterInputStream(payload, new java.util.zip.Inflater(), 65536);
            } else {
                in = payload; // 3 = uncompressed
            }
            try {
                return scanItStatus(in);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static final byte[] PAT_IT = new byte[]{4, 0, 13, 'I','n','h','a','b','i','t','e','d','T','i','m','e'};
    private static final byte[] PAT_ST = new byte[]{8, 0, 6, 'S','t','a','t','u','s'};

    /** Windowed pattern scan: finds the InhabitedTime TAG_Long and Status TAG_String without parsing.
     *  Keeps a small overlap so matches spanning window edges are not lost. */
    private static long[] scanItStatus(java.io.InputStream in) throws java.io.IOException {
        final int WIN = 65536, KEEP = 64;
        byte[] buf = new byte[WIN + KEEP];
        int have = 0;
        long it = Long.MIN_VALUE;
        int full = -1;
        long guard = 0;
        while (true) {
            int r = in.read(buf, have, buf.length - have);
            if (r < 0) {
                break;
            }
            have += r;
            guard += r;
            if (guard > (64L << 20) * 8) { // hard stop after ~512MB decompressed: give up, fail open
                return null;
            }
            if (it == Long.MIN_VALUE) {
                int i = indexOf(buf, have, PAT_IT);
                if (i >= 0 && i + PAT_IT.length + 8 <= have) {
                    long v = 0;
                    for (int k = 0; k < 8; k++) {
                        v = (v << 8) | (buf[i + PAT_IT.length + k] & 0xFFL);
                    }
                    it = v;
                }
            }
            if (full < 0) {
                int i = indexOf(buf, have, PAT_ST);
                if (i >= 0 && i + PAT_ST.length + 2 <= have) {
                    int sl = ((buf[i + PAT_ST.length] & 0xFF) << 8) | (buf[i + PAT_ST.length + 1] & 0xFF);
                    if (i + PAT_ST.length + 2 + sl <= have && sl > 0 && sl < 64) {
                        String s = new String(buf, i + PAT_ST.length + 2, sl, java.nio.charset.StandardCharsets.ISO_8859_1);
                        int colon = s.indexOf(':');
                        full = (colon >= 0 ? s.substring(colon + 1) : s).equalsIgnoreCase("full") ? 1 : 0;
                    }
                }
            }
            if (it != Long.MIN_VALUE && full >= 0) {
                return new long[]{it, full};
            }
            if (have > KEEP) { // slide window, keep tail for cross-boundary matches
                System.arraycopy(buf, have - KEEP, buf, 0, KEEP);
                have = KEEP;
            }
        }
        if (it != Long.MIN_VALUE && full >= 0) {
            return new long[]{it, full};
        }
        return null;
    }

    private static int indexOf(byte[] buf, int have, byte[] pat) {
        outer:
        for (int i = 0; i <= have - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (buf[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static final ConcurrentHashMap<Class<?>, Field> STORAGE_FOLDER = new ConcurrentHashMap<>();

    /** The storage's region folder, reflected by field TYPE (java.nio.file.Path), name-agnostic. */
    private static java.io.File folderOf(Object storage) {
        try {
            Field f = STORAGE_FOLDER.computeIfAbsent(storage.getClass(), c -> {
                for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                    for (Field fd : k.getDeclaredFields()) {
                        if (java.nio.file.Path.class.isAssignableFrom(fd.getType())) {
                            fd.setAccessible(true);
                            return fd;
                        }
                    }
                }
                return null;
            });
            if (f == null) {
                return null;
            }
            Object p = f.get(storage);
            return (p instanceof java.nio.file.Path) ? ((java.nio.file.Path) p).toFile() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The CompoundTag currently stored on disk for (x,z), or null (no disk data / unreadable). */
    static Object readDisk(Object storage, int x, int z) {
        try {
            Object pos = newChunkPos(storage, x, z);
            if (pos == null) {
                return null;
            }
            Method read = READ.computeIfAbsent(storage.getClass(), c -> findRead(c, pos.getClass()));
            if (read == null) {
                return null;
            }
            return read.invoke(storage, pos);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Memory-free "does a chunk already exist on disk at (x,z)?" — uses the already-loaded RegionFile
     * (or opens only its ~8KB header) and checks the header offset. NO decompression, so it works
     * under the memory exhaustion where {@link #readDisk} would OOM. Returns false on any uncertainty.
     */
    static boolean diskChunkExists(Object storage, int x, int z) {
        try {
            Object rf = regionFile(storage, x, z);
            if (rf == null) {
                return false; // no region file → no chunk here
            }
            Object pos = newChunkPos(storage, x, z);
            if (pos == null) {
                return false;
            }
            Method has = HAS_CHUNK.computeIfAbsent(rf.getClass(), c -> findMethod1(c, "hasChunk", pos.getClass()));
            if (has != null) {
                return Boolean.TRUE.equals(has.invoke(rf, pos));
            }
            // fallback: a non-null chunk data stream means the chunk has data on disk
            Method gs = GET_STREAM.computeIfAbsent(rf.getClass(), c -> findMethod1(c, "getChunkDataInputStream", pos.getClass()));
            if (gs != null) {
                Object s = gs.invoke(rf, pos);
                if (s != null) {
                    if (s instanceof java.io.Closeable) {
                        try { ((java.io.Closeable) s).close(); } catch (Throwable ignored) { }
                    }
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The loaded (cached) RegionFile for (x,z), else the existing on-disk one; null if none. */
    private static Object regionFile(Object storage, int x, int z) {
        Class<?> c = storage.getClass();
        try {
            Method loaded = RF_LOADED.computeIfAbsent(c, k -> findMethod2i(k, "moonrise$getRegionFileIfLoaded"));
            if (loaded != null) {
                Object rf = loaded.invoke(storage, x, z);
                if (rf != null) {
                    return rf;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the existing-file lookup
        }
        try {
            Method exists = RF_EXISTS.computeIfAbsent(c, k -> findMethod2i(k, "moonrise$getRegionFileIfExists"));
            if (exists != null) {
                return exists.invoke(storage, x, z);
            }
        } catch (Throwable ignored) {
            // give up
        }
        return null;
    }

    private static Method findMethod1(Class<?> c, String name, Class<?> arg) {
        try {
            Method m = c.getMethod(name, arg);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod2i(Class<?> c, String name) {
        try {
            Method m = c.getMethod(name, int.class, int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    /** WriteData's first component: the CompoundTag about to be written (null if unreadable). */
    static Object writeDataInput(Object writeData) {
        if (writeData == null) {
            return null;
        }
        try {
            Method in = WD_INPUT.computeIfAbsent(writeData.getClass(), NbtReflect::findWriteDataInput);
            return in == null ? null : in.invoke(writeData);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true iff this WriteData is a DELETE op (legitimate chunk removal — never a corruption). */
    static boolean writeDataIsDelete(Object writeData) {
        if (writeData == null) {
            return false;
        }
        try {
            Method res = WD_RESULT.computeIfAbsent(writeData.getClass(), NbtReflect::findWriteDataResult);
            if (res == null) {
                return false;
            }
            Object r = res.invoke(writeData);
            return r != null && "DELETE".equals(r.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** {x,z} from a ChunkPos object; null if unreadable. */
    static int[] chunkPosXZ(Object chunkPos) {
        if (chunkPos == null) {
            return null;
        }
        try {
            Field[] xz = CHUNKPOS_XZ.computeIfAbsent(chunkPos.getClass(), NbtReflect::findChunkPosXZ);
            if (xz == null) {
                return null;
            }
            return new int[] {xz[0].getInt(chunkPos), xz[1].getInt(chunkPos)};
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── resolvers (cached) ─────────────────────────

    private static Method findGetStringOr(Class<?> c) {
        try {
            return c.getMethod("getStringOr", String.class, String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findGetString(Class<?> c) {
        try {
            return c.getMethod("getString", String.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findRead(Class<?> storageClass, Class<?> chunkPosClass) {
        try {
            Method m = storageClass.getMethod("read", chunkPosClass);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newChunkPos(Object storage, int x, int z) {
        try {
            ClassLoader cl = storage.getClass().getClassLoader();
            Constructor<?> ctor = CHUNKPOS_CTOR.computeIfAbsent(cl, l -> {
                try {
                    return l.loadClass("net.minecraft.world.level.ChunkPos")
                            .getConstructor(int.class, int.class);
                } catch (Throwable t) {
                    return null;
                }
            });
            return ctor == null ? null : ctor.newInstance(x, z);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findWriteDataInput(Class<?> c) {
        // record accessor input() first; then any zero-arg method returning a CompoundTag-ish type.
        try {
            Method m = c.getMethod("input");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            // fall through to scan
        }
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0
                    && m.getReturnType().getName().endsWith(".CompoundTag")) {
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    // keep scanning
                }
            }
        }
        return null;
    }

    private static Method findWriteDataResult(Class<?> c) {
        try {
            Method m = c.getMethod("result");
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            // fall through
        }
        for (Method m : c.getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().isEnum()) {
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    // keep scanning
                }
            }
        }
        return null;
    }

    private static Field[] findChunkPosXZ(Class<?> c) {
        try {
            Field x = c.getField("x");
            Field z = c.getField("z");
            return new Field[] {x, z};
        } catch (Throwable t) {
            return null;
        }
    }
}
