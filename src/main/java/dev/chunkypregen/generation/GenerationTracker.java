package dev.chunkypregen.generation;

import dev.chunkypregen.config.ChunkyPregenConfig;
import dev.chunkypregen.integration.ChunkyIntegration;
import dev.chunkypregen.integration.VoxyIntegration;
import dev.chunkypregen.monitor.ProgressRelay;
import dev.chunkypregen.monitor.SessionStats;
import dev.chunkypregen.monitor.TpsMonitor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.command.permission.PermissionSourcePredicate;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * Core state machine and tick driver for Chunky Pregenerator.
 *
 * Per-dimension states: IDLE → RUNNING → IDLE (or QUEUED when maxConcurrent reached).
 *
 * Spiral batch: a sequence of Chunky tasks with increasing radii. Ring radii are
 * computed once at batch start using a quadratic (ease-in) curve — small steps near
 * the player, larger steps further out. The batch center is fixed for the entire batch.
 *
 * Completion detection: uses Chunky's public API (ChunkyAPI.isRunning) via reflection,
 * polled every POLL_TICKS (0.5 s) per RUNNING dimension. When isRunning() returns false
 * the task is done and the spiral advances or the dim is marked IDLE. If reflection is
 * unavailable, falls back to FALLBACK_TIMEOUT_TICKS (10 s) before declaring completion.
 *
 * Chunky command compatibility (Chunky 1.4.55):
 *   chunky world / center / shape / radius / start / confirm / pause / continue / cancel
 *   "chunky threads" does NOT exist in 1.4.55 — intentionally omitted.
 */
public class GenerationTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkypregen/tracker");

    @SuppressWarnings("unchecked")
    private static final Predicate<ServerCommandSource> IS_OP =
            new PermissionSourcePredicate<>(new PermissionCheck.Require(
                    new Permission.Level(PermissionLevel.GAMEMASTERS)));

    public static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    public static final RegistryKey<World> NETHER =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether"));
    public static final RegistryKey<World> END =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_end"));

    private static final List<RegistryKey<World>> ALL_DIMS = List.of(OVERWORLD, NETHER, END);

    // Core state
    private static final Map<RegistryKey<World>, BlockPos>       lastCenters     = new HashMap<>();
    private static final Map<RegistryKey<World>, DimensionState> dimensionStates = new HashMap<>();
    private static final Queue<RegistryKey<World>>               jobQueue        = new LinkedList<>();

    // Spiral batch state — in-memory; batch restarts from ring 1 after server restart
    // (Chunky skips already-generated chunks, so restarting is cheap)
    private static final Map<RegistryKey<World>, List<Integer>> batchRings      = new HashMap<>();
    private static final Map<RegistryKey<World>, Integer>       batchRingIndex  = new HashMap<>();
    private static final Map<RegistryKey<World>, BlockPos>      batchCenter     = new HashMap<>();

    // Chunky API poll: check isRunning() every POLL_TICKS while a dim is RUNNING.
    // When isRunning() returns false → advance to next ring or mark complete.
    // If ChunkyIntegration reflection is unavailable, falls back to FALLBACK_TIMEOUT_TICKS.
    private static final Map<RegistryKey<World>, Integer> chunkyPollCountdown = new HashMap<>();
    private static final int POLL_TICKS            = 10;   // poll every 0.5s
    private static final int FALLBACK_TIMEOUT_TICKS = 200; // 10s fallback if API unavailable

    // Completion debounce: require N consecutive isRunning()=false polls before declaring a
    // task complete. Absorbs the brief false-negative window right after a new ring's task
    // starts (Chunky's worker hasn't flipped its running flag yet) — without this, a single
    // stray false reading advances/merges rings prematurely ("Nothing to confirm!" race).
    private static final Map<RegistryKey<World>, Integer> pollFalseStreak = new HashMap<>();
    private static final int COMPLETION_FALSE_STREAK = 2;

    // Watchdog: last-resort reset for a dim whose Chunky task has STALLED (no activity).
    // runningTickCount measures time since Chunky last reported activity, NOT total batch
    // runtime — it is reset to 0 on every poll where isRunning()=true and on every ring
    // advance. A healthy multi-hour batch therefore never trips it; only a genuine hang
    // (Chunky silent for WATCHDOG_TICKS straight) triggers recovery.
    private static final Map<RegistryKey<World>, Integer> runningTickCount = new HashMap<>();
    private static final int WATCHDOG_TICKS = 12000; // 10 minutes of NO Chunky activity (stall), not total runtime

    // Delayed on-join fire: avoids race with the Sodium render-distance dump sequence
    // that fires when joining a world. joinDelaySeconds (default 20) lets that sequence
    // complete before we send Chunky commands. Movement-triggered bundles are unaffected.
    private static MinecraftServer    pendingJoinServer    = null;
    private static ServerPlayerEntity pendingJoinPlayer    = null;
    private static boolean            pendingJoinForce     = false;
    private static boolean            pendingJoinResume    = false; // true = resume interrupted batches on countdown fire
    private static int                pendingJoinCountdown = 0;

    // In-progress batches loaded from disk on world load — resumed after the join delay
    private static final Map<RegistryKey<World>, StateSerializer.BatchState> pendingResumeBatches = new HashMap<>();

    public static boolean chunkyAvailable   = false;
    private static boolean endVisited       = false;
    private static boolean tpsPaused        = false;
    private static int     tickCounter      = 0;
    private static int     lastKnownVoxyRadius = -1;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(GenerationTracker::onServerTick);
    }

    public static void onWorldLoad(MinecraftServer server) {
        StateSerializer.LoadResult result = StateSerializer.load(server);
        lastCenters.clear();
        lastCenters.putAll(result.lastCenters());
        endVisited = result.endVisited();
        dimensionStates.clear();
        jobQueue.clear();
        batchRings.clear();
        batchRingIndex.clear();
        batchCenter.clear();
        chunkyPollCountdown.clear();
        runningTickCount.clear();
        pollFalseStreak.clear();
        pendingJoinCountdown = 0;
        pendingJoinResume    = false;
        tickCounter = 0;
        tpsPaused = false;
        lastKnownVoxyRadius = VoxyIntegration.getRenderDistanceChunks();
        SessionStats.reset();
        ProgressRelay.clear();

        // Stash any in-progress batches — they'll be resumed after the join delay fires
        pendingResumeBatches.clear();
        pendingResumeBatches.putAll(result.inProgressBatches());
        if (!pendingResumeBatches.isEmpty())
            LOGGER.info("[ChunkyPregen] {} in-progress batch(es) will resume after player joins.",
                    pendingResumeBatches.size());

        debug(server, "State loaded — endVisited=" + endVisited + ", voxyRadius=" + lastKnownVoxyRadius);
    }

    public static void onWorldUnload(MinecraftServer server) {
        // Collect batch state for any dimension currently mid-spiral so it can be resumed later
        Map<RegistryKey<World>, StateSerializer.BatchState> inProgress = new HashMap<>();
        for (RegistryKey<World> dim : ALL_DIMS) {
            if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) != DimensionState.RUNNING) continue;
            List<Integer> rings = batchRings.get(dim);
            if (rings == null) continue;
            int idx = batchRingIndex.getOrDefault(dim, 0);
            BlockPos center = batchCenter.getOrDefault(dim, lastCenters.getOrDefault(dim, BlockPos.ORIGIN));
            inProgress.put(dim, new StateSerializer.BatchState(rings, idx, center.getX(), center.getZ()));
            LOGGER.info("[ChunkyPregen] Saving in-progress batch for {} — ring {}/{} at ({},{})",
                    dimName(dim), idx + 1, rings.size(), center.getX(), center.getZ());
        }
        StateSerializer.save(server, lastCenters, endVisited, inProgress);
        // Clear pending join state so it doesn't fire into a stopped/restarted server
        pendingJoinServer    = null;
        pendingJoinPlayer    = null;
        pendingJoinForce     = false;
        pendingJoinResume    = false;
        pendingJoinCountdown = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private static void onServerTick(MinecraftServer server) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        if (!cfg.enabled || !chunkyAvailable) return;

        // Pending on-join fire countdown (every tick)
        if (pendingJoinCountdown > 0) {
            pendingJoinCountdown--;
            if (pendingJoinCountdown == 0 && pendingJoinServer != null) {
                if (pendingJoinResume) {
                    fireResume(pendingJoinServer);
                } else {
                    fireOnJoin(pendingJoinServer, pendingJoinPlayer, pendingJoinForce);
                }
                pendingJoinServer = null;
                pendingJoinPlayer = null;
                pendingJoinResume = false;
            }
        }

        // Chunky API poll — for each RUNNING dim, decrement countdown and poll when zero.
        // isRunning()=true  → Chunky is actively working: reset the stall watchdog + debounce.
        // isRunning()=false → only declare complete after COMPLETION_FALSE_STREAK consecutive
        //                     false polls (absorbs the brief false-negative at task startup).
        for (RegistryKey<World> dim : ALL_DIMS) {
            if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) != DimensionState.RUNNING) continue;
            int cd = chunkyPollCountdown.merge(dim, -1, Integer::sum);
            if (cd > 0) continue;
            boolean apiAvail = ChunkyIntegration.isAvailable();
            chunkyPollCountdown.put(dim, apiAvail ? POLL_TICKS : FALLBACK_TIMEOUT_TICKS);
            boolean running = ChunkyIntegration.isTaskRunning(dim);
            if (running) {
                // Real progress — Chunky is grinding. Reset stall watchdog and completion debounce
                // so a legitimately long batch never false-trips either guard.
                runningTickCount.put(dim, 0);
                pollFalseStreak.put(dim, 0);
            } else {
                // Debounce only matters when the API is actually answering; in the time-based
                // fallback (no API) a single elapsed window already means "assume complete".
                int needed = apiAvail ? COMPLETION_FALSE_STREAK : 1;
                int streak = pollFalseStreak.merge(dim, 1, Integer::sum);
                if (streak >= needed) {
                    pollFalseStreak.put(dim, 0);
                    if (cfg.debugMode)
                        debug(server, "API poll: " + dimName(dim) + " — isRunning()=false x" + streak + " → task complete");
                    onChunkyTaskComplete(server, "apipoll:" + dimName(dim));
                } else if (cfg.debugMode) {
                    debug(server, "API poll: " + dimName(dim) + " — isRunning()=false ("
                            + streak + "/" + needed + "), debouncing");
                }
            }
        }

        ProgressRelay.onTick(server);

        tickCounter++;
        if (tickCounter % cfg.checkIntervalTicks != 0) return;

        if (cfg.debugMode)
            debug(server, "Checking positions... (tick " + tickCounter + ")");

        // Watchdog: reset dims stuck RUNNING too long
        for (RegistryKey<World> dim : ALL_DIMS) {
            if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) == DimensionState.RUNNING) {
                int ticks = runningTickCount.merge(dim, cfg.checkIntervalTicks, Integer::sum);
                if (ticks >= WATCHDOG_TICKS) {
                    LOGGER.warn("[ChunkyPregen] Watchdog: {} stuck RUNNING for {} ticks — resetting",
                            dimName(dim), ticks);
                    setDimState(server, dim, DimensionState.IDLE);
                    clearBatchState(dim);
                }
            }
        }

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

        if (cfg.autoTpsPause) handleTpsPauseResume(server, cfg, players);

        // Voxy render-distance growth → re-trigger all enabled dims
        if (VoxyIntegration.PRESENT && cfg.voxyIntegration) {
            int currentVoxyRadius = VoxyIntegration.getRenderDistanceChunks();
            if (currentVoxyRadius > 0 && currentVoxyRadius > lastKnownVoxyRadius) {
                if (cfg.debugMode)
                    debug(server, "Voxy radius grew " + lastKnownVoxyRadius + "→" + currentVoxyRadius + " — re-triggering");
                lastKnownVoxyRadius = currentVoxyRadius;
                triggerAllDims(server, players);
                return;
            }
        }

        if (tpsPaused) return;
        if (players.size() < cfg.minPlayersToTrigger) return;

        try {
            // Track which dims we've already printed debug for this tick (one line per dim)
            Set<RegistryKey<World>> debuggedDims = new HashSet<>();
            for (ServerPlayerEntity player : players) {
                RegistryKey<World> dim = player.getEntityWorld().getRegistryKey();
                if (!isDimensionEnabled(dim)) continue;

                BlockPos playerPos  = player.getBlockPos();
                BlockPos lastCenter = lastCenters.getOrDefault(dim, playerPos);
                long dx      = playerPos.getX() - lastCenter.getX();
                long dz      = playerPos.getZ() - lastCenter.getZ();
                long distSq  = dx * dx + dz * dz;
                long trigger = getTrigger(dim);
                DimensionState dimState = dimensionStates.getOrDefault(dim, DimensionState.IDLE);

                // Debug: print once per dim per tick, BEFORE creative/spectator filter
                // so the output is never silently swallowed when you're in creative mode.
                if (cfg.debugMode && dimState != DimensionState.RUNNING && debuggedDims.add(dim)) {
                    String modeTag = (cfg.skipCreativePlayers  && player.isCreative())  ? " [creative-skip]"  :
                                     (cfg.skipSpectatorPlayers && player.isSpectator()) ? " [spectator-skip]" : "";
                    debug(server, dimName(dim) + " dist=" + (long) Math.sqrt(distSq)
                            + " blocks (trigger=" + trigger + ") state=" + dimState + modeTag);
                }

                // Creative / spectator players don't trigger jobs or mark End as visited
                if (cfg.skipSpectatorPlayers && player.isSpectator()) continue;
                if (cfg.skipCreativePlayers  && player.isCreative())   continue;

                if (dim.equals(END)) {
                    endVisited = true;
                    StateSerializer.save(server, lastCenters, endVisited, Map.of());
                }

                if (distSq >= trigger * trigger) {
                    maybeFireJob(server, dim, playerPos);
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.error("[ChunkyPregen] Exception in tick handler", e);
        }
    }

    private static void handleTpsPauseResume(MinecraftServer server, ChunkyPregenConfig cfg,
                                              List<ServerPlayerEntity> players) {
        boolean anyRunning = dimensionStates.containsValue(DimensionState.RUNNING);
        if (!anyRunning && !tpsPaused) return;
        double tps = TpsMonitor.getTps(server);
        if (!tpsPaused && tps < cfg.tpsPauseThreshold) {
            tpsPaused = true;
            SessionStats.recordPause();
            execCmd(server, "chunky pause");
            if (cfg.chatNotifications)
                broadcast(server, "§e[ChunkyPregen] §7Generation paused (TPS=" + String.format("%.1f", tps) + ")");
        } else if (tpsPaused && tps >= cfg.tpsResumeThreshold) {
            tpsPaused = false;
            SessionStats.recordResume();
            execCmd(server, "chunky continue");
            if (cfg.chatNotifications)
                broadcast(server, "§a[ChunkyPregen] §7Generation resumed (TPS=" + String.format("%.1f", tps) + ")");
        }
    }

    private static void triggerAllDims(MinecraftServer server, List<ServerPlayerEntity> players) {
        Map<RegistryKey<World>, BlockPos> playerByDim = new HashMap<>();
        for (ServerPlayerEntity p : players)
            playerByDim.putIfAbsent(p.getEntityWorld().getRegistryKey(), p.getBlockPos());
        for (RegistryKey<World> dim : ALL_DIMS) {
            if (!isDimensionEnabled(dim)) continue;
            maybeFireJob(server, dim, playerByDim.getOrDefault(dim, BlockPos.ORIGIN));
        }
    }

    // ── Job management ────────────────────────────────────────────────────────

    public static void maybeFireJob(MinecraftServer server, RegistryKey<World> dim, BlockPos center) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;

        if (dim.equals(END) && cfg.requireEndVisit && !endVisited) {
            if (cfg.debugMode) debug(server, "Skipping End — player has not visited yet");
            return;
        }

        DimensionState state        = dimensionStates.getOrDefault(dim, DimensionState.IDLE);
        int            runningCount = (int) dimensionStates.values().stream()
                .filter(s -> s == DimensionState.RUNNING).count();

        if (state == DimensionState.RUNNING) {
            if (cfg.debugMode) debug(server, "Skipping " + dimName(dim) + " — batch in progress");
            return;
        }

        if (runningCount >= cfg.maxConcurrentDimensions) {
            if (state != DimensionState.QUEUED) {
                setDimState(server, dim, DimensionState.QUEUED);
                jobQueue.add(dim);
                if (cfg.debugMode)
                    debug(server, "Queued " + dimName(dim)
                            + " (" + runningCount + "/" + cfg.maxConcurrentDimensions + " running)");
            }
            return;
        }

        fireJob(server, dim, center);
    }

    /** Initialises the spiral batch and fires the first ring. */
    private static void fireJob(MinecraftServer server, RegistryKey<World> dim, BlockPos center) {
        // Notify client side so it can cycle Sodium render distance to force Voxy boundary rebuild
        dev.chunkypregen.ChunkyPregen.onGenerationBundleStart.run();

        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;

        int targetRadius    = computeTargetRadius(dim);
        String radiusSource = describeRadiusSource(dim);

        setDimState(server, dim, DimensionState.RUNNING);
        lastCenters.put(dim, new BlockPos(center.getX(), 0, center.getZ()));
        StateSerializer.save(server, lastCenters, endVisited, Map.of());
        SessionStats.recordJob(dim, targetRadius, cfg.shape.chunkyCmdName);

        if (cfg.spiralGeneration) {
            List<Integer> rings = computeBezierRings(targetRadius, cfg.generationRingStep);
            batchCenter.put(dim, new BlockPos(center.getX(), 0, center.getZ()));
            batchRings.put(dim, rings);
            batchRingIndex.put(dim, 0);

            if (cfg.debugMode)
                debug(server, "Spiral batch: " + dimName(dim)
                        + " center=" + center.getX() + "," + center.getZ()
                        + " rings=" + rings
                        + " radius-src=" + radiusSource);

            fireRingCommands(server, dim, center, rings.get(0), 1, rings.size());
        } else {
            if (cfg.debugMode)
                debug(server, "Firing job: " + dimName(dim)
                        + " center=" + center.getX() + "," + center.getZ()
                        + " radius=" + targetRadius
                        + " radius-src=" + radiusSource);
            fireCommands(server, dim, center, targetRadius);
        }

        if (cfg.chatNotifications) {
            String radiusInfo = cfg.spiralGeneration
                    ? batchRings.getOrDefault(dim, List.of(targetRadius)).size() + " rings → " + targetRadius + " chunk radius"
                    : targetRadius + " chunk radius";
            String msg = cfg.startMessage
                    .replace("{x}",         String.valueOf(center.getX()))
                    .replace("{z}",         String.valueOf(center.getZ()))
                    .replace("{dimension}", dimName(dim))
                    .replace("{radius}",    radiusInfo);
            broadcast(server, msg);
        }
    }

    /**
     * Computes ring radii for a spiral batch using a quadratic (ease-in) curve.
     * Smaller steps near the player, larger steps further out.
     *
     * N = clamp(2·√(targetRadius/minStep), 2, 20)
     * ring[i] = targetRadius · ((i+1)/N)²   (last ring forced to targetRadius)
     */
    static List<Integer> computeBezierRings(int targetRadius, int minStep) {
        if (minStep < 1) minStep = 1;
        if (targetRadius <= minStep) return new ArrayList<>(List.of(targetRadius));

        int n = (int) Math.round(2.0 * Math.sqrt((double) targetRadius / minStep));
        n = Math.max(2, Math.min(20, n));

        List<Integer> rings = new ArrayList<>(n);
        int prev = 0;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            int r = (int) Math.round(targetRadius * t * t);
            r = Math.max(r, prev + 1);
            r = Math.min(r, targetRadius);
            if (i == n) r = targetRadius;
            rings.add(r);
            prev = r;
        }
        return rings;
    }

    /** Advances the spiral batch to its next ring (dim stays RUNNING). */
    private static void advanceSpiralRing(MinecraftServer server, RegistryKey<World> dim) {
        List<Integer> rings = batchRings.get(dim);
        int nextIndex = batchRingIndex.get(dim) + 1;
        batchRingIndex.put(dim, nextIndex);

        int radius     = rings.get(nextIndex);
        int ringNumber = nextIndex + 1; // 1-based
        int total      = rings.size();
        BlockPos center = batchCenter.get(dim);

        // Reset poll countdown so the new ring gets its own fresh poll window, and reset the
        // stall watchdog + completion debounce — advancing a ring is unambiguous forward progress.
        chunkyPollCountdown.put(dim, ChunkyIntegration.isAvailable() ? POLL_TICKS : FALLBACK_TIMEOUT_TICKS);
        runningTickCount.put(dim, 0);
        pollFalseStreak.put(dim, 0);

        if (cfg().debugMode)
            debug(server, "Spiral ring " + ringNumber + "/" + total
                    + " for " + dimName(dim)
                    + " (radius " + radius + " chunks)");

        fireCommands(server, dim, center, radius);
    }

    private static void fireRingCommands(MinecraftServer server, RegistryKey<World> dim,
                                         BlockPos center, int radius, int ringNum, int totalRings) {
        if (cfg().debugMode)
            debug(server, "Ring " + ringNum + "/" + totalRings + " — " + dimName(dim)
                    + " radius=" + radius + " chunks");
        fireCommands(server, dim, center, radius);
    }

    private static void fireCommands(MinecraftServer server, RegistryKey<World> dim,
                                     BlockPos center, int radius) {
        String dimKey = dim.getValue().toString();
        try {
            execCmd(server, "chunky world "  + dimKey);
            execCmd(server, "chunky center " + center.getX() + " " + center.getZ());
            execCmd(server, "chunky shape "  + cfg().shape.chunkyCmdName);
            execCmd(server, "chunky radius " + (radius * 16)); // radius is in chunks; Chunky expects blocks
            // "chunky threads" does not exist in Chunky 1.4.55 — intentionally omitted
            execCmd(server, "chunky start");
            execCmd(server, "chunky confirm");
        } catch (Exception e) {
            LOGGER.error("[ChunkyPregen] Failed to fire Chunky commands for {}", dimKey, e);
            setDimState(server, dim, DimensionState.IDLE);
            clearBatchState(dim);
        }
    }

    public static void onChunkyTaskComplete(MinecraftServer server, String message) {
        RegistryKey<World> dim = extractDimFromMessage(message);
        if (dim == null || dimensionStates.getOrDefault(dim, DimensionState.IDLE) != DimensionState.RUNNING) {
            dim = null;
            for (Map.Entry<RegistryKey<World>, DimensionState> e : dimensionStates.entrySet()) {
                if (e.getValue() == DimensionState.RUNNING) { dim = e.getKey(); break; }
            }
        }
        if (dim == null) return;

        List<Integer> rings = batchRings.get(dim);
        if (cfg().spiralGeneration && rings != null) {
            int idx   = batchRingIndex.getOrDefault(dim, 0);
            int total = rings.size();
            if (idx < total - 1) {
                advanceSpiralRing(server, dim);
                return; // dim stays RUNNING
            }
            // All rings done
            if (cfg().debugMode)
                debug(server, "Spiral batch complete for " + dimName(dim)
                        + " (" + total + " rings)");
            clearBatchState(dim);
        }

        final RegistryKey<World> completedDim = dim;
        setDimState(server, completedDim, DimensionState.IDLE);
        ProgressRelay.onJobComplete(completedDim.getValue().toString());
        if (cfg().chatNotifications)
            broadcast(server, cfg().completeMessage.replace("{dimension}", dimName(completedDim)));
        processQueue(server);
    }

    public static void cancelAll(MinecraftServer server) {
        execCmd(server, "chunky cancel");
        tpsPaused = false;
        for (RegistryKey<World> dim : ALL_DIMS) clearBatchState(dim);
        for (RegistryKey<World> dim : ALL_DIMS) {
            if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) != DimensionState.IDLE)
                setDimState(server, dim, DimensionState.IDLE);
        }
        jobQueue.clear();
        chunkyPollCountdown.clear();
        runningTickCount.clear();
        pollFalseStreak.clear();
        pendingJoinCountdown = 0;
        ProgressRelay.clear();
    }

    private static void clearBatchState(RegistryKey<World> dim) {
        batchRings.remove(dim);
        batchRingIndex.remove(dim);
        batchCenter.remove(dim);
    }

    private static void processQueue(MinecraftServer server) {
        RegistryKey<World> next = jobQueue.poll();
        if (next == null) return;
        if (cfg().debugMode) debug(server, "Processing queue — starting " + dimName(next));
        BlockPos center = lastCenters.getOrDefault(next, BlockPos.ORIGIN);
        fireJob(server, next, center);
    }

    // ── On-join generation ────────────────────────────────────────────────────

    /**
     * Schedules a delayed new-world generation fire.
     * The delay lets chunky-offline finish its post-join setup before Chunky commands fire.
     */
    public static void scheduleOnJoin(MinecraftServer server, ServerPlayerEntity player, boolean forceRun) {
        pendingJoinServer    = server;
        pendingJoinPlayer    = player;
        pendingJoinForce     = forceRun;
        pendingJoinResume    = false;
        pendingJoinCountdown = ChunkyPregenConfig.INSTANCE.joinDelaySeconds * 20;
        if (ChunkyPregenConfig.INSTANCE.debugMode)
            LOGGER.debug("[ChunkyPregen] On-join fire scheduled in {} ticks ({}s)",
                    pendingJoinCountdown, ChunkyPregenConfig.INSTANCE.joinDelaySeconds);
    }

    /**
     * Schedules a delayed resume of interrupted in-progress batches.
     * Uses the same join-delay countdown as new-world generation.
     */
    public static void scheduleResume(MinecraftServer server, ServerPlayerEntity player) {
        pendingJoinServer    = server;
        pendingJoinPlayer    = player;
        pendingJoinForce     = false;
        pendingJoinResume    = true;
        pendingJoinCountdown = ChunkyPregenConfig.INSTANCE.joinDelaySeconds * 20;
        LOGGER.info("[ChunkyPregen] Batch resume scheduled in {}s ({} dim(s))",
                ChunkyPregenConfig.INSTANCE.joinDelaySeconds, pendingResumeBatches.size());
    }

    /** Returns true if there are in-progress batches loaded from disk waiting to resume. */
    public static boolean hasPendingResume() {
        return !pendingResumeBatches.isEmpty();
    }

    private static void fireOnJoin(MinecraftServer server, ServerPlayerEntity player, boolean forceRun) {
        if (!chunkyAvailable) return;
        ChunkyPregenConfig cfg = cfg();
        if (!forceRun) return;

        BlockPos           playerPos = player.getBlockPos();
        RegistryKey<World> playerDim = player.getEntityWorld().getRegistryKey();

        if (cfg.enableOverworld)
            maybeFireJob(server, OVERWORLD, playerDim.equals(OVERWORLD) ? playerPos : BlockPos.ORIGIN);
        if (cfg.enableNether)
            maybeFireJob(server, NETHER,    playerDim.equals(NETHER)    ? playerPos : BlockPos.ORIGIN);
        if (cfg.enableEnd)
            maybeFireJob(server, END,       playerDim.equals(END)       ? playerPos : BlockPos.ORIGIN);
    }

    /**
     * Resumes all in-progress batches loaded from disk.
     * Restores ring sequence, ring index, and center for each dim, sets it to RUNNING,
     * and re-fires the current ring. Chunky automatically skips already-generated chunks
     * within that ring, so chunk-level precision is achieved even with ring-level bookmarking.
     */
    private static void fireResume(MinecraftServer server) {
        if (!chunkyAvailable || pendingResumeBatches.isEmpty()) return;
        ChunkyPregenConfig cfg = cfg();

        for (Map.Entry<RegistryKey<World>, StateSerializer.BatchState> entry : pendingResumeBatches.entrySet()) {
            RegistryKey<World> dim = entry.getKey();
            StateSerializer.BatchState bs = entry.getValue();

            if (!isDimensionEnabled(dim)) {
                LOGGER.info("[ChunkyPregen] Skipping resume for {} — dimension disabled.", dimName(dim));
                continue;
            }
            if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) == DimensionState.RUNNING) {
                LOGGER.info("[ChunkyPregen] Skipping resume for {} — already running.", dimName(dim));
                continue;
            }

            // Restore batch state
            BlockPos center = new BlockPos(bs.centerX(), 0, bs.centerZ());
            batchRings.put(dim,      new ArrayList<>(bs.rings()));
            batchRingIndex.put(dim,  bs.ringIndex());
            batchCenter.put(dim,     center);
            lastCenters.put(dim,     center);
            setDimState(server, dim, DimensionState.RUNNING);

            int ringNum   = bs.ringIndex() + 1;
            int total     = bs.rings().size();
            int radius    = bs.rings().get(bs.ringIndex());

            LOGGER.info("[ChunkyPregen] Resuming {} — ring {}/{} (radius {} chunks) at ({},{})",
                    dimName(dim), ringNum, total, radius, bs.centerX(), bs.centerZ());
            if (cfg.chatNotifications)
                broadcast(server, "§6[ChunkyPregen] §fResuming generation in " + dimName(dim)
                        + " — ring §e" + ringNum + "§f/§e" + total + "§f (radius §e" + radius + "§f chunks).");

            // Notify client for RD cycle
            dev.chunkypregen.ChunkyPregen.onGenerationBundleStart.run();

            fireCommands(server, dim, center, radius);
        }
        pendingResumeBatches.clear();
    }

    // ── Settings-triggered regen ──────────────────────────────────────────────

    /**
     * Called from the Sodium UI (render/client thread) when a generation-related setting changes.
     * If we're in singleplayer (integrated server), schedules the actual state mutation and
     * job dispatch on the server thread via {@code server.execute()} to avoid race conditions
     * and ConcurrentModificationExceptions from touching dimensionStates/jobQueue off-thread.
     * Does NOT cancel any currently running Chunky task.
     */
    public static void scheduleSettingsRegenIfInWorld() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        MinecraftServer server = mc.getServer();
        if (server == null) return; // not in singleplayer

        // Invalidate Voxy cache so the re-trigger reads a fresh render distance.
        VoxyIntegration.invalidateCache();

        // All state mutations must happen on the server thread.
        server.execute(() -> {
            // Clear queued dims (not running ones)
            for (RegistryKey<World> dim : ALL_DIMS) {
                DimensionState st = dimensionStates.getOrDefault(dim, DimensionState.IDLE);
                if (st == DimensionState.QUEUED) {
                    setDimState(server, dim, DimensionState.IDLE);
                    clearBatchState(dim);
                }
            }
            jobQueue.clear();

            // Re-trigger all enabled, non-running dims
            List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
            Map<RegistryKey<World>, BlockPos> playerByDim = new HashMap<>();
            for (ServerPlayerEntity p : players)
                playerByDim.putIfAbsent(p.getEntityWorld().getRegistryKey(), p.getBlockPos());

            for (RegistryKey<World> dim : ALL_DIMS) {
                if (!isDimensionEnabled(dim)) continue;
                if (dimensionStates.getOrDefault(dim, DimensionState.IDLE) == DimensionState.RUNNING) continue;
                BlockPos center = playerByDim.getOrDefault(dim,
                        lastCenters.getOrDefault(dim, BlockPos.ORIGIN));
                maybeFireJob(server, dim, center);
            }

            LOGGER.info("[ChunkyPregen] Settings changed — re-triggered generation for applicable dimensions");
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int computeTargetRadius(RegistryKey<World> dim) {
        ChunkyPregenConfig cfg = cfg();
        int voxyRadius  = (cfg.voxyIntegration && VoxyIntegration.PRESENT)
                          ? VoxyIntegration.getRenderDistanceChunks() : -1;
        // Generation radius = voxyChunks × voxyGenRadiusMultiplier (default 2×)
        int baseRadius  = (voxyRadius > 0)
                ? Math.max(1, Math.round(voxyRadius * cfg.voxyGenRadiusMultiplier))
                : cfg.generationRadius;
        int dimOverride = getDimRadiusOverride(dim);
        int radius      = (dimOverride > 0) ? dimOverride : baseRadius;
        if (cfg.netherAutoScale && dim.equals(NETHER))
            radius = Math.max(1, radius / 8);
        return Math.max(1, radius);
    }

    private static String describeRadiusSource(RegistryKey<World> dim) {
        ChunkyPregenConfig cfg = cfg();
        int dimOverride = getDimRadiusOverride(dim);
        if (dimOverride > 0) return "dim-override(" + dimOverride + ")";
        int voxyRadius = (cfg.voxyIntegration && VoxyIntegration.PRESENT)
                         ? VoxyIntegration.getRenderDistanceChunks() : -1;
        if (voxyRadius > 0) return "voxy(" + voxyRadius + "×" + cfg.voxyGenRadiusMultiplier + "="
                + Math.round(voxyRadius * cfg.voxyGenRadiusMultiplier) + ")";
        return "config(" + cfg.generationRadius + ")";
    }

    private static long getTrigger(RegistryKey<World> dim) {
        ChunkyPregenConfig cfg = cfg();
        long t;
        if (cfg.voxyIntegration && VoxyIntegration.PRESENT) {
            int vr = VoxyIntegration.getRenderDistanceChunks();
            if (vr > 0) {
                // Gen radius in blocks = vr × voxyGenRadiusMultiplier × 16
                // Trigger = that × voxyTriggerMultiplier (fraction of gen radius)
                // Default 0.1 = retrigger after moving 10% of the gen radius → very responsive
                long genRadiusBlocks = Math.round(vr * cfg.voxyGenRadiusMultiplier * 16.0);
                t = Math.round(genRadiusBlocks * cfg.voxyTriggerMultiplier);
            } else {
                t = cfg.triggerDistance;
            }
        } else {
            t = cfg.triggerDistance;
        }
        return (cfg.netherAutoScale && dim.equals(NETHER)) ? t / 8 : t;
    }

    private static int getDimRadiusOverride(RegistryKey<World> dim) {
        ChunkyPregenConfig cfg = cfg();
        if (dim.equals(OVERWORLD)) return cfg.overworldRadius;
        if (dim.equals(NETHER))    return cfg.netherRadius;
        if (dim.equals(END))       return cfg.endRadius;
        return -1;
    }

    private static boolean isDimensionEnabled(RegistryKey<World> dim) {
        ChunkyPregenConfig cfg = cfg();
        if (dim.equals(OVERWORLD)) return cfg.enableOverworld;
        if (dim.equals(NETHER))    return cfg.enableNether;
        if (dim.equals(END))       return cfg.enableEnd;
        return false;
    }

    private static void setDimState(MinecraftServer server, RegistryKey<World> dim, DimensionState newState) {
        DimensionState old = dimensionStates.getOrDefault(dim, DimensionState.IDLE);
        dimensionStates.put(dim, newState);
        if (newState == DimensionState.RUNNING) {
            runningTickCount.put(dim, 0);
            pollFalseStreak.put(dim, 0);
            // Start poll countdown immediately
            int initPoll = ChunkyIntegration.isAvailable() ? POLL_TICKS : FALLBACK_TIMEOUT_TICKS;
            chunkyPollCountdown.put(dim, initPoll);
        } else {
            runningTickCount.remove(dim);
            pollFalseStreak.remove(dim);
            chunkyPollCountdown.remove(dim);
        }
        if (cfg().debugMode && old != newState)
            debug(server, dimName(dim) + ": " + old + " → " + newState);
    }

    private static RegistryKey<World> extractDimFromMessage(String msg) {
        if (msg == null) return null;
        String lower = msg.toLowerCase();
        if (lower.contains("overworld"))                               return OVERWORLD;
        if (lower.contains("the_nether") || lower.contains("nether")) return NETHER;
        if (lower.contains("the_end")    || lower.contains(":end"))   return END;
        return null;
    }

    private static void execCmd(MinecraftServer server, String cmd) {
        server.getCommandManager().parseAndExecute(server.getCommandSource(), cmd);
    }

    private static void broadcast(MinecraftServer server, String msg) {
        LOGGER.info("[ChunkyPregen] {}", msg);
        Text line = Text.literal(msg);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
            if (IS_OP.test(p.getCommandSource())) p.sendMessage(line, false);
    }

    private static void debug(MinecraftServer server, String msg) {
        if (!cfg().debugMode) return;
        LOGGER.debug("[ChunkyPregen Debug] {}", msg);
        Text line = Text.literal("§7[ChunkyPregen Debug] §f" + msg);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
            if (IS_OP.test(p.getCommandSource())) p.sendMessage(line, false);
    }

    private static ChunkyPregenConfig cfg() { return ChunkyPregenConfig.INSTANCE; }

    // ── Public accessors ──────────────────────────────────────────────────────

    public static String        dimName(RegistryKey<World> dim)       { return dim.getValue().toString(); }
    public static DimensionState getState(RegistryKey<World> dim)     { return dimensionStates.getOrDefault(dim, DimensionState.IDLE); }
    public static BlockPos      getLastCenter(RegistryKey<World> dim) { return lastCenters.get(dim); }
    public static boolean       isTpsPaused()                         { return tpsPaused; }
    public static boolean       isEndVisited()                        { return endVisited; }
    public static void          clearCenter(RegistryKey<World> dim)   { lastCenters.remove(dim); }
    public static void          clearAllCenters()                     { lastCenters.clear(); }
    public static Map<RegistryKey<World>, BlockPos> getLastCenters()  { return Collections.unmodifiableMap(lastCenters); }
    public static boolean       isAnyRunning()                        { return dimensionStates.containsValue(DimensionState.RUNNING); }

    /** Current ring number (1-based), or -1 if no active batch. */
    public static int getBatchRingNumber(RegistryKey<World> dim) {
        return batchRings.containsKey(dim) ? batchRingIndex.getOrDefault(dim, 0) + 1 : -1;
    }

    /** Total rings in the active batch, or -1 if no batch. */
    public static int getBatchTotalRings(RegistryKey<World> dim) {
        List<Integer> rings = batchRings.get(dim);
        return rings != null ? rings.size() : -1;
    }

    /** Current ring radius in chunks, or -1 if no batch. */
    public static int getBatchCurrentRadius(RegistryKey<World> dim) {
        List<Integer> rings = batchRings.get(dim);
        if (rings == null) return -1;
        int idx = batchRingIndex.getOrDefault(dim, 0);
        return idx < rings.size() ? rings.get(idx) : -1;
    }

    /** Target (final) radius of the batch, or -1 if no batch. */
    public static int getBatchTargetRadius(RegistryKey<World> dim) {
        List<Integer> rings = batchRings.get(dim);
        return rings != null ? rings.get(rings.size() - 1) : -1;
    }
}
