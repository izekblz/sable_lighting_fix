package dev.ryanhcode.sable.mixin.plot.lighting.server;

import dev.ryanhcode.sable.render.light_bridge.ServerSubLevelLightInjector;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards server-side block-light updates to the {@link ServerSubLevelLightInjector} so plots
 * can be re-scanned and reinjected if the change occurred near a sub-level.
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheLightMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void sable$forwardBlockLightUpdate(final LightLayer layer, final SectionPos pos, final CallbackInfo ci) {
        if (layer != LightLayer.BLOCK) {
            return;
        }

        ServerSubLevelLightInjector.onServerLightUpdate(this.level, pos.getX(), pos.getY(), pos.getZ());
    }
}
