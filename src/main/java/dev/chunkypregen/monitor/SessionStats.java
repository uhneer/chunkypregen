package dev.chunkypregen.monitor;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory session statistics. Resets on world unload/server stop.
 * Not persisted to disk — intended for the /chunkypregen stats command.
 */
public final class SessionStats {

    private static final Map<RegistryKey<World>, Integer> jobsFired        = new HashMap<>();
    private static final Map<RegistryKey<World>, Long>    chunksEstimated  = new HashMap<>();
    private static long sessionStartMs = System.currentTimeMillis();
    private static boolean paused      = false;
    private static long pauseStartMs   = 0;
    private static long totalPausedMs  = 0;

    private SessionStats() {}

    /**
     * Record a new job starting. Estimates chunk count from radius and shape.
     * Circle: π·r², Square: (2r)² = 4r²
     */
    public static void recordJob(RegistryKey<World> dim, int radiusChunks, String shape) {
        jobsFired.merge(dim, 1, Integer::sum);
        long estimate = "circle".equals(shape)
                ? (long) (Math.PI * (double) radiusChunks * radiusChunks)
                : 4L * radiusChunks * radiusChunks;
        chunksEstimated.merge(dim, estimate, Long::sum);
    }

    public static void recordPause() {
        if (!paused) {
            paused = true;
            pauseStartMs = System.currentTimeMillis();
        }
    }

    public static void recordResume() {
        if (paused) {
            totalPausedMs += System.currentTimeMillis() - pauseStartMs;
            paused = false;
        }
    }

    public static Map<RegistryKey<World>, Integer> getJobsFired() {
        return Collections.unmodifiableMap(jobsFired);
    }

    public static Map<RegistryKey<World>, Long> getChunksEstimated() {
        return Collections.unmodifiableMap(chunksEstimated);
    }

    /** Returns active (non-paused) milliseconds elapsed this session. */
    public static long getActiveMs() {
        long elapsed = System.currentTimeMillis() - sessionStartMs;
        long pausedMs = totalPausedMs + (paused ? System.currentTimeMillis() - pauseStartMs : 0);
        return Math.max(0, elapsed - pausedMs);
    }

    public static void reset() {
        jobsFired.clear();
        chunksEstimated.clear();
        sessionStartMs = System.currentTimeMillis();
        paused = false;
        pauseStartMs = 0;
        totalPausedMs = 0;
    }
}
