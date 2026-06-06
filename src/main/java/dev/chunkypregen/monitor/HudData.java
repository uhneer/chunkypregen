package dev.chunkypregen.monitor;

/**
 * Thread-safe bridge between GenerationTracker (server thread) and the HUD renderer
 * (render thread). All fields are volatile — reads/writes are individual assignments
 * on primitive types, so no lock is needed.
 *
 * Lifecycle:
 *   - active=true  when any dimension is RUNNING with a spiral batch
 *   - active=false when all dims are IDLE
 *   - On bundle complete: completionFlashMs is set so the HUD can show a brief
 *     full-bar flash before hiding
 */
public final class HudData {

    private HudData() {}

    /** Whether generation is currently active. */
    public static volatile boolean active            = false;

    /** 0-based index of the ring currently being generated. */
    public static volatile int     currentRingIndex  = 0;

    /** Total number of rings in this bundle. */
    public static volatile int     totalRings        = 0;

    /**
     * Percentage (0–100) through the current ring, parsed from Chunky's
     * per-second progress broadcast.
     */
    public static volatile float   currentRingPct    = 0f;

    /**
     * System.currentTimeMillis() when the last bundle completed.
     * The HUD shows a full grey bar for COMPLETION_FLASH_MS after this,
     * then hides. 0 = no recent completion.
     */
    public static volatile long    completionFlashMs = 0L;

    /** How long (ms) to show the grey completion bar after a bundle finishes. */
    public static final long COMPLETION_FLASH_MS = 2_000L;

    // ── Proximity indicator data ───────────────────────────────────────────────

    /** Last generation center X (block coordinate). */
    public static volatile double lastCenterX = 0;
    /** Last generation center Z (block coordinate). */
    public static volatile double lastCenterZ = 0;
    /** Current trigger deadzone in blocks. Updated when config changes or bundle fires. */
    public static volatile int    triggerDistanceBlocks = 2000;

    // ── Helpers called from server thread ─────────────────────────────────────

    public static void onBundleStart(int totalRings, double centerX, double centerZ) {
        HudData.active            = true;
        HudData.currentRingIndex  = 0;
        HudData.totalRings        = totalRings;
        HudData.currentRingPct    = 0f;
        HudData.completionFlashMs = 0L;
        HudData.lastCenterX       = centerX;
        HudData.lastCenterZ       = centerZ;
        HudData.triggerDistanceBlocks = dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.triggerDistance;
    }

    public static void onRingAdvance(int newRingIndex) {
        HudData.currentRingIndex = newRingIndex;
        HudData.currentRingPct   = 0f;
    }

    public static void onBundleComplete() {
        HudData.active            = false;
        HudData.currentRingPct    = 0f;
        HudData.completionFlashMs = System.currentTimeMillis();
    }

    public static void onBundleCancelled() {
        HudData.active            = false;
        HudData.currentRingPct    = 0f;
        HudData.completionFlashMs = 0L;
        HudData.totalRings        = 0;
    }

    // ── Helpers for the renderer (render thread reads) ─────────────────────────

    /** Fraction (0–1) of the bundle represented by fully completed rings. */
    public static float completedFraction() {
        int total = totalRings;
        if (total <= 0) return 0f;
        return Math.min(1f, (float) currentRingIndex / total);
    }

    /** Fraction (0–1) of the bundle represented by the current ring's partial progress. */
    public static float currentRingPartialFraction() {
        int total = totalRings;
        if (total <= 0) return 0f;
        return Math.min(1f / total, (currentRingPct / 100f) / total);
    }
}
