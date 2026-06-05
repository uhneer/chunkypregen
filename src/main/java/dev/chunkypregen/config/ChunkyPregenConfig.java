package dev.chunkypregen.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Singleton config loaded from config/chunkypregen.json via Gson.
 *
 * All fields are public so Gson can serialize/deserialize them directly.
 * Unknown fields in the JSON are ignored; missing fields fall back to the
 * Java field initializer (i.e. the default value shown here).
 *
 * Compatibility:
 *   - Gson 2.x (bundled with MC since 1.16). Confirmed: Gson 2.13.2 (MC 1.21.11).
 *   - Config file is forwards-compatible: new fields added in future versions of
 *     this mod will simply be missing from older JSON files and use their defaults.
 */
public class ChunkyPregenConfig {

    private static final Logger LOGGER     = LoggerFactory.getLogger("chunkypregen/config");
    private static final Gson   GSON       = new GsonBuilder().setPrettyPrinting().create();
    private static final Path   CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("chunkypregen.json");

    public static final ChunkyPregenConfig INSTANCE = load();

    // ── Master ────────────────────────────────────────────────────────────────
    public boolean enabled   = true;
    public boolean debugMode = false;

    // ── Trigger thresholds ────────────────────────────────────────────────────
    // triggerDistance = generationRadius / 2 * 16 (half the gen radius, in blocks)
    public int triggerDistance    = 2000;
    public int generationRadius   = 250;
    public int checkIntervalTicks = 200;

    // ── Per-dimension radius overrides (-1 = inherit global / Voxy radius) ───
    public int overworldRadius = -1;
    public int netherRadius    = -1;
    public int endRadius       = -1;

    // ── Dimension toggles ─────────────────────────────────────────────────────
    public boolean enableOverworld = true;
    public boolean enableNether    = true;
    public boolean enableEnd       = false;

    // ── Join delay ────────────────────────────────────────────────────────────
    /**
     * Seconds to wait after joining a world before dispatching the first Chunky bundle.
     * Covers the Sodium render-distance dump sequence that fires on join — without this
     * delay the Chunky commands race with that sequence and can be cancelled or ignored.
     * Only applies to on-join; movement-triggered bundles fire immediately.
     * Range: 15–60. Default: 15.
     */
    public int joinDelaySeconds = 15;

    // ── Behaviour flags ───────────────────────────────────────────────────────
    public boolean netherAutoScale  = true;
    public boolean chatNotifications = true;

    // ── Player filtering ──────────────────────────────────────────────────────
    /** If true, players in Creative mode are ignored for distance checks. */
    public boolean skipCreativePlayers   = false;
    /** If true, players in Spectator mode are ignored for distance checks. */
    public boolean skipSpectatorPlayers  = true;
    /** Minimum online players required before a generation job is triggered. */
    public int     minPlayersToTrigger   = 1;

    // ── End auto-gate ─────────────────────────────────────────────────────────
    /** When true, End pre-gen is suppressed until a player has actually visited The End. */
    public boolean requireEndVisit = true;

    // ── TPS auto-pause ────────────────────────────────────────────────────────
    /** Automatically pause Chunky when server TPS drops below tpsPauseThreshold. */
    public boolean autoTpsPause      = true;
    /** TPS at which a running Chunky job is paused. Chunky 1.4+ supports chunky pause/continue. */
    public int     tpsPauseThreshold  = 15;
    /** TPS at which a paused Chunky job is automatically resumed. */
    public int     tpsResumeThreshold = 18;

    // ── Concurrency ───────────────────────────────────────────────────────────
    public int maxConcurrentDimensions = 1;
    /** Number of Chunky worker threads. Defaults to 80% of logical CPU cores. */
    public int chunkyThreads = Math.max(1, (int) Math.round(Runtime.getRuntime().availableProcessors() * 0.8));

    // ── Voxy integration ──────────────────────────────────────────────────────
    /** Use Voxy's LOD render distance to drive generation radius and trigger distance. */
    public boolean voxyIntegration    = false;
    /** Fallback render distance (chunks) if Voxy config cannot be read. */
    public int     voxyRenderDistance = 512;
    /**
     * Generation radius = voxyChunks × this.
     * 2.0 = generate 2× your LOD view distance so you never outrun pre-gen.
     * Only used when voxyIntegration is active.
     */
    public float   voxyGenRadiusMultiplier  = 2.0f;
    /**
     * Trigger (deadzone) radius = voxyChunks × 16 blocks/chunk × this.
     * 1.5 = re-fire once you move 1.5× your view distance from the last center.
     * Only used when voxyIntegration is active; falls back to triggerDistance otherwise.
     */
    public float   voxyTriggerMultiplier    = 0.05f;

    // ── Spiral generation ─────────────────────────────────────────────────────
    /** Fire concentric rings from center outward. Chunky skips already-generated chunks,
     *  so each successive ring only generates the new outer band. */
    public boolean spiralGeneration   = true;
    /** Controls ring density for the quadratic spiral curve. Smaller = more rings / finer near player. */
    public int     generationRingStep = 25;

    // ── Shape ─────────────────────────────────────────────────────────────────
    public GenerationShape shape = GenerationShape.CIRCLE;

    // ── Progress relay ────────────────────────────────────────────────────────
    /** Suppress Chunky's per-second progress spam and relay a summary instead. */
    public boolean progressRelay               = true;
    /** How often (minutes) to broadcast the generation progress summary. */
    public int     progressRelayIntervalMinutes = 5;

    // ── Notification messages ─────────────────────────────────────────────────
    public String startMessage    = "§aChunky Pregenerator: §fStarting generation at {x}, {z} in {dimension} (radius {radius} chunks)";
    public String completeMessage = "§aChunky Pregenerator: §fGeneration complete in {dimension}";

    // ─────────────────────────────────────────────────────────────────────────

    private static ChunkyPregenConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
                ChunkyPregenConfig cfg = GSON.fromJson(reader, ChunkyPregenConfig.class);
                if (cfg != null) return cfg;
            } catch (IOException e) {
                LOGGER.error("[ChunkyPregen] Failed to load config, using defaults", e);
            }
        }
        ChunkyPregenConfig cfg = new ChunkyPregenConfig();
        cfg.save();
        return cfg;
    }

    public void reload() {
        if (!Files.exists(CONFIG_PATH)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            ChunkyPregenConfig loaded = GSON.fromJson(reader, ChunkyPregenConfig.class);
            if (loaded == null) return;
            this.enabled                    = loaded.enabled;
            this.debugMode                  = loaded.debugMode;
            this.triggerDistance            = loaded.triggerDistance;
            this.generationRadius           = loaded.generationRadius;
            this.checkIntervalTicks         = loaded.checkIntervalTicks;
            this.overworldRadius            = loaded.overworldRadius;
            this.netherRadius               = loaded.netherRadius;
            this.endRadius                  = loaded.endRadius;
            this.enableOverworld            = loaded.enableOverworld;
            this.enableNether               = loaded.enableNether;
            this.enableEnd                  = loaded.enableEnd;
            this.netherAutoScale            = loaded.netherAutoScale;
            this.joinDelaySeconds           = loaded.joinDelaySeconds;
            this.chatNotifications          = loaded.chatNotifications;
            this.skipCreativePlayers        = loaded.skipCreativePlayers;
            this.skipSpectatorPlayers       = loaded.skipSpectatorPlayers;
            this.minPlayersToTrigger        = loaded.minPlayersToTrigger;
            this.requireEndVisit            = loaded.requireEndVisit;
            this.autoTpsPause               = loaded.autoTpsPause;
            this.tpsPauseThreshold          = loaded.tpsPauseThreshold;
            this.tpsResumeThreshold         = loaded.tpsResumeThreshold;
            this.maxConcurrentDimensions    = loaded.maxConcurrentDimensions;
            this.chunkyThreads              = loaded.chunkyThreads;
            this.voxyIntegration            = loaded.voxyIntegration;
            this.voxyRenderDistance         = loaded.voxyRenderDistance;
            this.voxyGenRadiusMultiplier    = loaded.voxyGenRadiusMultiplier;
            this.voxyTriggerMultiplier      = loaded.voxyTriggerMultiplier;
            this.spiralGeneration           = loaded.spiralGeneration;
            this.generationRingStep         = loaded.generationRingStep;
            this.shape                      = loaded.shape;
            this.progressRelay              = loaded.progressRelay;
            this.progressRelayIntervalMinutes = loaded.progressRelayIntervalMinutes;
            this.startMessage               = loaded.startMessage;
            this.completeMessage            = loaded.completeMessage;
        } catch (IOException e) {
            LOGGER.error("[ChunkyPregen] Failed to reload config", e);
        }
    }

    public void save() {
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            LOGGER.error("[ChunkyPregen] Failed to save config", e);
        }
    }
}
