package dev.chunkypregen.monitor;

import dev.chunkypregen.config.ChunkyPregenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intercepts Chunky's per-second progress spam and replaces it with a
 * configurable-interval summary broadcast.
 *
 * Chunky 1.4.x progress message format (confirmed 1.4.55):
 *   [Chunky] Task running for minecraft:overworld. Processed: 3689 chunks (0.24%), ETA: 4:52:59, Rate: 88.8 cps, Current: 22, -5
 *
 * Compatibility:
 *   - Relies on ServerMessageEvents.ALLOW_GAME_MESSAGE (fabric-message-api-v1 ≥ 6.x, MC 1.19+).
 *   - If Chunky changes its message format this relay will stop working but will
 *     not crash — messages will pass through unfiltered instead.
 *   - Theoretically compatible with any Chunky 1.3+ (format stable since 1.3).
 */
public final class ProgressRelay {

    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[Chunky\\] Task running for (\\S+)\\. Processed: (\\d+) chunks \\(([\\d.]+)%\\), ETA: ([^,]+), Rate: ([\\d.]+) cps"
    );

    // Stores the latest progress line per dimension key (e.g. "minecraft:overworld")
    private static final Map<String, String> latestProgress = new ConcurrentHashMap<>();
    private static int relayTickCounter = 0;

    /** Returns true if this message is a Chunky progress line and should be suppressed. */
    public static boolean isChunkyProgress(String msg) {
        return msg != null && msg.contains("[Chunky] Task running for");
    }

    /** Parse and cache the latest progress for this message's dimension. */
    public static void update(String msg) {
        Matcher m = PROGRESS_PATTERN.matcher(msg);
        if (!m.find()) return;
        String dim   = m.group(1);
        String pct   = m.group(3);
        String eta   = m.group(4).trim();
        String rate  = m.group(5);
        latestProgress.put(dim, pct + "% — ETA " + eta + " — " + rate + " cps");
        // Feed the HUD with the raw percentage so the progress bar can update live
        try {
            HudData.currentRingPct = Float.parseFloat(pct);
        } catch (NumberFormatException ignored) {}
    }

    /** Call from the server tick event. Broadcasts a summary at the configured interval. */
    public static void onTick(MinecraftServer server) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        if (!cfg.progressRelay || latestProgress.isEmpty()) return;

        relayTickCounter++;
        int intervalTicks = cfg.progressRelayIntervalMinutes * 20 * 60;
        if (relayTickCounter < intervalTicks) return;
        relayTickCounter = 0;

        StringBuilder sb = new StringBuilder("§6[ChunkyPregen] §7Active generation:");
        for (Map.Entry<String, String> e : latestProgress.entrySet()) {
            String dim = e.getKey().replace("minecraft:", "");
            sb.append("\n  §7").append(dim).append(": §f").append(e.getValue());
        }
        server.getPlayerManager().broadcast(Text.literal(sb.toString()), false);
    }

    public static void onJobComplete(String dimKey) {
        latestProgress.remove(dimKey);
    }

    public static void clear() {
        latestProgress.clear();
        relayTickCounter = 0;
    }

    public static Map<String, String> getLatestProgress() {
        return latestProgress;
    }
}
