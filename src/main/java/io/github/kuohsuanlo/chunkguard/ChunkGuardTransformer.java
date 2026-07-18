package io.github.kuohsuanlo.chunkguard;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects the write barrier into {@code net.minecraft.world.level.chunk.storage.RegionFileStorage}.
 *
 * <p>Two entry-guard insertions, both at method start ({@code if (skip) return;}):</p>
 * <ul>
 *   <li><b>{@code moonrise$finishWrite(int x, int z, WriteData)}</b> — the primary chokepoint for
 *       every scheduled chunk save on Paper. A {@code void} early-return skips the write cleanly:
 *       the region file is never touched, so the good on-disk data survives (the deterministic form
 *       of the observed OOM "self-recovery"). We call {@link ChunkGuardRuntime#shouldSkipMoonrise}.</li>
 *   <li><b>{@code write(ChunkPos, CompoundTag)}</b> — the vanilla / IOWorker fallback path (world
 *       upgrade, non-Moonrise builds). Calls {@link ChunkGuardRuntime#shouldSkipVanilla}.</li>
 * </ul>
 *
 * <p><b>Version tolerance:</b> matched purely by class + method name and descriptor shape. If a
 * method isn't found (older/newer/other server), nothing is injected and the barrier simply doesn't
 * arm on that path — never an error, never blocking boot. Any exception during transform → the
 * original bytes are returned unchanged (that class stays vanilla). We deliberately do NOT touch the
 * {@code WriteData.result()==DELETE} branch inside finishWrite, and we never fabricate a DELETE —
 * that would {@code regionFile.clear(pos)} and destroy the very data we protect.</p>
 */
public final class ChunkGuardTransformer implements ClassFileTransformer {

    static final String STORAGE = "net/minecraft/world/level/chunk/storage/RegionFileStorage";
    static final String CHUNK_DATA = "net/minecraft/world/level/chunk/storage/SerializableChunkData";
    static final String RUNTIME = "io/github/kuohsuanlo/chunkguard/ChunkGuardRuntime";

    static final String FINISH_WRITE = "moonrise$finishWrite";
    static final String VANILLA_WRITE = "write";
    static final String VANILLA_WRITE_DESC =
            "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V";

    static final String D_MOONRISE = "(Ljava/lang/Object;IILjava/lang/Object;)Z";
    static final String D_VANILLA = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";

    /** used by AgentMain.retransformIfLoaded — PUBLIC on purpose: after appendToBootstrapClassLoaderSearch
     *  this class loads from the bootstrap loader while ChunkGuardAgentMain stays on the app loader;
     *  a package-private access across that loader boundary throws IllegalAccessError (would kill premain). */
    public static boolean isTarget(String internalName) {
        return STORAGE.equals(internalName) || CHUNK_DATA.equals(internalName);
    }

    private static boolean isFinishWrite(String name, String desc) {
        // moonrise$finishWrite(int, int, <WriteData>) : void  — match name + (IIL…;)V shape
        return FINISH_WRITE.equals(name) && desc.startsWith("(IIL") && desc.endsWith(";)V");
    }

    private static boolean isVanillaWrite(String name, String desc) {
        return VANILLA_WRITE.equals(name) && VANILLA_WRITE_DESC.equals(desc);
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // ★類名檢查必須在最前面、且在碰任何 ChunkGuardRuntime 符號之前(26.2-4 修正):transform()
        // 會被「每一個」類載入呼叫,包含本 agent 自己的類——若先呼叫 ChunkGuardRuntime.enabled(),
        // 在 Runtime 自身(或與其初始化交錯的類)載入期間會觸發 ClassCircularityError,例外落在
        // try 之外被吞、RegionFileStorage 保持 vanilla = 寫入屏障靜默失效(s21 2026-07-17 實案)。
        if (className == null || (!STORAGE.equals(className) && !CHUNK_DATA.equals(className))) {
            return null;
        }
        if (!ChunkGuardRuntime.enabled()) {
            return null;
        }
        if (CHUNK_DATA.equals(className)) {
            return transformChunkData(classfileBuffer);
        }
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            boolean[] patched = {false};
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                    if (isFinishWrite(name, desc)) {
                        patched[0] = true;
                        return new GuardInserter(mv, GuardInserter.MOONRISE);
                    }
                    if (isVanillaWrite(name, desc)) {
                        patched[0] = true;
                        return new GuardInserter(mv, GuardInserter.VANILLA);
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            if (!patched[0]) {
                System.err.println("[ChunkGuard] RegionFileStorage found but no write chokepoint matched"
                        + " — barrier NOT armed (server left unchanged). Version drift?");
                return null;
            }
            ChunkGuardRuntime.injected = true;
            System.out.println("[ChunkGuard] armed: write barrier injected into RegionFileStorage");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[ChunkGuard] transform failed — leaving RegionFileStorage vanilla: " + t);
            t.printStackTrace();
            return null;
        }
    }

    /**
     * READ GUARD (26.2-3): injects {@code ChunkGuardRuntime.onChunkNbtRead(tag)} at the entry of
     * {@code SerializableChunkData.parse(LevelHeightAccessor, PalettedContainerFactory, CompoundTag)}
     * — the common parse chokepoint for every chunk loaded from disk (moonrise + vanilla paths both
     * funnel here). The runtime may HEAL a mislabeled ex-full chunk (proto status carrying mileage)
     * by rewriting its Status to full in-place, BEFORE the game decides to regenerate over it.
     * Matched tolerantly: static method named "parse" whose descriptor contains a CompoundTag param;
     * the tag's local slot is computed from the descriptor. Nothing matched → not armed, no error.
     */
    private static byte[] transformChunkData(byte[] classfileBuffer) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            boolean[] patched = {false};
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                    if ((access & Opcodes.ACC_STATIC) != 0 && "parse".equals(name)
                            && desc.contains("Lnet/minecraft/nbt/CompoundTag;")) {
                        int slot = tagSlot(desc);
                        if (slot >= 0) {
                            patched[0] = true;
                            final int tagVar = slot;
                            return new MethodVisitor(Opcodes.ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    super.visitVarInsn(Opcodes.ALOAD, tagVar);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                                            "onChunkNbtRead", "(Ljava/lang/Object;)V", false);
                                }
                            };
                        }
                    }
                    return mv;
                }
            };
            cr.accept(cv, 0);
            if (!patched[0]) {
                System.err.println("[ChunkGuard] SerializableChunkData found but no parse(CompoundTag)"
                        + " matched — read guard NOT armed (write barrier unaffected). Version drift?");
                return null;
            }
            System.out.println("[ChunkGuard] armed: read guard injected into SerializableChunkData.parse");
            return cw.toByteArray();
        } catch (Throwable t) {
            System.err.println("[ChunkGuard] read-guard transform failed — leaving SerializableChunkData vanilla: " + t);
            t.printStackTrace();
            return null;
        }
    }

    /** Local-variable slot of the first CompoundTag param in a STATIC method descriptor; -1 if none. */
    private static int tagSlot(String desc) {
        int slot = 0;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                String ref = desc.substring(i, end + 1);
                if ("Lnet/minecraft/nbt/CompoundTag;".equals(ref)) {
                    return slot;
                }
                slot += 1;
                i = end + 1;
            } else if (c == 'J' || c == 'D') {
                slot += 2;
                i++;
            } else if (c == '[') {
                i++; // array marker: type char follows; treat whole array as 1 slot
                while (i < desc.length() && desc.charAt(i) == '[') i++;
                if (desc.charAt(i) == 'L') i = desc.indexOf(';', i) + 1; else i++;
                slot += 1;
            } else {
                slot += 1;
                i++;
            }
        }
        return -1;
    }

    /** Inserts {@code if (ChunkGuardRuntime.shouldSkip*(…)) return;} at the method entry. */
    private static final class GuardInserter extends MethodVisitor {
        static final int MOONRISE = 0;
        static final int VANILLA = 1;
        private final int kind;

        GuardInserter(MethodVisitor mv, int kind) {
            super(Opcodes.ASM9, mv);
            this.kind = kind;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label cont = new Label();
            if (kind == MOONRISE) {
                // shouldSkipMoonrise(this, chunkX, chunkZ, writeData)
                super.visitVarInsn(Opcodes.ALOAD, 0);   // this (RegionFileStorage)
                super.visitVarInsn(Opcodes.ILOAD, 1);   // chunkX
                super.visitVarInsn(Opcodes.ILOAD, 2);   // chunkZ
                super.visitVarInsn(Opcodes.ALOAD, 3);   // WriteData
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "shouldSkipMoonrise", D_MOONRISE, false);
            } else {
                // shouldSkipVanilla(this, chunkPos, chunkData)
                super.visitVarInsn(Opcodes.ALOAD, 0);   // this
                super.visitVarInsn(Opcodes.ALOAD, 1);   // ChunkPos
                super.visitVarInsn(Opcodes.ALOAD, 2);   // CompoundTag
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "shouldSkipVanilla", D_VANILLA, false);
            }
            super.visitJumpInsn(Opcodes.IFEQ, cont);
            super.visitInsn(Opcodes.RETURN);            // void early-return = clean skip
            super.visitLabel(cont);
            // Empty stack, unchanged locals at method entry → F_SAME.
            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
    }
}
