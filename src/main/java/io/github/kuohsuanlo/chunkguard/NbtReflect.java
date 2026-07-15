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
