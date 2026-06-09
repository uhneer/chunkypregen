package dev.chunkypregen.mixin;

import dev.chunkypregen.config.ChunkyPregenConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Iterator;

/**
 * Suppresses Chunky's boss bar progress display when Progress Relay is enabled.
 *
 * Chunky sends generation progress as a server-side BossBarS2CPacket, which
 * bypasses ServerMessageEvents.ALLOW_GAME_MESSAGE entirely. This mixin redirects
 * the iterator used in BossBarHud.render() to exclude any boss bar whose display
 * name contains "[Chunky]" when the user has chosen to suppress Chunky output.
 *
 * The underlying data is untouched — the bar still exists in the map, no packets
 * are intercepted, and no state is mutated. It simply isn't drawn.
 */
@Environment(EnvType.CLIENT)
@Mixin(BossBarHud.class)
public class MixinBossBarHud {

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                     target = "Ljava/util/Collection;iterator()Ljava/util/Iterator;")
    )
    private Iterator<ClientBossBar> suppressChunkyBars(Collection<ClientBossBar> collection) {
        if (!ChunkyPregenConfig.INSTANCE.progressRelay) {
            return collection.iterator();
        }
        return collection.stream()
                .filter(bar -> !bar.getName().getString().contains("[Chunky]"))
                .iterator();
    }
}
