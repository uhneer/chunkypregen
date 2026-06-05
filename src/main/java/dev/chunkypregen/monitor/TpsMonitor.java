package dev.chunkypregen.monitor;

import net.minecraft.server.MinecraftServer;

/**
 * Thin wrapper around MinecraftServer.getAverageTickTime() for TPS calculation.
 *
 * Compatibility:
 *   - MC 1.19+ / Yarn mappings: getAverageTickTime() returns a float of milliseconds
 *     averaged over the last 100 ticks. Confirmed present in 1.21.11+build.6.
 *   - Theoretically compatible with any MC version that has this method (1.19–present).
 *   - If Mojang renames the method in a future version, this class will fail to compile
 *     with a clear error rather than silently misbehaving.
 */
public final class TpsMonitor {

    private TpsMonitor() {}

    /** Returns the current server TPS, capped at 20.0. */
    public static double getTps(MinecraftServer server) {
        float avgMs = server.getAverageTickTime();
        if (avgMs <= 0) return 20.0;
        return Math.min(20.0, 1000.0 / avgMs);
    }
}
