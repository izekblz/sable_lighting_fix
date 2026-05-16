package dev.ryanhcode.sable.mixin.plot.lighting.sodium;

import dev.ryanhcode.sable.render.light_bridge.VirtualLightManager;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium counterpart of {@link dev.ryanhcode.sable.mixin.plot.lighting.BlockLightEngineMixin}:
 * blends virtual block light into Sodium's per-section brightness lookups.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin {

    @Inject(method = "getBrightness", at = @At("RETURN"), cancellable = true, remap = true)
    private void sable$overlayVirtualLight(final LightLayer layer, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
        if (layer != LightLayer.BLOCK) {
            return;
        }

        final VirtualLightManager manager = VirtualLightManager.get();
        if (!manager.hasAnyLights()) {
            return;
        }

        final int virtual = manager.getVirtualLight(pos);
        if (virtual > cir.getReturnValueI()) {
            cir.setReturnValue(virtual);
        }
    }
}
