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

    // ── HUD progress bar ──────────────────────────────────────────────────────

    /**
     * Registers a thin generation progress bar at the top of the screen.
     *
     * Visual layers (left to right):
     *   1. Near-black background — full bar width
     *   2. Muted dark green     — fraction of bundle already completed (completed rings)
     *   3. Neon green           — current ring's progress within the bundle fraction
     *   4. Grey flash           — briefly shown at 100% when bundle completes, then fades
     *
     * The bar is hidden when no generation is active and no recent completion flash.
     */
    private static void registerHud() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null) return;

            boolean active      = HudData.active;
            long    flashMs     = HudData.completionFlashMs;
            long    now         = System.currentTimeMillis();
            boolean flashing    = flashMs > 0 && (now - flashMs) < HudData.COMPLETION_FLASH_MS;

            if (!active && !flashing) return;

            int screenW  = client.getWindow().getScaledWidth();
            int barH     = 3;
            int barY     = 0;

            // Background
            drawContext.fill(0, barY, screenW, barY + barH, 0xFF0A0A0A);

            if (flashing) {
                // Full grey bar — completion pulse
                float alpha = 1f - (float)(now - flashMs) / HudData.COMPLETION_FLASH_MS;
                int a = Math.max(0, Math.min(255, (int)(alpha * 255)));
                int grey = (a << 24) | 0x555555;
                drawContext.fill(0, barY, screenW, barY + barH, grey);
                return;
            }

            // Dark green = completed rings
            float completedF = HudData.completedFraction();
            int completedPx  = (int)(screenW * completedF);
            if (completedPx > 0)
                drawContext.fill(0, barY, completedPx, barY + barH, 0xFF1A4520);

            // Neon green = current ring partial progress
            float partialF   = HudData.currentRingPartialFraction();
            int partialPx    = (int)(screenW * partialF);
            if (partialPx > 0)
                drawContext.fill(completedPx, barY, completedPx + partialPx, barY + barH, 0xFF39FF14);
        });
    }
}
