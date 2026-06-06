package dev.chunkypregen.integration;

import dev.chunkypregen.ChunkyPregen;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * Thin wrapper around Chunky's ChunkyAPI to check whether a task is actively
 * running for a given dimension.
 *
 * Uses Chunky's public API via reflection so that chunkypregen can be compiled
 * without Chunky as a hard compile dependency.
 *
 * The reflection approach resolves once at first call:
 *   ChunkyProvider.get()  →  Chunky  →  getApi()  →  ChunkyAPI.isRunning(worldKey)
 *
 * If reflection fails entirely (e.g. Chunky updated and renamed something),
 * isTaskRunning() always returns false so GenerationTracker falls back to the
 * 200-tick watchdog timeout rather than hanging forever.
 */
public final class ChunkyIntegration {

    private static final String CHUNKY_PROVIDER = "org.popcraft.chunky.ChunkyProvider";
    private static final String CHUNKY_API_IFACE = "org.popcraft.chunky.api.ChunkyAPI";

    // volatile: resolved/available are written once on one thread and read from the server
    // tick thread — without volatile the JVM may cache the old value indefinitely.
    private static volatile boolean resolved  = false;
    private static volatile boolean available = false;
    private static java.lang.reflect.Method providerGet  = null;  // ChunkyProvider.get()
    private static java.lang.reflect.Method chunkyGetApi = null;  // Chunky.getApi()
    private static java.lang.reflect.Method apiIsRunning = null;  // ChunkyAPI.isRunning(String)
    private static volatile boolean progressCallbackRegistered = false;

    private ChunkyIntegration() {}

    /**
     * Directly sets the thread pool size inside Chunky's TaskScheduler.
     *
     * Reflection chain:
     *   ChunkyProvider.get()  →  Chunky.scheduler  →  TaskScheduler.executor
     *     →  cast to ThreadPoolExecutor  →  setCorePoolSize / setMaximumPoolSize
     *
     * Safe to call at any time; silently no-ops if reflection fails or if
     * Chunky's internals changed in a future version.
     */
    public static void setThreadCount(int threads) {
        resolve();
        if (!available) return;
        try {
            Object chunky = providerGet.invoke(null);
            if (chunky == null) return;

            java.lang.reflect.Field schedulerField = chunky.getClass().getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            Object scheduler = schedulerField.get(chunky);
            if (scheduler == null) return;

            java.lang.reflect.Field executorField = scheduler.getClass().getDeclaredField("executor");
            executorField.setAccessible(true);
            Object executor = executorField.get(scheduler);
            if (!(executor instanceof java.util.concurrent.ThreadPoolExecutor tpe)) return;

            int clamped = Math.max(1, threads);
            // Must set max first if increasing, core first if decreasing
            if (clamped > tpe.getCorePoolSize()) {
                tpe.setMaximumPoolSize(clamped);
                tpe.setCorePoolSize(clamped);
            } else {
                tpe.setCorePoolSize(clamped);
                tpe.setMaximumPoolSize(clamped);
            }
            ChunkyPregen.LOGGER.info("[ChunkyPregen] Chunky thread pool resized to {} thread(s).", clamped);
        } catch (Exception e) {
            ChunkyPregen.LOGGER.warn("[ChunkyPregen] Could not set Chunky thread count: {}", e.getMessage());
        }
    }

    /**
     * Returns true if Chunky currently has a running (not paused, not complete)
     * generation task for the given dimension registry key.
     *
     * Thread-safe: only reads resolved fields after the one-time setup.
     */
    public static boolean isTaskRunning(RegistryKey<World> dim) {
        resolve();
        if (!available) return false;
        try {
            Object chunky = providerGet.invoke(null);          // ChunkyProvider.get() → Chunky
            if (chunky == null) return false;
            Object api    = chunkyGetApi.invoke(chunky);       // chunky.getApi() → ChunkyAPI
            if (api == null) return false;
            String worldKey = dim.getValue().toString();       // e.g. "minecraft:overworld"
            return (boolean) apiIsRunning.invoke(api, worldKey);
        } catch (Exception e) {
            ChunkyPregen.LOGGER.debug("[ChunkyPregen] ChunkyAPI.isRunning() reflective call failed: {}", e.getMessage());
            return false;
        }
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> providerCls = Class.forName(CHUNKY_PROVIDER);
            providerGet = providerCls.getMethod("get");

            // Resolve Chunky.getApi() — return type is ChunkyAPI
            // We can't reference ChunkyAPI by class literal, so look it up reflectively.
            Class<?> chunkyCls = Class.forName("org.popcraft.chunky.Chunky");
            chunkyGetApi = chunkyCls.getMethod("getApi");

            Class<?> apiCls = Class.forName(CHUNKY_API_IFACE);
            apiIsRunning = apiCls.getMethod("isRunning", String.class);

            available = true;
            ChunkyPregen.LOGGER.info("[ChunkyPregen] Resolved ChunkyAPI via reflection — using API poll for task completion.");
        } catch (Exception e) {
            available = false;
            ChunkyPregen.LOGGER.warn("[ChunkyPregen] Could not resolve ChunkyAPI via reflection ({}): {}. " +
                    "Falling back to watchdog timeout for completion detection.", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Registers a direct callback with ChunkyAPI.onGenerationProgress so the HUD
     * gets the percentage on every Chunky tick without relying on chat message interception.
     *
     * GenerationProgressEvent is a Java Record — accessor is event.progress() returning float (0–100).
     * Safe to call multiple times; only registers once.
     */
    public static void registerProgressCallback() {
        if (progressCallbackRegistered) return;
        resolve();
        if (!available) return;
        try {
            Object chunky = providerGet.invoke(null);
            if (chunky == null) return;
            Object api = chunkyGetApi.invoke(chunky);
            if (api == null) return;

            Class<?> eventClass = Class.forName("org.popcraft.chunky.api.event.task.GenerationProgressEvent");
            java.lang.reflect.Method progressGetter = eventClass.getMethod("progress");

            java.lang.reflect.Method onProgress = api.getClass().getMethod(
                    "onGenerationProgress", java.util.function.Consumer.class);

            java.util.function.Consumer<Object> callback = event -> {
                try {
                    float pct = (float) progressGetter.invoke(event);
                    dev.chunkypregen.monitor.HudData.currentRingPct = pct;
                } catch (Exception ignored) {}
            };

            onProgress.invoke(api, callback);
            progressCallbackRegistered = true;
            ChunkyPregen.LOGGER.info("[ChunkyPregen] Registered Chunky progress callback for HUD.");
        } catch (Exception e) {
            ChunkyPregen.LOGGER.warn("[ChunkyPregen] Could not register progress callback: {}", e.getMessage());
        }
    }

    /** True if the ChunkyAPI reflection was resolved successfully at startup. */
    public static boolean isAvailable() {
        resolve();
        return available;
    }
}
