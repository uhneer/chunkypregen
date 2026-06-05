package dev.chunkypregen.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.chunkypregen.config.ChunkyPregenConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional integration with the Voxy LOD renderer.
 *
 * Confirmed: Voxy 0.2.16-beta+1.21.11
 *   Class:   me.cortex.voxy.client.config.VoxyConfig
 *   Static:  VoxyConfig.CONFIG  (singleton, null until Voxy loads)
 *   Field:   float sectionRenderDistance  (default 16.0f, user-configurable)
 *   JSON:    config/voxy-config.json → "section_render_distance": 64.0
 *
 * Primary path: reflection on VoxyConfig.CONFIG.sectionRenderDistance — live runtime value.
 * Fallback:     read config/voxy-config.json from disk — used if reflection fails.
 * Results are cached for 30 s to avoid repeated overhead.
 */
public final class VoxyIntegration {

    private static final Logger LOGGER = LoggerFactory.getLogger("chunkypregen/voxy");

    public static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("voxy");

    private static final long CACHE_TTL_MS = 30_000L;
    private static int  cachedRadius = -1;
    private static long cacheExpiry  = 0L;

    // Reflection handles, resolved once
    private static boolean reflectionResolved = false;
    private static Field   configSingletonField = null;   // VoxyConfig.CONFIG  (static)
    private static Field   renderDistanceField  = null;   // VoxyConfig.sectionRenderDistance

    // Config file fallback
    private static final String[] CONFIG_CANDIDATES = {
            "voxy-config.json",
            "voxy-config.json5",
            "voxy/voxy-config.json",
            "voxy.json",
            "voxy.json5",
    };
    // Fields measured in Voxy sections (multiply by CHUNKS_PER_SECTION to get chunks)
    private static final java.util.Set<String> SECTION_FIELDS = java.util.Set.of(
            "section_render_distance",   // Voxy 0.2.16-beta (confirmed)
            "sectionRenderDistance"
    );

    // Fields already measured in chunks (no multiplication needed)
    private static final java.util.Set<String> CHUNK_FIELDS = java.util.Set.of(
            "renderDistance",
            "render_distance",
            "renderDistanceChunks",
            "lodRenderDistance"
    );

    // Combined ordered list for iteration
    private static final String[] DISTANCE_FIELDS = {
            "section_render_distance",  // Voxy 0.2.16-beta (confirmed)
            "sectionRenderDistance",
            "renderDistance",
            "render_distance",
            "renderDistanceChunks",
            "lodRenderDistance",
    };

    // Voxy stores render distance in "sections". Confirmed for 0.2.16-beta+1.21.11:
    // section_render_distance=64 → user sees 2048 chunks → 1 section = 32 chunks.
    private static final int CHUNKS_PER_SECTION = 32;

    private VoxyIntegration() {}

    /**
     * Returns the Voxy LOD render distance in chunks, or -1 if unavailable.
     * Primary: reads VoxyConfig.CONFIG.sectionRenderDistance via reflection (live value).
     * Fallback: reads voxy-config.json from disk (only when called from a context where
     *   I/O is safe — i.e. not the render thread). When called from the render thread
     *   (e.g. Sodium UI value getter) this method returns only the cached value and skips
     *   file I/O to avoid stalling the render thread.
     * sectionRenderDistance is in Voxy sections (1 section = 32 chunks); result is converted.
     * Result cached for 30 s.
     */
    public static int getRenderDistanceChunks() {
        if (!PRESENT || !ChunkyPregenConfig.INSTANCE.voxyIntegration) return -1;
        long now = System.currentTimeMillis();
        if (now < cacheExpiry) return cachedRadius;

        // Detect render thread: Thread.currentThread().getName() starts with "Render thread"
        // on Minecraft's client render thread. Skip file I/O there to avoid stalling rendering.
        boolean isRenderThread = Thread.currentThread().getName().startsWith("Render thread");

        int detected = tryReflection(); // reflection is always safe (in-memory read)
        if (detected <= 0 && !isRenderThread) detected = tryConfigFile(); // file I/O only off render thread

        if (detected > 0) {
            if (detected != cachedRadius)
                LOGGER.info("[ChunkyPregen] Voxy render distance: {} chunks ({} sections × {})",
                        detected, detected / CHUNKS_PER_SECTION, CHUNKS_PER_SECTION);
            cachedRadius = detected;
            cacheExpiry = now + CACHE_TTL_MS;
        } else if (!isRenderThread) {
            // Only mark cache miss / warn when we actually tried all paths
            cachedRadius = -1;
            cacheExpiry = now + CACHE_TTL_MS;
            LOGGER.warn("[ChunkyPregen] Could not read Voxy render distance — integration inactive");
        }
        // If on render thread and cache expired, return the stale cached value without updating expiry
        return cachedRadius;
    }

    public static void invalidateCache() { cacheExpiry = 0; }

    // ── Reflection (live VoxyConfig singleton) ────────────────────────────────

    private static int tryReflection() {
        resolveReflectionFields();
        if (configSingletonField == null || renderDistanceField == null) return -1;
        try {
            Object instance = configSingletonField.get(null);
            if (instance == null) return -1; // Voxy not yet initialised
            float v = (float) renderDistanceField.get(instance);
            return v > 0 ? Math.round(v * CHUNKS_PER_SECTION) : -1;
        } catch (Exception e) {
            LOGGER.debug("[ChunkyPregen] VoxyConfig reflection read failed: {}", e.getMessage());
            return -1;
        }
    }

    private static void resolveReflectionFields() {
        if (reflectionResolved) return;
        reflectionResolved = true;
        try {
            Class<?> cls = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            configSingletonField = cls.getField("CONFIG");
            renderDistanceField  = cls.getField("sectionRenderDistance");
            configSingletonField.setAccessible(true);
            renderDistanceField.setAccessible(true);
            LOGGER.info("[ChunkyPregen] Resolved VoxyConfig reflection fields");
        } catch (Exception e) {
            LOGGER.warn("[ChunkyPregen] Could not resolve VoxyConfig via reflection: {}", e.getMessage());
        }
    }

    // ── Config file fallback ──────────────────────────────────────────────────

    private static int tryConfigFile() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        for (String candidate : CONFIG_CANDIDATES) {
            Path path = configDir.resolve(candidate);
            if (!Files.exists(path)) continue;
            try {
                String content = Files.readString(path)
                        .replaceAll("//[^\n\r]*", "")
                        .replaceAll("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "");
                JsonObject root = JsonParser.parseString(content).getAsJsonObject();
                int v = findField(root, candidate);
                if (v > 0) return v;
                for (String key : root.keySet()) {
                    JsonElement el = root.get(key);
                    if (el.isJsonObject()) {
                        v = findField(el.getAsJsonObject(), candidate + "/" + key);
                        if (v > 0) return v;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[ChunkyPregen] Could not parse Voxy config '{}': {}", candidate, e.getMessage());
            }
        }
        return -1;
    }

    private static int findField(JsonObject obj, String source) {
        for (String field : DISTANCE_FIELDS) {
            if (!obj.has(field)) continue;
            try {
                float raw = obj.get(field).getAsFloat();
                if (raw <= 0) continue;
                int val;
                if (SECTION_FIELDS.contains(field)) {
                    // Field is in Voxy sections — convert to chunks
                    val = Math.round(raw * CHUNKS_PER_SECTION);
                    LOGGER.debug("[ChunkyPregen] Voxy config '{}' → '{}' = {} sections × {} = {} chunks",
                            source, field, raw, CHUNKS_PER_SECTION, val);
                } else {
                    // Field is already in chunks — use directly
                    val = Math.round(raw);
                    LOGGER.debug("[ChunkyPregen] Voxy config '{}' → '{}' = {} chunks (chunk-unit field, no scaling)",
                            source, field, val);
                }
                if (val > 0) return val;
            } catch (Exception ignored) {}
        }
        return -1;
    }
}
