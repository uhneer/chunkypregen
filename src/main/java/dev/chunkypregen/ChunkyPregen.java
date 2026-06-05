package dev.chunkypregen;

import dev.chunkypregen.command.ChunkyPregenCommands;
import dev.chunkypregen.config.ChunkyPregenConfig;
import dev.chunkypregen.generation.GenerationTracker;
import dev.chunkypregen.generation.StateSerializer;
import dev.chunkypregen.monitor.ProgressRelay;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chunky Pregenerator — Fabric mod for MC 1.21.11
 *
 * Hard dependencies (compile + runtime):
 *   - Fabric Loader   ≥ 0.19.3    (confirmed: 0.19.3)
 *   - Fabric API      0.141.4+1.21.11 (uses fabric-lifecycle-events-v1,
 *                     fabric-networking-api-v1, fabric-message-api-v1,
 *                     fabric-command-api-v2)
 *   - Minecraft       1.21.11 / Yarn 1.21.11+build.6
 *
 * Soft dependencies (runtime only, detected via FabricLoader):
 *   - Chunky          ≥ 1.4.55    (command API stable since 1.4.0;
 *                     theoretically compatible with 1.3.x but unconfirmed)
 *   - Sodium          0.8.12+mc1.21.11  (Sodium Config API / sodium:config_api_user
 *                     entrypoint added in 0.6.x; theoretically compatible with 0.6+)
 *   - Voxy            0.2.16-beta (config file schema; theoretically 0.2.x series)
 *
 * Events used:
 *   - ServerLifecycleEvents.SERVER_STARTED / SERVER_STOPPING
 *   - ServerPlayConnectionEvents.JOIN       (fabric-networking-api-v1)
 *   - ServerMessageEvents.ALLOW_GAME_MESSAGE (fabric-message-api-v1 ≥ 6.x, MC 1.19+)
 *     Used to suppress Chunky's per-second progress spam and intercept completions.
 */
public class ChunkyPregen implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("chunkypregen");

    /**
     * Called by GenerationTracker every time a new generation bundle starts.
     * ChunkyPregenClient registers itself here on the client side.
     * No-op on dedicated servers (the default lambda does nothing).
     */
    public static Runnable onGenerationBundleStart = () -> {};

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().isModLoaded("chunky")) {
            GenerationTracker.chunkyAvailable = true;
            LOGGER.info("[ChunkyPregen] Chunky detected — generation enabled.");
        } else {
            GenerationTracker.chunkyAvailable = false;
            LOGGER.warn("[ChunkyPregen] Chunky not detected — functionality disabled.");
        }

        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        LOGGER.info("[ChunkyPregen] Config loaded. enabled={}, triggerDist={}, radius={}, threads={}",
                cfg.enabled, cfg.triggerDistance, cfg.generationRadius, cfg.chunkyThreads);

        GenerationTracker.init();
        ChunkyPregenCommands.register();

        // Load tracker state when the server (world) starts
        ServerLifecycleEvents.SERVER_STARTED.register(GenerationTracker::onWorldLoad);
        ServerLifecycleEvents.SERVER_STOPPING.register(GenerationTracker::onWorldUnload);

        // On first player join: check world-init flag and trigger pregen if needed.
        // JOIN fires after the player is fully in the world, so their position is accurate.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!GenerationTracker.chunkyAvailable || !cfg.enabled) return;
            var player = handler.getPlayer();

            if (!StateSerializer.isWorldInitialized(server)) {
                LOGGER.info("[ChunkyPregen] New world detected — starting initial pre-generation.");
                StateSerializer.markWorldInitialized(server);
                // Broadcast is deferred to the join delay so it arrives after the client chat is ready.
                // The start message fires when the first ring actually starts (~15s after join).
                GenerationTracker.scheduleOnJoin(server, player, true);
            } else if (GenerationTracker.hasPendingResume()) {
                // A previous session was interrupted mid-batch — resume from where it left off
                GenerationTracker.scheduleResume(server, player);
            }
            // Otherwise: world initialized, no interrupted batch. Movement trigger handles generation.
        });

        // Intercept game messages to suppress Chunky's per-second progress spam.
        // Completion detection is handled entirely by the ChunkyAPI poll in GenerationTracker
        // (see chunkyPollCountdown / onChunkyTaskComplete). We do NOT call onChunkyTaskComplete
        // here — doing so would double-advance the spiral batch (once from the message, once
        // from the API poll that fires 0.5 s later).
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register((server, message, overlay) -> {
            try {
                String raw = message.getString();
                if (ProgressRelay.isChunkyProgress(raw)) {
                    if (cfg.progressRelay) {
                        ProgressRelay.update(raw);
                        return false; // suppress and replace with periodic relay summary
                    }
                    // progressRelay off — let Chunky's native output through untouched
                }
            } catch (Exception e) {
                LOGGER.debug("[ChunkyPregen] Error processing game message", e);
            }
            return true;
        });
    }
}
