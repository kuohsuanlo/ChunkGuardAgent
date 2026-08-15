package io.github.kuohsuanlo.chunkguard;

/**
 * 交付 #180 驗證:實體/POI 存檔(根標籤沒有 Status)不得被寫入屏障判斷、更不得被丟棄。
 * 用假的 CompoundTag/ChunkPos/storage 直接驅動真實的 NbtReflect + ChunkGuardRuntime。
 * 假 storage 一旦被碰就記錄——修好之後,entities 路徑必須「零接觸硬碟」。
 * 以 -Dchunkguard.lowHeapMB=999999 強制走低記憶體分支(bug 現形的那條)。
 */
public class Issue180Test {

    static boolean storageTouched = false;

    /** 假 RegionFileStorage:任何反射查找都會走到它,被碰就記一筆(且沒有任何可用方法 → 反射回退)。 */
    public static class FakeStorage {
        public java.nio.file.Path folder = java.nio.file.Paths.get("/nonexistent-chunkguard-test");
        public Object read(Object pos) { storageTouched = true; return null; }
        public Object moonrise$getRegionFileIfLoaded(int x, int z) { storageTouched = true; return null; }
        public Object moonrise$getRegionFileIfExists(int x, int z) { storageTouched = true; return null; }
    }

    /** 假 ChunkPos:NbtReflect.chunkPosXZ 反射讀 x/z 欄位。 */
    public static class FakeChunkPos {
        public int x = 212;
        public int z = -201;
    }

    /** entities / POI 的根標籤:完全沒有 Status 欄位 → getStringOr 回傳呼叫端給的預設值。 */
    public static class EntitiesTag {
        public String getStringOr(String key, String def) { return def; }
        public long getLongOr(String key, long def) { return def; }
        public int getIntOr(String key, int def) { return def; }
        public Object get(String key) { return null; }   // 沒有 sections
    }

    /** terrain proto chunk:有 Status。 */
    public static class ProtoTag {
        public String getStringOr(String key, String def) {
            return "Status".equals(key) ? "minecraft:structure_starts" : def;
        }
        public long getLongOr(String key, long def) { return def; }
        public int getIntOr(String key, int def) { return def; }
        // 真實 proto chunk 的根標籤一定有 sections(空殼時 list 很短)
        public Object get(String key) { return "sections".equals(key) ? new java.util.ArrayList<Object>() : null; }
    }

    /** terrain full chunk。 */
    public static class FullTag {
        public String getStringOr(String key, String def) {
            return "Status".equals(key) ? "minecraft:full" : def;
        }
        public long getLongOr(String key, long def) { return "InhabitedTime".equals(key) ? 90_000_000L : def; }
        public int getIntOr(String key, int def) { return def; }
        public Object get(String key) { return null; }
    }

    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ✅ PASS " : "  ❌ FAIL ") + name + "  " + detail);
        if (!ok) fails++;
    }

    public static void main(String[] args) {
        System.out.println("== 交付 #180 驗證(lowHeapMB=" + System.getProperty("chunkguard.lowHeapMB") + ") ==");

        // 1) statusOf:沒有 Status 必須回 null(不是 "")
        String s = NbtReflect.statusOf(new EntitiesTag());
        check("statusOf(entities 標籤) == null", s == null, "實得: " + (s == null ? "null" : "\"" + s + "\""));

        // 2) statusOf 對真 terrain 仍正常(無回歸)
        check("statusOf(proto) == structure_starts",
                "structure_starts".equals(NbtReflect.statusOf(new ProtoTag())),
                "實得: " + NbtReflect.statusOf(new ProtoTag()));
        check("statusOf(full) == full",
                "full".equals(NbtReflect.statusOf(new FullTag())),
                "實得: " + NbtReflect.statusOf(new FullTag()));

        // 3) 核心:entities 存檔不得被丟棄,且不得碰硬碟
        storageTouched = false;
        boolean skipEntities = ChunkGuardRuntime.shouldSkipVanilla(new FakeStorage(), new FakeChunkPos(), new EntitiesTag());
        check("entities 存檔不被丟棄", !skipEntities, "shouldSkip=" + skipEntities);
        check("entities 路徑零接觸硬碟", !storageTouched, "storageTouched=" + storageTouched);
        check("計數器 nonTerrainAllowed 有記到", ChunkGuardRuntime.nonTerrainAllowed.get() >= 1,
                "nonTerrainAllowed=" + ChunkGuardRuntime.nonTerrainAllowed.get());
        check("skipped 仍為 0", ChunkGuardRuntime.skipped.get() == 0,
                "skipped=" + ChunkGuardRuntime.skipped.get());
        check("lowHeapFailsafe 仍為 0(沒走保險絲)", ChunkGuardRuntime.lowHeapFailsafe.get() == 0,
                "lowHeapFailsafe=" + ChunkGuardRuntime.lowHeapFailsafe.get());

        // 4) 無回歸:terrain proto 仍會被判斷(會去碰硬碟做比對)
        storageTouched = false;
        ChunkGuardRuntime.shouldSkipVanilla(new FakeStorage(), new FakeChunkPos(), new ProtoTag());
        check("terrain proto 仍會查硬碟(保護未被削弱)", storageTouched, "storageTouched=" + storageTouched);

        // 5) 無回歸:full 快速通道(高里程 → 直接放行,零硬碟接觸)
        storageTouched = false;
        boolean skipFull = ChunkGuardRuntime.shouldSkipVanilla(new FakeStorage(), new FakeChunkPos(), new FullTag());
        check("高里程 full 直接放行", !skipFull, "shouldSkip=" + skipFull);

        System.out.println("== " + (fails == 0 ? "全部通過" : fails + " 項失敗") + " ==");
        System.out.println("stats: " + ChunkGuardRuntime.stats());
        System.exit(fails == 0 ? 0 : 1);
    }
}
