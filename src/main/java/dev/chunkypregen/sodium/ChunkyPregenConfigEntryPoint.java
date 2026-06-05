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
            mod.addPage(buildDistancesPage(builder));
            mod.addPage(buildDimensionsPage(builder));
            mod.addPage(buildPerformancePage(builder));
        } catch (Exception e) {
            LOGGER.warn("[ChunkyPregen] Could not register Sodium settings — UI unavailable", e);
        }
    }

    // ── Page 1: General ───────────────────────────────────────────────────────

    private OptionPageBuilder buildGeneralPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("General"));
        OptionGroupBuilder main = b.createOptionGroup().setName(Text.literal("General Settings"));

        main.addOption(b.createBooleanOption(Identifier.of(ns, "enabled"))
                .setName(Text.literal("Master Enable"))
                .setTooltip(Text.literal("Enables or disables all automatic chunk pre-generation. Disabling does not cancel a running job — use the Stop button below."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enabled = v; cfg.save(); }, () -> cfg.enabled)
                .setStorageHandler(cfg::save));

        if (!GenerationTracker.chunkyAvailable) {
            main.addOption(b.createBooleanOption(Identifier.of(ns, "chunky_warning"))
                    .setName(Text.literal("§cChunky not detected!"))
                    .setTooltip(Text.literal("The Chunky mod was not found. Install Chunky 1.4.55 or later."))
                    .setDefaultValue(false).setEnabled(false)
                    .setBinding(v -> {}, () -> false)
                    .setStorageHandler(cfg::save));
        }

        main.addOption(b.createBooleanOption(Identifier.of(ns, "debug_mode"))
                .setName(Text.literal("Debug Mode"))
                .setTooltip(Text.literal("Broadcasts verbose internal events (tick checks, ring progress, state transitions) as chat messages. Useful for diagnosing issues."))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.debugMode = v; cfg.save(); }, () -> cfg.debugMode)
                .setStorageHandler(cfg::save));

        main.addOption(b.createIntegerOption(Identifier.of(ns, "join_delay"))
                .setName(Text.literal("World Join Delay"))
                .setTooltip(Text.literal("How long to wait after joining a world before firing the first Chunky bundle. Lets the Sodium render-distance dump sequence complete so commands don't get cancelled mid-load. Does not affect movement-triggered bundles. Default: 15s."))
                .setRange(15, 60, 5)
                .setDefaultValue(15)
                .setValueFormatter(v -> Text.literal(v + "s"))
                .setBinding(v -> { cfg.joinDelaySeconds = v; cfg.save(); }, () -> cfg.joinDelaySeconds)
                .setStorageHandler(cfg::save));

        main.addOption(b.createIntegerOption(Identifier.of(ns, "check_interval"))
                .setName(Text.literal("Check Interval"))
                .setTooltip(Text.literal("How often the mod checks player positions against the last generation center. 20 ticks = 1 second. Lower values react faster at a small CPU cost."))
                .setRange(20, 1200, 20)
                .setDefaultValue(200)
                .setValueFormatter(v -> Text.literal(v / 20 + "s (" + v + " ticks)"))
                .setBinding(v -> { cfg.checkIntervalTicks = v; cfg.save(); }, () -> cfg.checkIntervalTicks)
                .setStorageHandler(cfg::save));

        main.addOption(b.createIntegerOption(Identifier.of(ns, "max_concurrent"))
                .setName(Text.literal("Max Concurrent Dimensions"))
                .setTooltip(Text.literal("How many dimensions can have active Chunky jobs simultaneously. Extra jobs are queued. Higher values use more CPU and disk I/O."))
                .setRange(1, 3, 1)
                .setDefaultValue(1)
                .setValueFormatter(v -> Text.literal(v + " dim" + (v == 1 ? "" : "s")))
                .setBinding(v -> { cfg.maxConcurrentDimensions = v; cfg.save(); }, () -> cfg.maxConcurrentDimensions)
                .setStorageHandler(cfg::save));

        main.addOption(b.createEnumOption(Identifier.of(ns, "shape"), GenerationShape.class)
                .setName(Text.literal("Generation Shape"))
                .setTooltip(Text.literal("Circle generates a circular area from center outward (recommended). Square generates a square region of the same radius."))
                .setDefaultValue(GenerationShape.CIRCLE)
                .setElementNameProvider(s -> Text.literal(s == GenerationShape.CIRCLE ? "Circle" : "Square"))
                .setBinding(v -> { cfg.shape = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.shape)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(main);

        // Spiral generation group
        OptionGroupBuilder spiral = b.createOptionGroup().setName(Text.literal("Spiral Generation"));

        spiral.addOption(b.createBooleanOption(Identifier.of(ns, "spiral_generation"))
                .setName(Text.literal("Spiral (Inside-Out) Generation"))
                .setTooltip(Text.literal("Fires Chunky with increasing radii ring by ring, ensuring chunks near you are generated first. Chunky skips already-done chunks so each ring only generates the new outer band. Recommended: ON."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.spiralGeneration = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.spiralGeneration)
                .setStorageHandler(cfg::save));

        spiral.addOption(b.createIntegerOption(Identifier.of(ns, "ring_step"))
                .setName(Text.literal("Ring Density"))
                .setTooltip(Text.literal("Controls how many rings the spiral generates. Lower = more rings with smaller steps near you (denser, more jobs). Higher = fewer rings with larger gaps (coarser). Rings use a quadratic curve so steps near you are always smaller than steps far out."))
                .setRange(10, 200, 5)
                .setDefaultValue(25)
                .setValueFormatter(v -> Text.literal(v + " (density param)"))
                .setBinding(v -> { cfg.generationRingStep = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.generationRingStep)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(spiral);

        // Stop button — at the bottom of General page
        OptionGroupBuilder controls = b.createOptionGroup().setName(Text.literal("Controls"));

        controls.addOption(b.createBooleanOption(Identifier.of(ns, "stop_generation"))
                .setName(Text.literal("§c■ Stop Generation"))
                .setTooltip(Text.literal("Immediately cancels all active generation jobs and clears the queue. Toggle to ON to confirm. Only works in singleplayer (integrated server)."))
                .setDefaultValue(false)
                .setBinding(v -> {
                    if (v) {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        if (mc.getServer() != null) {
                            GenerationTracker.cancelAll(mc.getServer());
                        }
                    }
                }, () -> false)
                .setStorageHandler(() -> {})); // no-op: this is a one-shot action, not a persisted setting

        page.addOptionGroup(controls);
        return page;
    }

    // ── Page 2: Distances ─────────────────────────────────────────────────────

    private OptionPageBuilder buildDistancesPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Distances"));

        OptionGroupBuilder global = b.createOptionGroup().setName(Text.literal("Global Thresholds"));

        global.addOption(b.createIntegerOption(Identifier.of(ns, "trigger_distance"))
                .setName(Text.literal("Deadzone Radius (blocks)"))
                .setTooltip(Text.literal("Fallback deadzone used only when Voxy integration is OFF. When Voxy is active, the deadzone is computed from your render distance × the Deadzone Multiplier above. Nether is auto-scaled ÷8."))
                .setRange(500, 50000, 500)
                .setDefaultValue(3500)
                .setValueFormatter(v -> Text.literal(v + " blocks"))
                .setBinding(v -> { cfg.triggerDistance = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.triggerDistance)
                .setStorageHandler(cfg::save));

        global.addOption(b.createIntegerOption(Identifier.of(ns, "gen_radius"))
                .setName(Text.literal("Generation Radius (chunks)"))
                .setTooltip(Text.literal("Fallback radius used only when Voxy integration is OFF. When Voxy is active, the radius is Voxy render distance × Gen Radius Multiplier (see above)."))
                .setRange(100, 5000, 100)
                .setDefaultValue(500)
                .setValueFormatter(v -> Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.generationRadius = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.generationRadius)
                .setStorageHandler(cfg::save));

        global.addOption(b.createBooleanOption(Identifier.of(ns, "nether_auto_scale"))
                .setName(Text.literal("Nether Auto-scale (÷8)"))
                .setTooltip(Text.literal("Divides the Nether trigger distance and generation radius by 8 to account for the 1:8 coordinate scale between Overworld and Nether."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.netherAutoScale = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.netherAutoScale)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(global);

        // Per-dimension radius overrides
        OptionGroupBuilder dimRadius = b.createOptionGroup().setName(Text.literal("Per-Dimension Radius Overrides"));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "overworld_radius"))
                .setName(Text.literal("Overworld Radius"))
                .setTooltip(Text.literal("Overworld-specific generation radius. 0 = inherit global/Voxy radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.overworldRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.overworldRadius < 0 ? 0 : cfg.overworldRadius)
                .setStorageHandler(cfg::save));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "nether_radius"))
                .setName(Text.literal("Nether Radius"))
                .setTooltip(Text.literal("Nether-specific generation radius (before auto-scale is applied). 0 = inherit global/Voxy radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.netherRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.netherRadius < 0 ? 0 : cfg.netherRadius)
                .setStorageHandler(cfg::save));

        dimRadius.addOption(b.createIntegerOption(Identifier.of(ns, "end_radius"))
                .setName(Text.literal("End Radius"))
                .setTooltip(Text.literal("End-specific generation radius. 0 = inherit global/Voxy radius."))
                .setRange(0, 5000, 100)
                .setDefaultValue(0)
                .setValueFormatter(v -> v == 0 ? Text.literal("Inherit") : Text.literal(v + " chunks"))
                .setBinding(v -> { cfg.endRadius = v == 0 ? -1 : v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                            () -> cfg.endRadius < 0 ? 0 : cfg.endRadius)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(dimRadius);

        // Voxy section
        if (VoxyIntegration.PRESENT) {
            // Invalidate cache so the tooltips (built once at page-open time) reflect the
            // current Voxy render distance rather than a value cached up to 30 s ago.
            VoxyIntegration.invalidateCache();

            OptionGroupBuilder voxy = b.createOptionGroup().setName(Text.literal("Voxy Integration"));

            voxy.addOption(b.createBooleanOption(Identifier.of(ns, "voxy_integration"))
                    .setName(Text.literal("Use Voxy Render Distance"))
                    .setTooltip(Text.literal(buildVoxyIntegrationTooltip(cfg)))
                    .setDefaultValue(true)
                    .setBinding(v -> { cfg.voxyIntegration = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.voxyIntegration)
                    .setStorageHandler(cfg::save));

            // Read-only display: live Voxy render distance converted to chunks
            voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_render_distance"))
                    .setName(Text.literal("Voxy Render Distance (chunks)"))
                    .setTooltip(Text.literal(buildVoxyDistanceTooltip()))
                    .setRange(1, 65536, 1)
                    .setDefaultValue(512)
                    .setEnabled(false) // read-only
                    .setValueFormatter(v -> {
                        int live = VoxyIntegration.getRenderDistanceChunks();
                        return live > 0
                                ? Text.literal(live + " chunks")
                                : Text.literal("unreadable (" + v + " chunks last known)");
                    })
                    .setBinding(v -> { /* no-op */ }, () -> {
                        int live = VoxyIntegration.getRenderDistanceChunks();
                        return live > 0 ? live : cfg.voxyRenderDistance;
                    })
                    .setStorageHandler(() -> {}));

            // Gen radius multiplier (stored as ×10 integer so Sodium can use a slider)
            voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_gen_radius_mult"))
                    .setName(Text.literal("Gen Radius Multiplier"))
                    .setTooltip(Text.literal("Generation radius = Voxy render distance × this. Default 2.0×: generates 2× your view distance so you never outrun pre-gen. Ignored when Voxy integration is off."))
                    .setRange(10, 50, 1)   // 1.0× – 5.0× in tenths
                    .setDefaultValue(20)
                    .setValueFormatter(v -> Text.literal(String.format("%.1f×", v / 10.0f)))
                    .setBinding(v -> { cfg.voxyGenRadiusMultiplier = v / 10.0f; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                                () -> Math.round(cfg.voxyGenRadiusMultiplier * 10))
                    .setStorageHandler(cfg::save));

            // Trigger multiplier (same ×10 encoding)
            voxy.addOption(b.createIntegerOption(Identifier.of(ns, "voxy_trigger_mult"))
                    .setName(Text.literal("Deadzone Multiplier"))
                    .setTooltip(Text.literal("Re-trigger deadzone = (gen radius in blocks) × this. Default 0.1 = retrigger after 10% of the gen radius. Lower = more frequent retriggers, higher = less frequent. e.g. with 500-chunk voxy and 2× gen mult: gen radius = 16000 blocks, deadzone = 1600 blocks at 0.1."))
                    .setRange(1, 50, 1)    // 0.01× – 0.50× in hundredths
                    .setDefaultValue(10)
                    .setValueFormatter(v -> Text.literal(String.format("%.2f× (%d blocks)", v / 100.0f,
                            Math.round(VoxyIntegration.getRenderDistanceChunks() * cfg.voxyGenRadiusMultiplier * 16 * v / 100.0f))))
                    .setBinding(v -> { cfg.voxyTriggerMultiplier = v / 100.0f; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); },
                                () -> Math.round(cfg.voxyTriggerMultiplier * 100))
                    .setStorageHandler(cfg::save));

            page.addOptionGroup(voxy);
        }

        return page;
    }

    // ── Page 3: Dimensions ────────────────────────────────────────────────────

    private OptionPageBuilder buildDimensionsPage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Dimensions"));

        OptionGroupBuilder dims = b.createOptionGroup().setName(Text.literal("Dimension Toggles"));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_overworld"))
                .setName(Text.literal("Generate in Overworld"))
                .setTooltip(Text.literal("Enables automatic chunk pre-generation in the Overworld."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enableOverworld = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableOverworld)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_nether"))
                .setName(Text.literal("Generate in Nether"))
                .setTooltip(Text.literal("Enables automatic chunk pre-generation in the Nether."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.enableNether = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableNether)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "enable_end"))
                .setName(Text.literal("Generate in The End"))
                .setTooltip(Text.literal("Enables automatic chunk pre-generation in The End. Disabled by default — can interfere with vanilla End progression."))
                .setDefaultValue(false)
                .setBinding(v -> { cfg.enableEnd = v; cfg.save(); GenerationTracker.scheduleSettingsRegenIfInWorld(); }, () -> cfg.enableEnd)
                .setStorageHandler(cfg::save));

        dims.addOption(b.createBooleanOption(Identifier.of(ns, "require_end_visit"))
                .setName(Text.literal("Require End Visit First"))
                .setTooltip(Text.literal("When enabled, End pre-gen is suppressed until a player has physically entered The End. Prevents generating End terrain before the Dragon fight."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.requireEndVisit = v; cfg.save(); }, () -> cfg.requireEndVisit)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(dims);

        OptionGroupBuilder players = b.createOptionGroup().setName(Text.literal("Player Filters"));

        players.addOption(b.createBooleanOption(Identifier.of(ns, "skip_creative"))
                .setName(Text.literal("Ignore Creative Players"))
                .setTooltip(Text.literal("Creative-mode players are excluded from distance checks. Prevents admins flying in Creative from triggering generation jobs."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.skipCreativePlayers = v; cfg.save(); }, () -> cfg.skipCreativePlayers)
                .setStorageHandler(cfg::save));

        players.addOption(b.createBooleanOption(Identifier.of(ns, "skip_spectator"))
                .setName(Text.literal("Ignore Spectator Players"))
                .setTooltip(Text.literal("Spectator-mode players are excluded from distance checks."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.skipSpectatorPlayers = v; cfg.save(); }, () -> cfg.skipSpectatorPlayers)
                .setStorageHandler(cfg::save));

        players.addOption(b.createIntegerOption(Identifier.of(ns, "min_players"))
                .setName(Text.literal("Min Players to Trigger"))
                .setTooltip(Text.literal("Generation only triggers when at least this many players are online. Useful on multiplayer servers to avoid firing jobs for a lone admin."))
                .setRange(1, 20, 1)
                .setDefaultValue(1)
                .setValueFormatter(v -> Text.literal(v + " player" + (v == 1 ? "" : "s")))
                .setBinding(v -> { cfg.minPlayersToTrigger = v; cfg.save(); }, () -> cfg.minPlayersToTrigger)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(players);

        OptionGroupBuilder notifs = b.createOptionGroup().setName(Text.literal("Notifications"));

        notifs.addOption(b.createBooleanOption(Identifier.of(ns, "chat_notifications"))
                .setName(Text.literal("Generation Notifications"))
                .setTooltip(Text.literal("Sends start/complete messages to OPs only (permission level 2+). Regular players never see these. Disable to silence notifications entirely."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.chatNotifications = v; cfg.save(); }, () -> cfg.chatNotifications)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(notifs);
        return page;
    }

    // ── Dynamic tooltip helpers ───────────────────────────────────────────────

    private static String buildVoxyIntegrationTooltip(ChunkyPregenConfig cfg) {
        int vr = VoxyIntegration.getRenderDistanceChunks();
        if (vr <= 0)
            return "Drives both generation radius and trigger deadzone from your Voxy LOD render distance. " +
                   "Currently: Voxy render distance unreadable.";
        int genRadius = Math.max(1, Math.round(vr * cfg.voxyGenRadiusMultiplier));
        long genRadiusBlocks = Math.round(vr * cfg.voxyGenRadiusMultiplier * 16.0);
        long deadzone = Math.round(genRadiusBlocks * cfg.voxyTriggerMultiplier);
        return String.format(
            "Drives both generation radius and trigger deadzone from your Voxy LOD render distance. " +
            "Currently reading: %d chunks from Voxy. Generation radius will be %d chunks (%d × %.1f×). " +
            "Deadzone will be %d blocks.",
            vr, genRadius, vr, cfg.voxyGenRadiusMultiplier, deadzone);
    }

    private static String buildVoxyDistanceTooltip() {
        int live = VoxyIntegration.getRenderDistanceChunks();
        if (live <= 0)
            return "Live read from Voxy (reflection) or voxy-config.json. Voxy stores render distance in sections " +
                   "(1 section = 32 chunks). Currently: unreadable — check voxy-config.json. Adjust in Voxy's own settings.";
        float sections = live / 32.0f;
        return String.format(
            "Live read from Voxy (reflection) or voxy-config.json. Voxy stores render distance in sections " +
            "(1 section = 32 chunks). Currently detected: %d chunks (≈ %.1f sections). Adjust in Voxy's own settings.",
            live, sections);
    }

    // ── Page 4: Performance ───────────────────────────────────────────────────

    private OptionPageBuilder buildPerformancePage(ConfigBuilder b) {
        ChunkyPregenConfig cfg = ChunkyPregenConfig.INSTANCE;
        String ns = "chunkypregen";

        OptionPageBuilder page = b.createOptionPage().setName(Text.literal("Performance"));

        OptionGroupBuilder tps = b.createOptionGroup().setName(Text.literal("TPS Auto-pause"));

        tps.addOption(b.createBooleanOption(Identifier.of(ns, "auto_tps_pause"))
                .setName(Text.literal("Pause on Low TPS"))
                .setTooltip(Text.literal("Automatically sends 'chunky pause' when server TPS drops below the threshold, and 'chunky continue' when TPS recovers. Requires Chunky 1.4.0+."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.autoTpsPause = v; cfg.save(); }, () -> cfg.autoTpsPause)
                .setStorageHandler(cfg::save));

        tps.addOption(b.createIntegerOption(Identifier.of(ns, "tps_pause_threshold"))
                .setName(Text.literal("Pause Threshold (TPS)"))
                .setTooltip(Text.literal("Chunky is paused when server TPS drops below this value."))
                .setRange(5, 18, 1)
                .setDefaultValue(15)
                .setValueFormatter(v -> Text.literal(v + " TPS"))
                .setBinding(v -> { cfg.tpsPauseThreshold = v; cfg.save(); }, () -> cfg.tpsPauseThreshold)
                .setStorageHandler(cfg::save));

        tps.addOption(b.createIntegerOption(Identifier.of(ns, "tps_resume_threshold"))
                .setName(Text.literal("Resume Threshold (TPS)"))
                .setTooltip(Text.literal("Chunky resumes when TPS recovers above this value. Keep this higher than the pause threshold to prevent rapid pause/resume oscillation."))
                .setRange(10, 20, 1)
                .setDefaultValue(18)
                .setValueFormatter(v -> Text.literal(v + " TPS"))
                .setBinding(v -> { cfg.tpsResumeThreshold = v; cfg.save(); }, () -> cfg.tpsResumeThreshold)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(tps);

        OptionGroupBuilder relay = b.createOptionGroup().setName(Text.literal("Progress Relay"));

        relay.addOption(b.createBooleanOption(Identifier.of(ns, "progress_relay"))
                .setName(Text.literal("Suppress Chunky Spam"))
                .setTooltip(Text.literal("Silences Chunky's per-second progress messages and replaces them with a periodic summary broadcast at the interval below."))
                .setDefaultValue(true)
                .setBinding(v -> { cfg.progressRelay = v; cfg.save(); }, () -> cfg.progressRelay)
                .setStorageHandler(cfg::save));

        relay.addOption(b.createIntegerOption(Identifier.of(ns, "progress_relay_interval"))
                .setName(Text.literal("Progress Summary Interval"))
                .setTooltip(Text.literal("How often to broadcast a generation progress summary to all players (when progress relay is enabled)."))
                .setRange(1, 30, 1)
                .setDefaultValue(5)
                .setValueFormatter(v -> Text.literal("Every " + v + " min"))
                .setBinding(v -> { cfg.progressRelayIntervalMinutes = v; cfg.save(); },
                            () -> cfg.progressRelayIntervalMinutes)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(relay);

        OptionGroupBuilder lod = b.createOptionGroup().setName(Text.literal("LOD Refresh"));

        lod.addOption(b.createIntegerOption(Identifier.of(ns, "lod_refresh_seconds"))
                .setName(Text.literal("LOD Rebuild Hold Duration"))
                .setTooltip(Text.literal("How long to hold render distance at RD=32 during the Voxy LOD rebuild cycle that fires at the start of each generation bundle. Longer = more time for Voxy to rebuild all LOD sections before returning to normal. Too short = some distant LOD may not update. Min: 15s, Max: 30s."))
                .setRange(15, 30, 1)
                .setDefaultValue(20)
                .setValueFormatter(v -> Text.literal(v + "s"))
                .setBinding(v -> { cfg.lodRefreshSeconds = v; cfg.save(); }, () -> cfg.lodRefreshSeconds)
                .setStorageHandler(cfg::save));

        page.addOptionGroup(lod);
        return page;
    }
}
