package dev.chunkypregen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.chunkypregen.config.ChunkyPregenConfig;
import dev.chunkypregen.generation.DimensionState;
import dev.chunkypregen.generation.GenerationTracker;
import dev.chunkypregen.generation.StateSerializer;
import dev.chunkypregen.integration.VoxyIntegration;
import dev.chunkypregen.monitor.ProgressRelay;
import dev.chunkypregen.monitor.SessionStats;
import dev.chunkypregen.monitor.TpsMonitor;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionSourcePredicate;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * All /chunkypregen sub-commands.
 *
 * Permission level: GAMEMASTERS (op level 2) for all commands.
 * Uses the MC 1.21.11 permission API (PermissionSourcePredicate + PermissionCheck.Require).
 * Theoretically compatible with any MC version that uses this permission system (1.20.5+).
 * Older versions (pre-1.20.5) used hasPermissionLevel(int) — see project memory for details.
 */
public class ChunkyPregenCommands {

    private static final List<RegistryKey<World>> ALL_DIMS =
            List.of(GenerationTracker.OVERWORLD, GenerationTracker.NETHER, GenerationTracker.END);

    // Requires op level 2 (GAMEMASTERS) via 1.21.11 permission API
    @SuppressWarnings("unchecked")
    private static final java.util.function.Predicate<ServerCommandSource> OP2 =
            new PermissionSourcePredicate<>(new PermissionCheck.Require(
                    new Permission.Level(PermissionLevel.GAMEMASTERS)));

    public static void register() {
        CommandRegistrationCallback.EVENT.register(ChunkyPregenCommands::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                         CommandRegistryAccess reg,
                                         CommandManager.RegistrationEnvironment env) {
        dispatcher.register(
            literal("chunkypregen").requires(OP2)

            // ── status ──────────────────────────────────────────────────────
            .then(literal("status").executes(ctx -> {
                sendStatus(ctx.getSource());
                return 1;
            }))

            // ── trigger [dimension] ──────────────────────────────────────────
            .then(literal("trigger")
                .executes(ctx -> { triggerAll(ctx.getSource()); return 1; })
                .then(CommandManager.argument("dimension", StringArgumentType.word())
                    .executes(ctx -> {
                        triggerDim(ctx.getSource(), StringArgumentType.getString(ctx, "dimension"));
                        return 1;
                    })))

            // ── cancel ───────────────────────────────────────────────────────
            .then(literal("cancel").executes(ctx -> {
                GenerationTracker.cancelAll(ctx.getSource().getServer());
                ctx.getSource().sendFeedback(() -> Text.literal("§aCancelled all active Chunky jobs."), false);
                return 1;
            }))

            // ── reset [dimension] ────────────────────────────────────────────
            .then(literal("reset")
                .executes(ctx -> {
                    GenerationTracker.clearAllCenters();
                    ctx.getSource().sendFeedback(() -> Text.literal("§aCleared all last-center positions."), false);
                    return 1;
                })
                .then(CommandManager.argument("dimension", StringArgumentType.word())
                    .executes(ctx -> {
                        String dimStr = StringArgumentType.getString(ctx, "dimension");
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimStr));
                        GenerationTracker.clearCenter(key);
                        ctx.getSource().sendFeedback(() -> Text.literal("§aCleared last-center for " + dimStr + "."), false);
                        return 1;
                    })))

            // ── setworld ─────────────────────────────────────────────────────
            .then(literal("setworld")
                .then(literal("reset").executes(ctx -> {
                    StateSerializer.clearWorldInitFlag(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal(
                        "§aWorld init flag cleared. Next player join will trigger automatic pre-generation."), false);
                    return 1;
                }))
                .then(literal("initialized").executes(ctx -> {
                    StateSerializer.markWorldInitialized(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal(
                        "§aWorld marked as initialized. Automatic new-world pre-gen will not trigger again."), false);
                    return 1;
                })))

            // ── stats ────────────────────────────────────────────────────────
            .then(literal("stats").executes(ctx -> {
                sendStats(ctx.getSource());
                return 1;
            }))

            // ── debug ────────────────────────────────────────────────────────
            .then(literal("debug").executes(ctx -> {
                ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
                cfg.debugMode = !cfg.debugMode;
                cfg.save();
                ctx.getSource().sendFeedback(() -> Text.literal(
                    "§aDebug mode: " + (cfg.debugMode ? "ON" : "OFF")), false);
                return 1;
            }))

            // ── reload ───────────────────────────────────────────────────────
            .then(literal("reload").executes(ctx -> {
                ChunkyPregenConfig.INSTANCE.reload();
                VoxyIntegration.invalidateCache();
                ctx.getSource().sendFeedback(() -> Text.literal("§aConfig reloaded from disk."), false);
                return 1;
            }))

            // ── config ───────────────────────────────────────────────────────
            .then(literal("config").executes(ctx -> {
                printConfig(ctx.getSource());
                return 1;
            }))
        );
    }

    // ── Command implementations ────────────────────────────────────────────────

    private static void sendStatus(ServerCommandSource src) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        double tps = TpsMonitor.getTps(src.getServer());

        src.sendFeedback(() -> Text.literal("§6=== Chunky Pregenerator Status ==="), false);
        src.sendFeedback(() -> Text.literal(
            "§7Enabled: §f" + cfg.enabled +
            "  Chunky: §f" + GenerationTracker.chunkyAvailable +
            "  TPS: §f" + String.format("%.1f", tps) +
            (GenerationTracker.isTpsPaused() ? " §c(paused)" : "")), false);
        src.sendFeedback(() -> Text.literal(
            "§7Trigger: §f" + cfg.triggerDistance + " blocks" +
            "  Radius: §f" + cfg.generationRadius + " chunks" +
            "  Threads: §f" + cfg.chunkyThreads), false);

        if (VoxyIntegration.PRESENT) {
            int vr = VoxyIntegration.getRenderDistanceChunks();
            src.sendFeedback(() -> Text.literal("§7Voxy radius: §f" + vr + " chunks"), false);
        }

        src.sendFeedback(() -> Text.literal("§7End visited: §f" + GenerationTracker.isEndVisited()), false);

        for (RegistryKey<World> dim : ALL_DIMS) {
            DimensionState state   = GenerationTracker.getState(dim);
            BlockPos       last    = GenerationTracker.getLastCenter(dim);
            String         lastStr = last != null ? last.getX() + ", " + last.getZ() : "none";

            String progress = ProgressRelay.getLatestProgress().get(dim.getValue().toString());
            String progressStr = progress != null ? "  progress: §f" + progress : "";

            // Show spiral ring progress if a batch is in flight
            String spiralStr = "";
            int ringNum    = GenerationTracker.getBatchRingNumber(dim);
            int totalRings = GenerationTracker.getBatchTotalRings(dim);
            int curRadius  = GenerationTracker.getBatchCurrentRadius(dim);
            int tgtRadius  = GenerationTracker.getBatchTargetRadius(dim);
            if (ringNum > 0 && totalRings > 0) {
                spiralStr = "  ring §f" + ringNum + "§7/§f" + totalRings
                          + " §7(§f" + curRadius + "§7/§f" + tgtRadius + " §7chunks)";
            }

            final String line = "§7" + GenerationTracker.dimName(dim) + ": §f" + state + "  last=" + lastStr + progressStr + spiralStr;
            src.sendFeedback(() -> Text.literal(line), false);
        }
    }

    private static void sendStats(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal("§6=== Chunky Pregenerator Session Stats ==="), false);

        long activeMs = SessionStats.getActiveMs();
        long secs  = activeMs / 1000;
        long mins  = secs / 60;
        long hours = mins / 60;
        String duration = hours > 0
            ? hours + "h " + (mins % 60) + "m"
            : mins > 0 ? mins + "m " + (secs % 60) + "s" : secs + "s";

        src.sendFeedback(() -> Text.literal("§7Active time this session: §f" + duration), false);

        for (RegistryKey<World> dim : ALL_DIMS) {
            int  jobs     = SessionStats.getJobsFired().getOrDefault(dim, 0);
            long estimate = SessionStats.getChunksEstimated().getOrDefault(dim, 0L);
            if (jobs == 0) continue;
            String dimShort = dim.getValue().getPath().replace("the_", "");
            final String line = "§7" + dimShort + ": §f" + jobs + " job" + (jobs == 1 ? "" : "s") +
                                "  ~" + String.format("%,d", estimate) + " chunks";
            src.sendFeedback(() -> Text.literal(line), false);
        }
    }

    private static void printConfig(ServerCommandSource src) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        src.sendFeedback(() -> Text.literal("§6=== Chunky Pregenerator Config ==="), false);
        src.sendFeedback(() -> Text.literal("§7enabled: §f" + cfg.enabled + "  debugMode: §f" + cfg.debugMode), false);
        src.sendFeedback(() -> Text.literal("§7triggerDistance: §f" + cfg.triggerDistance + "  generationRadius: §f" + cfg.generationRadius), false);
        src.sendFeedback(() -> Text.literal("§7overworldRadius: §f" + cfg.overworldRadius + "  netherRadius: §f" + cfg.netherRadius + "  endRadius: §f" + cfg.endRadius), false);
        src.sendFeedback(() -> Text.literal("§7checkIntervalTicks: §f" + cfg.checkIntervalTicks + "  spiralGeneration: §f" + cfg.spiralGeneration + "  ringStep: §f" + cfg.generationRingStep + " chunks"), false);
        src.sendFeedback(() -> Text.literal("§7enableOverworld: §f" + cfg.enableOverworld + "  enableNether: §f" + cfg.enableNether + "  enableEnd: §f" + cfg.enableEnd), false);
        src.sendFeedback(() -> Text.literal("§7netherAutoScale: §f" + cfg.netherAutoScale), false);
        src.sendFeedback(() -> Text.literal("§7requireEndVisit: §f" + cfg.requireEndVisit + "  minPlayersToTrigger: §f" + cfg.minPlayersToTrigger), false);
        src.sendFeedback(() -> Text.literal("§7skipCreative: §f" + cfg.skipCreativePlayers + "  skipSpectator: §f" + cfg.skipSpectatorPlayers), false);
        src.sendFeedback(() -> Text.literal("§7autoTpsPause: §f" + cfg.autoTpsPause + "  pauseAt: §f" + cfg.tpsPauseThreshold + " TPS  resumeAt: §f" + cfg.tpsResumeThreshold + " TPS"), false);
        src.sendFeedback(() -> Text.literal("§7progressRelay: §f" + cfg.progressRelay + "  every §f" + cfg.progressRelayIntervalMinutes + "min"), false);
        src.sendFeedback(() -> Text.literal("§7voxyIntegration: §f" + cfg.voxyIntegration + "  voxyRenderDistance: §f" + cfg.voxyRenderDistance + "  shape: §f" + cfg.shape), false);
    }

    private static void triggerAll(ServerCommandSource src) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        if (cfg.enableOverworld) triggerDim(src, "minecraft:overworld");
        if (cfg.enableNether)    triggerDim(src, "minecraft:the_nether");
        if (cfg.enableEnd)       triggerDim(src, "minecraft:the_end");
    }

    private static void triggerDim(ServerCommandSource src, String dimStr) {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dimStr));
        BlockPos center = BlockPos.ORIGIN;
        if (src.getServer() != null) {
            for (var player : src.getServer().getPlayerManager().getPlayerList()) {
                if (player.getEntityWorld().getRegistryKey().equals(key)) {
                    center = player.getBlockPos();
                    break;
                }
            }
        }
        final BlockPos finalCenter = center;
        GenerationTracker.maybeFireJob(src.getServer(), key, finalCenter);
        src.sendFeedback(() -> Text.literal(
            "§aTriggered generation for " + dimStr + " at " + finalCenter.getX() + ", " + finalCenter.getZ()), false);
    }
}
