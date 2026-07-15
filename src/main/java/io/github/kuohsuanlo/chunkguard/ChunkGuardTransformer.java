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
    static final String RUNTIME = "io/github/kuohsuanlo/chunkguard/ChunkGuardRuntime";

    static final String FINISH_WRITE = "moonrise$finishWrite";
    static final String VANILLA_WRITE = "write";
    static final String VANILLA_WRITE_DESC =
            "(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V";

    static final String D_MOONRISE = "(Ljava/lang/Object;IILjava/lang/Object;)Z";
    static final String D_VANILLA = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z";

    /** used by AgentMain.retransformIfLoaded */
    static boolean isTarget(String internalName) {
        return STORAGE.equals(internalName);
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
        if (className == null || !STORAGE.equals(className) || !ChunkGuardRuntime.enabled()) {
            return null;
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
