package io.github.kuohsuanlo.chunkguard;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java agent entry point. Start the server with {@code -javaagent:ChunkGuardAgent.jar}.
 *
 * <p>What it does: instruments the chunk region-file write chokepoint so that, right before a
 * chunk's NBT is written to disk, {@link ChunkGuardRuntime} inspects it. If the chunk about to be
 * written is a blank / load-failed stub AND the on-disk version still holds real data, the write is
 * SKIPPED — the good disk data survives. This is the deterministic form of the observed
 * "self-recovery" (when a dying server halts before the destructive save, the disk keeps its data).</p>
 *
 * <p>Why an agent, and why bootstrap: Paper (paperclip) loads {@code net.minecraft.*} in an
 * isolated classloader that cannot see the {@code -javaagent} app classloader. We first append this
 * jar (pure JDK + relocated ASM) to the bootstrap classloader — the common ancestor of every
 * classloader — so the call spliced into the NMS write method can reach the same
 * {@link ChunkGuardRuntime} via parent delegation. The runtime reads the chunk NBT REFLECTIVELY
 * (its args are {@code java.lang.Object}), so this jar carries no compile-time NMS dependency and
 * needs no compile-against-NMS template step — the same jar tolerates many Paper versions.</p>
 *
 * <p>Same agent skeleton (bootstrap append + reflective runtime) as the author's other javaagents.</p>
 */
public final class ChunkGuardAgentMain {

    public static final String AUTHOR = "廢土貓大 LogoCat";
    public static final String SITE = "mcfallout.net";

    private ChunkGuardAgentMain() {
    }

    public static void premain(String args, Instrumentation inst) {
        signature();
        bootstrap(inst);
        install(inst);
        ChunkGuardRuntime.maybeStartVerboseLogger();
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("[ChunkGuard] shutdown stats: " + ChunkGuardRuntime.stats()),
                "ChunkGuard-shutdown-stats"));
    }

    public static void agentmain(String args, Instrumentation inst) {
        signature();
        bootstrap(inst);
        install(inst);
        ChunkGuardRuntime.maybeStartVerboseLogger();
        retransformIfLoaded(inst);
    }

    /**
     * Author signature (harmless, purely for attribution): a boot banner line, a system property
     * readable by jcmd/any tool, and a forever-sleeping named daemon thread that shows up in
     * spark / thread dumps. Never affects agent behaviour.
     */
    private static void signature() {
        try {
            System.setProperty("chunkguard.author", AUTHOR + " (" + SITE + ")");
            System.out.println("[ChunkGuard] ChunkGuardAgent —— crafted by " + AUTHOR + " · 廢土 · " + SITE);
            Thread sig = new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "ChunkGuard-signature · " + AUTHOR + " · " + SITE);
            sig.setDaemon(true);
            sig.start();
        } catch (Throwable ignored) {
            // signature failure must never affect the agent
        }
    }

    /** Append this agent jar to the bootstrap classloader so the instrumented NMS write method can
     *  reach {@link ChunkGuardRuntime}. */
    private static void bootstrap(Instrumentation inst) {
        try {
            File self = new File(
                    ChunkGuardAgentMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            inst.appendToBootstrapClassLoaderSearch(new JarFile(self));
            System.out.println("[ChunkGuard] appended to bootstrap classpath: " + self);
        } catch (Throwable t) {
            System.err.println("[ChunkGuard] FATAL: appendToBootstrapClassLoaderSearch failed: " + t);
            t.printStackTrace();
        }
    }

    private static void install(Instrumentation inst) {
        inst.addTransformer(new ChunkGuardTransformer(), true);
        System.out.println("[ChunkGuard] agent installed (transformer registered)"
                + (ChunkGuardRuntime.shadow() ? " [SHADOW mode: detect-only, never skips]" : "")
                + (ChunkGuardRuntime.enabled() ? "" : " [DISABLED]"));
    }

    /** agentmain (dynamic attach): the target NMS classes may already be loaded — retransform them. */
    private static void retransformIfLoaded(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            return;
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (ChunkGuardTransformer.isTarget(c.getName().replace('.', '/'))) {
                try {
                    inst.retransformClasses(c);
                } catch (Throwable t) {
                    System.err.println("[ChunkGuard] retransform failed for " + c.getName() + ": " + t);
                }
            }
        }
    }
}
