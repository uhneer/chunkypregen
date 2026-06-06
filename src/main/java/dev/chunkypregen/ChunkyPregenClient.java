package dev.chunkypregen;

import dev.chunkypregen.monitor.HudData;
import net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side initializer.
 *
 * Handles:
 *  - Keybinding to open Sodium settings (unbound by default)
 *  - Render-distance cycle triggered every time a new generation bundle starts,
 *    forcing Voxy + Sodium to re-initialize their LOD section boundary and
 *    eliminate the "thin air" gap at the transition zone.
 *
 * Cycle sequence (2-phase, no flush):
 *   t=0         → wait 1s (let bundle settle)
 *   t+1s (20t)  → set RD to 32 (force Sodium + Voxy rebuild — lag ~3-5s, no transparency flash)
 *   t+11s(200t) → set RD to 3  (settle)
 *
 * The old 3-phase design dropped RD to 2 first (flush) which caused the entire world
 * to go transparent for half a second as Sodium unloaded all distant sections.
 * That step is not needed — Voxy's worker threads restart on any significant RD change,
 * so jumping directly to 32 is sufficient to trigger the LOD rebuild. The max RD is
 * also reduced from 85 to 32 (7× fewer sections to compile → lag drops from ~20s to ~3-5s).
 *
 * A 90-second cooldown prevents the nether bundle start (~52s after the overworld bundle)
 * from triggering a second back-to-back cycle.
 */
@Environment(EnvType.CLIENT)
public class ChunkyPregenClient implements ClientModInitializer {

    public static KeyBinding openSettingsKey;

    // Render-distance cycle state — volatile because triggerRdCycle() is called
    // from the server thread while the tick handler reads on the render thread.
    private static volatile int  rdCyclePhase          = -1;  // -1 = idle
    private static volatile int  rdCycleCountdown      =  0;
    private static volatile long lastRdCycleTriggerMs  =  0L;
    private static final    long RD_CYCLE_COOLDOWN_MS  = 90_000L;

    private static final int RD_MAX              = 32; // force-rebuild RD (no flush step)
    private static final int RD_RESTORE          =  3; // settle RD
    private static final int DELAY_BEFORE_EXPAND = 20; // 1s — brief settle before jumping to RD_MAX
    // DELAY_AT_MAX is read from config (lodRefreshSeconds * 20) at cycle trigger time

    /**
     * Called by GenerationTracker (server thread) whenever a new bundle starts. Thread-safe.
     * Starts the 2-phase render-distance cycle: current → 32 → 3 chunks.
     */
    public static void triggerRdCycle() {
        long now = System.currentTimeMillis();
        if (rdCyclePhase >= 0) {
            ChunkyPregen.LOGGER.info("[ChunkyPregen] RD cycle trigger ignored — cycle already in phase {}.", rdCyclePhase);
            return;
        }
        long elapsed = now - lastRdCycleTriggerMs;
        if (lastRdCycleTriggerMs != 0L && elapsed < RD_CYCLE_COOLDOWN_MS) {
            ChunkyPregen.LOGGER.info("[ChunkyPregen] RD cycle suppressed — triggered too recently ({}s ago, cooldown={}s). " +
                    "Skipping duplicate.", elapsed / 1000, RD_CYCLE_COOLDOWN_MS / 1000);
            return;
        }
        int delayAtMax = dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.lodRefreshSeconds * 20;
        ChunkyPregen.LOGGER.info("[ChunkyPregen] RD cycle triggered by generation bundle start. " +
                "Sequence: RD={}→{} chunks over ~{}s total (no flush — no transparency flash).",
                RD_MAX, RD_RESTORE, (DELAY_BEFORE_EXPAND + delayAtMax) / 20);
        lastRdCycleTriggerMs = now;
        rdCyclePhase         = 0;
        rdCycleCountdown     = DELAY_BEFORE_EXPAND;
    }

    @Override
    public void onInitializeClient() {
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkypregen.open_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyBinding.Category.create(Identifier.of("chunkypregen", "category"))
        ));

        ChunkyPregen.onGenerationBundleStart = ChunkyPregenClient::triggerRdCycle;

        registerHud();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Open-settings keybind
            while (openSettingsKey.wasPressed()) {
                if (client.currentScreen == null) {
                    try {
                        client.setScreen(VideoSettingsScreen.createScreen(null));
                    } catch (Exception e) {
                        ChunkyPregen.LOGGER.warn("[ChunkyPregen] Could not open Sodium settings screen", e);
                    }
                }
            }

            // Render-distance cycle
            if (rdCyclePhase < 0 || client.world == null) return;
            if (--rdCycleCountdown > 0) return;

            switch (rdCyclePhase) {
                case 0 -> {
                    // Phase 1 — expand: jump to RD_MAX to force Sodium + Voxy to rebuild LOD sections.
                    // No flush to RD=2 first, so there is no transparency flash.
                    // Lag of ~3-5s is expected while Sodium compiles the new sections.
                    int rdBefore = client.options.getViewDistance().getValue();
                    client.options.getViewDistance().setValue(RD_MAX);
                    client.options.write();
                    int rdAfter  = client.options.getViewDistance().getValue();
                    int holdSecs = dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.lodRefreshSeconds;
                    ChunkyPregen.LOGGER.info("[ChunkyPregen] RD cycle phase 1/2 — expand. " +
                            "RD: {} → {} (expected {}). Holding for {}s.",
                            rdBefore, rdAfter, RD_MAX, holdSecs);
                    if (rdAfter != RD_MAX)
                        ChunkyPregen.LOGGER.warn("[ChunkyPregen] RD expand MISMATCH: set {} but read back {}. " +
                                "Sodium may have clamped the value.", RD_MAX, rdAfter);
                    if (client.player != null && dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.debugMode)
                        client.player.sendMessage(
                            Text.literal("§7[ChunkyPregen] §fLOD refresh (1/2): rebuilding LOD (RD=" + rdAfter + ")… §7[brief lag normal]"), false);
                    rdCyclePhase     = 1;
                    rdCycleCountdown = dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.lodRefreshSeconds * 20;
                }
                case 1 -> {
                    // Phase 2 — settle: return to normal gameplay render distance.
                    int rdBefore = client.options.getViewDistance().getValue();
                    client.options.getViewDistance().setValue(RD_RESTORE);
                    client.options.write();
                    int rdAfter  = client.options.getViewDistance().getValue();
                    ChunkyPregen.LOGGER.info("[ChunkyPregen] RD cycle phase 2/2 — settle. " +
                            "RD: {} → {} (expected {}). Cycle complete.",
                            rdBefore, rdAfter, RD_RESTORE);
                    if (rdAfter != RD_RESTORE)
                        ChunkyPregen.LOGGER.warn("[ChunkyPregen] RD restore MISMATCH: set {} but read back {}.",
                                RD_RESTORE, rdAfter);
                    if (client.player != null && dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.debugMode)
                        client.player.sendMessage(
                            Text.literal("§a[ChunkyPregen] §fLOD refresh (2/2): done — render distance set to " + rdAfter + " chunks."), false);
                    rdCyclePhase = -1;
                }
            }
        });
    }

    // ── HUD widget ────────────────────────────────────────────────────────────

    // Base geometry (unscaled, at hudScale = 1.0)
    private static final float BASE_WIDGET_R   = 35f;
    private static final float BASE_RING_OUTER = 35f;
    private static final float BASE_RING_INNER = 25f; // must equal BASE_PROX_R
    private static final float BASE_PROX_R     = 25f;
    private static final float BASE_DEAD_R     = 15f;
    private static final int   PAD             = 6;

    // Scaled geometry — rebuilt whenever hudScale changes
    private static float scaledWidgetR   = BASE_WIDGET_R;
    private static float scaledRingOuter = BASE_RING_OUTER;
    private static float scaledRingInner = BASE_RING_INNER;
    private static float scaledProxR     = BASE_PROX_R;
    private static float scaledDeadR     = BASE_DEAD_R;
    private static float lastBuiltScale  = -1f; // -1 forces first build

    // Colours
    private static final int COL_BG      = 0xCC080808;
    private static final int COL_RING_BG = 0xFF0D150D;
    private static final int COL_INNER   = 0xBB0A0A0A;
    private static final int COL_DEAD    = 0xFF1A4520;
    private static final int COL_TOP     = 0xFF4488FF; // blue — current ring %
    private static final int COL_BOT     = 0xFF1A5020; // dark green — batch %
    private static final int COL_FLASH   = 0xFF556655;
    private static final int COL_CENTER  = 0xFF00FF44;

    // Pre-computed geometry caches — built once, reused every frame
    // Span format: int[] of (dx1, dy, dx2) triples — one fill() call per span
    private static int[] bgSpans      = null; // background filled circle
    private static int[] ringBgSpans  = null; // full ring background
    private static int[] innerSpans   = null; // inner proximity circle
    private static int[] deadzoneSpans = null; // thin deadzone ring

    // Arc pixel caches — rebuilt only when progress values change meaningfully
    // Pixel format: int[] of (dx, dy) pairs
    private static int[] topArcPx    = null;
    private static int[] botArcPx    = null;
    private static float cachedTopPct = -1f;
    private static float cachedBotPct = -1f;

    private static void rebuildGeometry(float scale) {
        scaledWidgetR   = BASE_WIDGET_R   * scale;
        scaledRingOuter = BASE_RING_OUTER * scale;
        scaledRingInner = BASE_RING_INNER * scale;
        scaledProxR     = BASE_PROX_R     * scale;
        scaledDeadR     = BASE_DEAD_R     * scale;
        bgSpans       = buildCircleSpans(scaledWidgetR);
        ringBgSpans   = buildRingSpans(scaledRingInner, scaledRingOuter);
        innerSpans    = buildCircleSpans(scaledProxR);
        deadzoneSpans = buildRingSpans(scaledDeadR - 1.5f * scale, scaledDeadR + 1.5f * scale);
        // Invalidate arc caches so they rebuild at new scale
        topArcPx = null; cachedTopPct = -1f;
        botArcPx = null; cachedBotPct = -1f;
        lastBuiltScale = scale;
    }

    private static void registerHud() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) return;

            // Rebuild geometry if scale changed (rare — only when user moves the slider)
            float scale = dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.hudScale;
            if (scale != lastBuiltScale) rebuildGeometry(scale);

            // Hide on F1, F3, ESC / any open screen
            if (client.options.hudHidden) return;
            if (client.currentScreen != null) return;
            try {
                if (client.getDebugHud().shouldShowDebugHud()) return;
            } catch (Exception ignored) {}

            boolean active   = HudData.active;
            long    flashMs  = HudData.completionFlashMs;
            long    now      = System.currentTimeMillis();
            boolean flashing = flashMs > 0 && (now - flashMs) < HudData.COMPLETION_FLASH_MS;
            boolean hasCenter = HudData.triggerDistanceBlocks > 0;

            if (!active && !flashing && !hasCenter) return;

            int screenW = client.getWindow().getScaledWidth();
            int screenH = client.getWindow().getScaledHeight();
            int boxSize = (int)(scaledWidgetR * 2) + 2;

            dev.chunkypregen.config.HudPosition pos =
                    dev.chunkypregen.config.ChunkyPregenConfig.INSTANCE.hudPosition;
            int wx, wy;
            switch (pos) {
                case TOP_RIGHT    -> { wx = screenW - boxSize - PAD; wy = PAD; }
                case TOP_CENTER   -> { wx = (screenW - boxSize) / 2;  wy = PAD; }
                case BOTTOM_LEFT  -> { wx = PAD; wy = screenH - boxSize - PAD; }
                case BOTTOM_RIGHT -> { wx = screenW - boxSize - PAD; wy = screenH - boxSize - PAD; }
                default           -> { wx = PAD; wy = PAD; }
            }

            int cx = wx + (int) scaledWidgetR + 1;
            int cy = wy + (int) scaledWidgetR + 1;

            // 1. Background circle (pre-computed, ~71 fill calls)
            drawSpans(drawContext, cx, cy, bgSpans, COL_BG);

            // 2. Ring background (pre-computed, ~142 fill calls)
            drawSpans(drawContext, cx, cy, ringBgSpans, COL_RING_BG);

            if (flashing) {
                float alpha = 1f - (float)(now - flashMs) / HudData.COMPLETION_FLASH_MS;
                int a = Math.max(0, Math.min(200, (int)(alpha * 200)));
                drawSpans(drawContext, cx, cy, ringBgSpans, (a << 24) | (COL_FLASH & 0xFFFFFF));
            } else if (active) {
                // TOP half: current ring % — blue, arc symmetric around 12 o'clock
                float ringPct = Math.min(1f, HudData.currentRingPct / 100f);
                if (Math.abs(ringPct - cachedTopPct) > 0.005f) {
                    topArcPx    = buildArcPixels(scaledRingInner, scaledRingOuter, -HALF_PI, ringPct * HALF_PI);
                    cachedTopPct = ringPct;
                }
                if (topArcPx != null) drawPixels(drawContext, cx, cy, topArcPx, COL_TOP);

                // BOTTOM half: batch progress — dark green, arc symmetric around 6 o'clock
                float batchPct = Math.min(1f, HudData.completedFraction() + HudData.currentRingPartialFraction());
                if (Math.abs(batchPct - cachedBotPct) > 0.005f) {
                    botArcPx    = buildArcPixels(scaledRingInner, scaledRingOuter, HALF_PI, batchPct * HALF_PI);
                    cachedBotPct = batchPct;
                }
                if (botArcPx != null) drawPixels(drawContext, cx, cy, botArcPx, COL_BOT);
            }

            // 3. Inner proximity circle (pre-computed)
            if (hasCenter) {
                drawSpans(drawContext, cx, cy, innerSpans, COL_INNER);
                drawSpans(drawContext, cx, cy, deadzoneSpans, COL_DEAD);
                drawContext.fill(cx, cy, cx + 1, cy + 1, COL_CENTER);

                // Player dot — single pixel, computed every frame (trivial)
                double pdx  = client.player.getX() - HudData.lastCenterX;
                double pdz  = client.player.getZ() - HudData.lastCenterZ;
                double dist = Math.sqrt(pdx * pdx + pdz * pdz);
                int    trig = Math.max(1, HudData.triggerDistanceBlocks);
                double scale2 = scaledDeadR / trig;
                double sx = pdx * scale2, sz = pdz * scale2;
                double len = Math.sqrt(sx * sx + sz * sz);
                if (len > scaledDeadR + 4) { double f = (scaledDeadR + 4) / len; sx *= f; sz *= f; }
                int dotX = (int) Math.round(cx + sx);
                int dotY = (int) Math.round(cy + sz);
                float prox = (float)(dist / trig);
                int dotCol = prox < 0.6f ? 0xFFFFFFFF : prox < 0.85f ? 0xFFFFCC00 : 0xFFFF4400;
                drawContext.fill(dotX, dotY, dotX + 1, dotY + 1, dotCol);
            }
        });
    }

    // ── Geometry builders (called once at startup) ────────────────────────────

    private static final float HALF_PI = (float)(Math.PI / 2);
    private static final float PI_F    = (float) Math.PI;
    private static final float TWO_PI  = PI_F * 2f;

    /**
     * Builds horizontal span data for a filled circle of radius r.
     * Format: int[] of (dx1, dy, dx2) triples — one fill(cx+dx1, cy+dy, cx+dx2, cy+dy+1) per triple.
     * Reduces ~3800 fill() calls to ~71 (one per row).
     */
    /** Symmetric horizontal spans for a filled circle centred at origin. */
    private static int[] buildCircleSpans(float r) {
        int bounds = (int) Math.ceil(r);
        int[] buf = new int[(bounds * 2 + 1) * 3];
        int n = 0;
        for (int dy = -bounds; dy <= bounds; dy++) {
            float hw = (float) Math.sqrt(Math.max(0f, r * r - dy * dy));
            int halfW = Math.round(hw);
            if (halfW > 0) { buf[n++] = -halfW; buf[n++] = dy; buf[n++] = halfW; }
        }
        int[] result = new int[n];
        System.arraycopy(buf, 0, result, 0, n);
        return result;
    }

    /** Symmetric horizontal spans for a ring (annulus) centred at origin. */
    private static int[] buildRingSpans(float innerR, float outerR) {
        int bounds = (int) Math.ceil(outerR);
        int[] buf = new int[(bounds * 2 + 1) * 6];
        int n = 0;
        for (int dy = -bounds; dy <= bounds; dy++) {
            float outerHw = (float) Math.sqrt(Math.max(0f, outerR * outerR - dy * dy));
            if (outerHw <= 0f) continue;
            int oH = Math.round(outerHw);
            if (Math.abs(dy) >= innerR) {
                buf[n++] = -oH; buf[n++] = dy; buf[n++] = oH;
            } else {
                float innerHw = (float) Math.sqrt(Math.max(0f, innerR * innerR - dy * dy));
                int iH = Math.round(innerHw);
                if (oH > iH) {
                    buf[n++] = -oH; buf[n++] = dy; buf[n++] = -iH; // left span
                    buf[n++] =  iH; buf[n++] = dy; buf[n++] =  oH; // right span
                }
            }
        }
        int[] result = new int[n];
        System.arraycopy(buf, 0, result, 0, n);
        return result;
    }

    /**
     * Builds a pixel list for an arc sector of a ring.
     * arcCenter: standard angle (0=right, π/2=down). halfSpan: arc half-width.
     * Arc grows symmetrically from arcCenter ± halfSpan.
     * Rebuilt only when progress changes — stored as (dx, dy) pairs.
     */
    private static int[] buildArcPixels(float innerR, float outerR,
                                         float arcCenter, float halfSpan) {
        if (halfSpan <= 0f) return new int[0];
        int bounds = (int) Math.ceil(outerR) + 1;
        int[] buf = new int[bounds * bounds * 2];
        int n = 0;
        for (int dy = -bounds; dy <= bounds; dy++) {
            for (int dx = -bounds; dx <= bounds; dx++) {
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < innerR - 0.5f || dist > outerR + 0.5f) continue;
                float angle = (float) Math.atan2(dy, dx);
                float diff = angle - arcCenter;
                while (diff >  PI_F) diff -= TWO_PI;
                while (diff < -PI_F) diff += TWO_PI;
                if (Math.abs(diff) > halfSpan) continue;
                buf[n++] = dx; buf[n++] = dy;
            }
        }
        int[] result = new int[n];
        System.arraycopy(buf, 0, result, 0, n);
        return result;
    }

    // ── Render helpers ────────────────────────────────────────────────────────

    /** Renders pre-computed span data with a single colour. O(spans) fill calls. */
    private static void drawSpans(DrawContext ctx, int cx, int cy, int[] spans, int color) {
        for (int i = 0; i < spans.length; i += 3)
            ctx.fill(cx + spans[i], cy + spans[i+1], cx + spans[i+2], cy + spans[i+1] + 1, color);
    }

    /** Renders pre-computed pixel list with a single colour. O(pixels) fill calls. */
    private static void drawPixels(DrawContext ctx, int cx, int cy, int[] pixels, int color) {
        for (int i = 0; i < pixels.length; i += 2)
            ctx.fill(cx + pixels[i], cy + pixels[i+1], cx + pixels[i] + 1, cy + pixels[i+1] + 1, color);
    }
}
