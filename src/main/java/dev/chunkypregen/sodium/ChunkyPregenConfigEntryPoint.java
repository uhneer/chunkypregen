package dev.chunkypregen.sodium;

import dev.chunkypregen.config.ChunkyPregenConfig;
import dev.chunkypregen.config.GenerationShape;
import dev.chunkypregen.generation.GenerationTracker;
import dev.chunkypregen.integration.VoxyIntegration;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sodium settings sidebar integration for Chunky Pregenerator.
 *
 * Sodium API compatibility:
 *   - Confirmed: Sodium 0.8.12+mc1.21.11
 *   - Entrypoint: sodium:config_api_user (introduced in Sodium 0.6.x)
 *   - Interface: ConfigEntryPoint / ConfigBuilder / ModOptionsBuilder
 *   - Theoretically compatible with Sodium 0.6.x–0.8.x (API stable across this range)
 *
 * Pages:
 *   1. General    — master toggles, timing, concurrency, spiral, stop button
 *   2. Distances  — radius, trigger, Nether scale, per-dim overrides, Voxy
 *   3. Dimensions — dimension toggles, player filters, End gate
 *   4. Performance — TPS pause, progress relay
 */
public class ChunkyPregenConfigEntryPoint implements ConfigEntryPoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkypregen/sodium");

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        try {
            String version = FabricLoader.getInstance()
                    .getModContainer("chunkypregen")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString())
                    .orElse("1.0.0");

            ModOptionsBuilder mod = builder.registerModOptions("chunkypregen", "Chunky Pregenerator", version);
            mod.setIcon(Identifier.of("chunkypregen", "textures/icon.png"));

            mod.addPage(buildGeneralPage(builder));
            mod.addPage(buildDimensionsPage(builder));
            mod.addPage(buildPerformancePage(builder));
            mod.addPage(buildVoxyPage(builder));
            mod.addPage(buildHudPage(builder));
        } catch (Exception e) {
            LOGGER.warn("[ChunkyPregen] Could not register Sodium settings — UI unavailable", e);
        }
    }

    // ── Page 1: General ──────────────────────────────────────────────────────

    private OptionPageBuilder buildGeneralPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("General"));

        OptionGroupBuilder main = b.createOptionGroup().setName(Text.literal("Generation"));

        main.addOption(b.createBooleanOption(Identifier.of(ns, "enabled"))
                .setName(Text.literal("Enable Chunky Pregenerator"))
                .setTooltip(Text.literal("Master switch for the entire mod. When OFF, no generation runs AND the HUD widget is hidden — the mod is effectively dormant. (Does not cancel an already-running job; use Stop below for that.)"))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enabled = v; cfg.save(); }, () -> cfg.enabled)
                .setStorageHandler(cfg::save));

        main.addOption(b.createBooleanOption(Identifier.of(ns, "auto_retrigger"))
                .setName(Text.literal("Auto-retrigger on Movement"))
                .setTooltip(Text.literal("When ON, a new bundle fires automatically once you move past the deadzone — checked every Position Check Interval (your poll rate), not every tick. When OFF, generation only runs on world join or manual trigger; moving never retriggers."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.autoRetrigger = v; cfg.save(); }, () -> cfg.autoRetrigger)
                .setStorageHandler(cfg::save));

        if (!GenerationTracker.chunkyAvailable) {
            main.addOption(b.createBooleanOption(Identifier.of(ns, "chunky_warning"))
                    .setName(Text.literal("§cChunky not detected!"))
                    .setTooltip(Text.literal("Chunky mod not found. Install Chunky 1.4.55 or later."))
                    .setDefaultValue(false).setEnabled(false)
                    .setBinding(v -> {}, () -> false)
                    .setStorageHandler(cfg::save));
        }

        main.addOption(b.createEnumOption(Identifier.of(ns, "shape"), GenerationShape.class)
                .setName(Text.literal("Shape"))
                .setTooltip(Text.literal("Circle (recommended) or square area centered on the player."))
                .setDefaultValue(GenerationShape.CIRCLE)
                .setElementNameProvider(s -> Text.literal(s == GenerationShape.CIRCLE ? "Circle" : "Square"))
                .setBinding(v -> { cfg.shape = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.shape)
                .setStorageHandler(cfg::save));

        main.addOption(b.createIntegerOption(Identifier.of(ns, "gen_radius"))
                .setName(Text.literal("Generation Radius"))
                .setTooltip(Text.literal("How far from you chunks get pre-generated. The deadzone — how far you need to walk before a new bundle fires — is automatically kept at half this distance. At 250 chunks the deadzone is 2000 blocks, so there's always a buffer of pre-generated terrain ahead of you. Nether is auto-scaled by 1/8."))
                .setRange(100, 5000, 50)
                .setDefaultValue(250)
                .setValueFormatter(v -> Text.literal(v + " chunks"))
                .setBinding(v -> {
                    cfg.generationRadius = v;
                    cfg.triggerDistance  = v / 2 * 16;
                    dev.chunkypregen.monitor.HudData.triggerDistanceBlocks = cfg.triggerDistance;
                    cfg.save();
                    GenerationTracker.scheduleSettingsRegenIfInWorld();
                }, () -> cfg.generationRadius)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(main);

        OptionGroupBuilder spiral = b.createOptionGroup().setName(Text.literal("Spiral Settings"));

        spiral.addOption(b.createBooleanOption(Identifier.of(ns, "spiral_generation"))
                .setName(Text.literal("Spiral (Inside-Out) Generation"))
                .setTooltip(Text.literal("Splits the area into concentric rings and generates closest first. Each ring builds on what Chunky already skips, so nearby terrain is always done first."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.spiralGeneration = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.spiralGeneration)
                .setStorageHandler(cfg::save));

        spiral.addOption(b.createIntegerOption(Identifier.of(ns, "ring_step"))
                .setName(Text.literal("Ring Density"))
                .setTooltip(Text.literal("Lower = more rings, finer steps near the player. Higher = fewer rings, coarser. Curve is quadratic so close rings are always denser than far ones."))
                .setRange(5, 100, 5)
                .setDefaultValue(25)
                .setValueFormatter(v -> Text.literal(v + " chunk step"))
                .setBinding(v -> { cfg.generationRingStep = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.generationRingStep)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(spiral);

        OptionGroupBuilder controls = b.createOptionGroup().setName(Text.literal("Controls"));

        controls.addOption(b.createBooleanOption(Identifier.of(ns, "stop_generation"))
                .setName(Text.literal("§c■ Stop Generation"))
                .setTooltip(Text.literal("Cancels all active Chunky jobs immediately. Toggle ON to confirm."))
                .setDefaultValue(false)
                .setBinding(v -> {
                    if (v) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.getServer() != null) GenerationTracker.cancelAll(mc.getServer());
                    }
                }, () -> false)
                .setStorageHandler(() -> {}));

        page.addOptionGroup(controls);
        return page;
    }

    // ── Page 2: Dimensions ────────────────────────────────────────────────────

    private OptionPageBuilder buildDimensionsPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Dimensions"));

        OptionGroupBuilder dims = b.createOptionGroup().setName(Text.literal("Enabled Dimensions"));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_overworld"))
                .setName(Text.literal("Overworld"))
                .setTooltip(Text.literal("Pre-generate chunks in the Overworld."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enableOverworld = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableOverworld)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_nether"))
                .setName(Text.literal("Nether"))
                .setTooltip(Text.literal("Pre-generate chunks in the Nether."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enableNether = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableNether)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_end"))
                .setName(Text.literal("The End"))
                .setTooltip(Text.literal("Disabled by default — pre-generating the End can interfere with the Dragon fight on first entry."))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.enableEnd = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableEnd)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "require_end_visit"))
                .setName(Text.literal("Wait for End Visit"))
                .setTooltip(Text.literal("Suppresses End pre-gen until a player has actually entered The End. Prevents generating terrain before the Dragon fight triggers."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.requireEndVisit = v; cfg.save(); }, () -> cfg.requireEndVisit)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "nether_auto_scale"))
                .setName(Text.literal("Nether Auto-scale (÷8)"))
                .setTooltip(Text.literal("Divides the Nether radius and deadzone by 8 to match the 1:8 Overworld/Nether coordinate scale."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.netherAutoScale = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.netherAutoScale)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(dims);

        OptionGroupBuilder dimRadius = b.createOptionGroup().setName(Text.literal("Per-Dimension Radius Overrides"));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "overworld_radius"))
                .setName(Text.literal("Overworld Override"))
                .setTooltip(Text.literal("Custom radius for the Overworld only. 0 = use global radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit global") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.overworldRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.overworldRadius < 0 ? 0 : cfg.overworldRadius)
                .setStorageHandler(cfg::save));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "nether_radius"))
                .setName(Text.literal("Nether Override"))
                .setTooltip(Text.literal("Custom radius for the Nether (before auto-scale). 0 = use global radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit global") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.netherRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.netherRadius < 0 ? 0 : cfg.netherRadius)
                .setStorageHandler(cfg::save));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "end_radius"))
                .setName(Text.literal("End Override"))
                .setTooltip(Text.literal("Custom radius for The End. 0 = use global radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit global") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.endRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.endRadius < 0 ? 0 : cfg.endRadius)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(dimRadius);

        OptionGroupBuilder players = b.createOptionGroup().setName(Text.literal("Player Filters"));

        players.addOption(b.createBooleanOption(Identifier.of(ns, "skip_creative"))
                .setName(Text.literal("Ignore Creative Players"))
                .setTooltip(Text.literal("Creative players are excluded from position checks. Stops admins flying around from spamming generation jobs."))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.skipCreativePlayers = v; cfg.save(); }, () -> cfg.skipCreativePlayers)
                .setStorageHandler(cfg::save));

        players.addOption(b.createBooleanOption(Identifier.of(ns, "skip_spectator"))
                .setName(Text.literal("Ignore Spectator Players"))
                .setTooltip(Text.literal("Spectator players are excluded from position checks."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.skipSpectatorPlayers = v; cfg.save(); }, () -> cfg.skipSpectatorPlayers)
                .setStorageHandler(cfg::save));

        players.addOption(b.createIntegerOption(Identifier.of(ns, "min_players"))
                .setName(Text.literal("Min Players to Trigger"))
                .setTooltip(Text.literal("Generation only fires when at least this many eligible players are online."))
                .setRange(1, 20, 1)
                .setDefaultValue(1)
                .setValueFormatter(v -> Text.literal(v + " player" + (v == 1 ? "" : "s")))
                .setBinding(v -> { cfg.minPlayersToTrigger = v; cfg.save(); }, () -> cfg.minPlayersToTrigger)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(players);
        return page;
    }

    // ── Page 3: Performance ───────────────────────────────────────────────────

    private OptionPageBuilder buildPerformancePage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";
        int cpuThreads = Runtime.getRuntime().availableProcessors();
        int autoCount  = Math.max(1, (int) Math.round(cpuThreads * 0.25));

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Performance"));

        OptionGroupBuilder threads = b.createOptionGroup().setName(Text.literal("Chunky Threads"));

        threads.addOption(b.createBooleanOption(Identifier.of(ns, "auto_threads"))
                .setName(Text.literal("Automatic Thread Count"))
                .setTooltip(Text.literal("Automatically uses 25% of logical CPU threads (" + autoCount + " of " + cpuThreads + " on this machine). Turn off to set manually."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.autoThreads = v; cfg.save(); }, () -> cfg.autoThreads)
                .setStorageHandler(cfg::save));

        threads.addOption(b.createIntegerOption(Identifier.of(ns, "chunky_threads"))
                .setName(Text.literal("Thread Count"))
                .setTooltip(Text.literal("Manual thread count. Unlocks only when Automatic Thread Count is OFF. More threads = faster gen, more lag while running."))
                .setRange(2, cpuThreads, 1)
                .setDefaultValue(autoCount)
                // Dynamically enabled: unlocks the moment Automatic Thread Count is toggled OFF.
                .setEnabledProvider(
                        state -> !state.readBooleanOption(Identifier.of(ns, "auto_threads")),
                        Identifier.of(ns, "auto_threads"))
                .setValueFormatter(v -> Text.literal(v + " / " + cpuThreads + " threads"))
                .setBinding(v -> { cfg.chunkyThreads = v; cfg.save(); }, () -> cfg.autoThreads ? autoCount : cfg.chunkyThreads)
                .setStorageHandler(cfg::save));

        threads.addOption(b.createIntegerOption(Identifier.of(ns, "max_concurrent"))
                .setName(Text.literal("Max Concurrent Dimensions"))
                .setTooltip(Text.literal("How many dimensions can generate simultaneously. Extra dims queue."))
                .setRange(1, 3, 1)
                .setDefaultValue(1)
                .setValueFormatter(v -> Text.literal(v + " dim" + (v == 1 ? "" : "s")))
                .setBinding(v -> { cfg.maxConcurrentDimensions = v; cfg.save(); }, () -> cfg.maxConcurrentDimensions)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(threads);

        OptionGroupBuilder tps = b.createOptionGroup().setName(Text.literal("TPS Auto-pause"));

        tps.addOption(b.createBooleanOption(Identifier.of(ns, "auto_tps_pause"))
                .setName(Text.literal("Pause on Low TPS"))
                .setTooltip(Text.literal("Pauses Chunky when server TPS drops, resumes when it recovers. Prevents generation from making lag worse."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.autoTpsPause = v; cfg.save(); }, () -> cfg.autoTpsPause)
                .setStorageHandler(cfg::save));

        tps.addOption(b.createIntegerOption(Identifier.of(ns, "tps_pause_threshold"))
                .setName(Text.literal("Pause Threshold"))
                .setTooltip(Text.literal("Chunky pauses when server TPS drops below this value."))
                .setRange(5, 18, 1)
                .setDefaultValue(15)
                .setValueFormatter(v -> Text.literal(v + " TPS"))
                .setBinding(v -> { cfg.tpsPauseThreshold = v; cfg.save(); }, () -> cfg.tpsPauseThreshold)
                .setStorageHandler(cfg::save));

        tps.addOption(b.createIntegerOption(Identifier.of(ns, "tps_resume_threshold"))
                .setName(Text.literal("Resume Threshold"))
                .setTooltip(Text.literal("Keep above Pause Threshold to avoid rapid pause/resume cycles."))
                .setRange(10, 20, 1)
                .setDefaultValue(18)
                .setValueFormatter(v -> Text.literal(v + " TPS"))
                .setBinding(v -> { cfg.tpsResumeThreshold = v; cfg.save(); }, () -> cfg.tpsResumeThreshold)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(tps);

        OptionGroupBuilder timing = b.createOptionGroup().setName(Text.literal("Timing"));

        timing.addOption(b.createIntegerOption(Identifier.of(ns, "join_delay"))
                .setName(Text.literal("World Join Delay"))
                .setTooltip(Text.literal("Seconds to wait after joining before firing the first bundle. Covers the Sodium LOD rebuild sequence on join."))
                .setRange(10, 60, 5)
                .setDefaultValue(15)
                .setValueFormatter(v -> Text.literal(v + "s"))
                .setBinding(v -> { cfg.joinDelaySeconds = v; cfg.save(); }, () -> cfg.joinDelaySeconds)
                .setStorageHandler(cfg::save));

        timing.addOption(b.createIntegerOption(Identifier.of(ns, "check_interval"))
                .setName(Text.literal("Position Check Interval"))
                .setTooltip(Text.literal("How often the mod checks player positions. Lower = more responsive at a tiny CPU cost."))
                .setRange(20, 600, 20)
                .setDefaultValue(200)
                .setValueFormatter(v -> Text.literal(v / 20 + "s"))
                .setBinding(v -> { cfg.checkIntervalTicks = v; cfg.save(); }, () -> cfg.checkIntervalTicks)
                .setStorageHandler(cfg::save));

        timing.addOption(b.createIntegerOption(Identifier.of(ns, "lod_refresh_seconds"))
                .setName(Text.literal("LOD Rebuild Hold Duration"))
                .setTooltip(Text.literal("How long to hold RD=32 during the Voxy LOD rebuild cycle at bundle start. Longer = more LOD sections rebuilt before returning to normal."))
                .setRange(15, 30, 1)
                .setDefaultValue(20)
                .setValueFormatter(v -> Text.literal(v + "s"))
                .setBinding(v -> { cfg.lodRefreshSeconds = v; cfg.save(); }, () -> cfg.lodRefreshSeconds)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(timing);
        return page;
    }

    // ── Page 4: Voxy ─────────────────────────────────────────────────────────

    private OptionPageBuilder buildVoxyPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Voxy"));

        if (!VoxyIntegration.PRESENT) {
            OptionGroupBuilder absent = b.createOptionGroup().setName(Text.literal("Voxy Not Installed"));
            absent.addOption(b.createBooleanOption(Identifier.of(ns, "voxy_absent"))
                    .setName(Text.literal("§7Voxy not detected"))
                    .setTooltip(Text.literal("Install Voxy to let ChunkyPregen base generation radius on your LOD render distance."))
                    .setDefaultValue(false).setEnabled(false)
                    .setBinding(v -> {}, () -> false)
                    .setStorageHandler(cfg::save));
            page.addOptionGroup(absent);
            return page;
        }

        VoxyIntegration.invalidateCache();

        OptionGroupBuilder voxy = b.createOptionGroup().setName(Text.literal("Voxy Integration"));

        voxy.addOption(b.createBooleanOption(Identifier.of(ns, "voxy_integration"))
                .setName(Text.literal("Drive Radius from Voxy RD"))
                .setTooltip(Text.literal(buildVoxyIntegrationTooltip(cfg)))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.voxyIntegration = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.voxyIntegration)
                .setStorageHandler(cfg::save));

        voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_render_distance"))
                .setName(Text.literal("Detected Render Distance"))
                .setTooltip(Text.literal(buildVoxyDistanceTooltip()))
                .setRange(1, 65536, 1)
                .setDefaultValue(512)
                .setEnabled(false)
                .setValueFormatter(v -> {
                    int live = VoxyIntegration.getRenderDistanceChunks();
                    return live > 0 ? Text.literal(live + " chunks") : Text.literal("unreadable");
                })
                .setBinding(v -> {}, () -> {
                    int live = VoxyIntegration.getRenderDistanceChunks();
                    return live > 0 ? live : cfg.voxyRenderDistance;
                })
                .setStorageHandler(() -> {}));

        voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_gen_radius_mult"))
                .setName(Text.literal("Gen Radius Multiplier"))
                .setTooltip(Text.literal("Generation radius = Voxy chunks × this. 2.0× means generate twice your view distance."))
                .setRange(10, 50, 1)
                .setDefaultValue(20)
                .setValueFormatter(v -> Text.literal(String.format("%.1f×", v / 10.0f)))
                .setBinding(v -> { cfg.voxyGenRadiusMultiplier = v / 10.0f; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> Math.round(cfg.voxyGenRadiusMultiplier * 10))
                .setStorageHandler(cfg::save));

        voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_trigger_mult"))
                .setName(Text.literal("Deadzone Multiplier"))
                .setTooltip(Text.literal("Deadzone = (gen radius in blocks) × this. 0.10× = retrigger after moving 10% of the generation radius."))
                .setRange(1, 50, 1)
                .setDefaultValue(10)
                .setValueFormatter(v -> Text.literal(String.format("%.2f×", v / 100.0f)))
                .setBinding(v -> { cfg.voxyTriggerMultiplier = v / 100.0f; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> Math.round(cfg.voxyTriggerMultiplier * 100))
                .setStorageHandler(cfg::save));

        page.addOptionGroup(voxy);
        return page;
    }

    // ── Page 5: HUD ──────────────────────────────────────────────────────────

    private OptionPageBuilder buildHudPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("HUD"));

        OptionGroupBuilder display = b.createOptionGroup().setName(Text.literal("Widget"));

        display.addOption(b.createEnumOption(Identifier.of(ns, "hud_position"), dev.chunkypregen.config.HudPosition.class)
                .setName(Text.literal("Position"))
                .setTooltip(Text.literal("Corner of the screen where the circular progress widget appears."))
                .setDefaultValue(dev.chunkypregen.config.HudPosition.TOP_LEFT)
                .setElementNameProvider(p -> Text.literal(switch (p) {
                    case TOP_LEFT    -> "Top Left";
                    case TOP_CENTER  -> "Top Center";
                    case TOP_RIGHT   -> "Top Right";
                    case BOTTOM_LEFT -> "Bottom Left";
                    default          -> "Bottom Right";
                }))
                .setBinding(v -> { cfg.hudPosition = v; cfg.save(); }, () -> cfg.hudPosition)
                .setStorageHandler(cfg::save));

        display.addOption(b.createIntegerOption(Identifier.of(ns, "hud_scale"))
                .setName(Text.literal("Size"))
                .setTooltip(Text.literal("Scale of the HUD widget. 10 = default size. 5 = half size, 20 = double size."))
                .setRange(5, 20, 1)
                .setDefaultValue(10)
                .setValueFormatter(v -> Text.literal(String.format("%.1f×", v / 10.0f)))
                .setBinding(v -> { cfg.hudScale = v / 10.0f; cfg.save(); }, () -> Math.round(cfg.hudScale * 10))
                .setStorageHandler(cfg::save));

        page.addOptionGroup(display);

        OptionGroupBuilder progress = b.createOptionGroup().setName(Text.literal("Progress Output"));

        progress.addOption(b.createBooleanOption(Identifier.of(ns, "progress_relay"))
                .setName(Text.literal("Suppress Chunky Chat Spam"))
                .setTooltip(Text.literal("Silences Chunky's per-second progress messages in chat. The HUD widget shows live progress instead. Recommended: ON."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.progressRelay = v; cfg.save(); }, () -> cfg.progressRelay)
                .setStorageHandler(cfg::save));

        progress.addOption(b.createIntegerOption(Identifier.of(ns, "progress_relay_interval"))
                .setName(Text.literal("Chat Summary Interval"))
                .setTooltip(Text.literal("When chat spam is suppressed, a brief summary is still broadcast at this interval."))
                .setRange(1, 30, 1)
                .setDefaultValue(5)
                .setValueFormatter(v -> Text.literal("Every " + v + " min"))
                .setBinding(v -> { cfg.progressRelayIntervalMinutes = v; cfg.save(); }, () -> cfg.progressRelayIntervalMinutes)
                .setStorageHandler(cfg::save));

        progress.addOption(b.createBooleanOption(Identifier.of(ns, "chat_notifications"))
                .setName(Text.literal("Start / Complete Messages"))
                .setTooltip(Text.literal("Broadcasts a message when a generation bundle starts or finishes. Only shown to OPs."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.chatNotifications = v; cfg.save(); }, () -> cfg.chatNotifications)
                .setStorageHandler(cfg::save));

        progress.addOption(b.createBooleanOption(Identifier.of(ns, "debug_mode"))
                .setName(Text.literal("Debug Logging"))
                .setTooltip(Text.literal("Broadcasts internal state events (ring advances, watchdog ticks, position checks) to chat. Only useful for diagnosing issues."))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.debugMode = v; cfg.save(); }, () -> cfg.debugMode)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(progress);
        return page;
    }

    // ── Tooltip helpers ───────────────────────────────────────────────────────

    private static String buildVoxyIntegrationTooltip(ChunkyPregenConfig cfg) {
        int vr = VoxyIntegration.getRenderDistanceChunks();
        if (vr <= 0)
            return "Drives generation radius and deadzone from your Voxy LOD render distance. Currently: Voxy unreadable.";
        int genRadius = Math.max(1, Math.round(vr * cfg.voxyGenRadiusMultiplier));
        long deadzone = Math.round(vr * cfg.voxyGenRadiusMultiplier * 16.0 * cfg.voxyTriggerMultiplier);
        return String.format("Currently reading %d chunks from Voxy. Gen radius: %d chunks. Deadzone: %d blocks.", vr, genRadius, deadzone);
    }

    private static String buildVoxyDistanceTooltip() {
        int live = VoxyIntegration.getRenderDistanceChunks();
        if (live <= 0)
            return "Live read from Voxy via reflection or voxy-config.json. Currently unreadable.";
        return String.format("Currently reading %d chunks (%.1f sections) from Voxy.", live, live / 32.0f);
    }
}
