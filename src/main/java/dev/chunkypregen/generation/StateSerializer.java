package dev.chunkypregen.generation;

import com.google.gson.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes/deserializes tracker state to {@code <world>/data/chunkypregen_state.json}.
 * Also manages two flag files in the same directory:
 *   - chunkypregen_initialized  — marks a world as having been pre-generated at least once
 *   - (endVisited stored inside chunkypregen_state.json as a top-level boolean)
 *
 * JSON schema (v3):
 * {
 *   "lastCenters":      { "minecraft:overworld": { "x": 0, "z": 0 }, ... },
 *   "endVisited":       false,
 *   "inProgressBatches": {
 *     "minecraft:overworld": {
 *       "rings":     [6, 24, 53, ...],
 *       "ringIndex": 4,
 *       "centerX":   -198,
 *       "centerZ":   206
 *     }
 *   }
 * }
 *
 * Backwards compatible: v1/v2 files load fine; missing fields default to empty/false.
 */
public class StateSerializer {

    private static final Logger LOGGER     = LoggerFactory.getLogger("chunkypregen/state");
    private static final Gson   GSON       = new GsonBuilder().setPrettyPrinting().create();
    private static final String STATE_FILE = "chunkypregen_state.json";
    private static final String INIT_FLAG  = "chunkypregen_initialized";

    // ── Paths ──────────────────────────────────────────────────────────────────

    private static Path dataDir(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).resolve("data");
    }

    private static Path statePath(MinecraftServer server)    { return dataDir(server).resolve(STATE_FILE); }
    private static Path initFlagPath(MinecraftServer server) { return dataDir(server).resolve(INIT_FLAG); }

    // ── World init flag ────────────────────────────────────────────────────────

    public static boolean isWorldInitialized(MinecraftServer server) {
        return Files.exists(initFlagPath(server));
    }

    public static void markWorldInitialized(MinecraftServer server) {
        Path path = initFlagPath(server);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "initialized");
        } catch (IOException e) {
            LOGGER.warn("[ChunkyPregen] Could not write world init flag: {}", e.getMessage());
        }
    }

    public static void clearWorldInitFlag(MinecraftServer server) {
        try {
            Files.deleteIfExists(initFlagPath(server));
        } catch (IOException e) {
            LOGGER.warn("[ChunkyPregen] Could not delete world init flag: {}", e.getMessage());
        }
    }

    // ── BatchState ─────────────────────────────────────────────────────────────

    /**
     * Snapshot of a spiral batch mid-flight.
     * rings:     full ring sequence computed at batch start (in chunks)
     * ringIndex: index of the ring that was actively running when the session ended
     * centerX/Z: block coordinates of the batch center
     */
    public record BatchState(List<Integer> rings, int ringIndex, int centerX, int centerZ) {}

    // ── State save/load ────────────────────────────────────────────────────────

    public static void save(MinecraftServer server,
                            Map<RegistryKey<World>, BlockPos> lastCenters,
                            boolean endVisited,
                            Map<RegistryKey<World>, BatchState> inProgressBatches) {
        Path path = statePath(server);
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();

            JsonObject centers = new JsonObject();
            for (Map.Entry<RegistryKey<World>, BlockPos> entry : lastCenters.entrySet()) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", entry.getValue().getX());
                pos.addProperty("z", entry.getValue().getZ());
                centers.add(entry.getKey().getValue().toString(), pos);
            }
            root.add("lastCenters", centers);
            root.addProperty("endVisited", endVisited);

            // Persist any in-progress spiral batches so they can be resumed on rejoin
            if (!inProgressBatches.isEmpty()) {
                JsonObject batches = new JsonObject();
                for (Map.Entry<RegistryKey<World>, BatchState> entry : inProgressBatches.entrySet()) {
                    BatchState bs = entry.getValue();
                    JsonObject b = new JsonObject();
                    JsonArray rings = new JsonArray();
                    for (int r : bs.rings()) rings.add(r);
                    b.add("rings", rings);
                    b.addProperty("ringIndex", bs.ringIndex());
                    b.addProperty("centerX",   bs.centerX());
                    b.addProperty("centerZ",   bs.centerZ());
                    batches.add(entry.getKey().getValue().toString(), b);
                }
                root.add("inProgressBatches", batches);
            }

            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("[ChunkyPregen] Could not save state: {}", e.getMessage());
        }
    }

    /** Result holder for state loaded from disk. */
    public record LoadResult(
            Map<RegistryKey<World>, BlockPos>    lastCenters,
            boolean                              endVisited,
            Map<RegistryKey<World>, BatchState>  inProgressBatches) {}

    public static LoadResult load(MinecraftServer server) {
        Map<RegistryKey<World>, BlockPos>   centers  = new HashMap<>();
        Map<RegistryKey<World>, BatchState> batches  = new HashMap<>();
        boolean endVisited = false;
        Path path = statePath(server);
        if (!Files.exists(path)) return new LoadResult(centers, false, batches);

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return new LoadResult(centers, false, batches);

            if (root.has("endVisited")) endVisited = root.get("endVisited").getAsBoolean();

            if (root.has("lastCenters")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("lastCenters").entrySet()) {
                    try {
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(entry.getKey()));
                        JsonObject pos = entry.getValue().getAsJsonObject();
                        centers.put(key, new BlockPos(pos.get("x").getAsInt(), 0, pos.get("z").getAsInt()));
                    } catch (Exception e) {
                        LOGGER.warn("[ChunkyPregen] Bad center entry '{}': {}", entry.getKey(), e.getMessage());
                    }
                }
            }

            if (root.has("inProgressBatches")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("inProgressBatches").entrySet()) {
                    try {
                        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(entry.getKey()));
                        JsonObject b = entry.getValue().getAsJsonObject();
                        List<Integer> rings = new ArrayList<>();
                        for (JsonElement e : b.getAsJsonArray("rings")) rings.add(e.getAsInt());
                        int ringIndex = b.get("ringIndex").getAsInt();
                        int cx        = b.get("centerX").getAsInt();
                        int cz        = b.get("centerZ").getAsInt();
                        batches.put(key, new BatchState(rings, ringIndex, cx, cz));
                        LOGGER.info("[ChunkyPregen] Loaded in-progress batch for {} — ring {}/{} at ({},{})",
                                entry.getKey(), ringIndex + 1, rings.size(), cx, cz);
                    } catch (Exception e) {
                        LOGGER.warn("[ChunkyPregen] Bad inProgressBatch entry '{}': {}", entry.getKey(), e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ChunkyPregen] Could not load state: {}", e.getMessage());
        }
        return new LoadResult(centers, endVisited, batches);
    }
}
