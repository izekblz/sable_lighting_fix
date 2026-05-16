package dev.ryanhcode.sable.mixin.plot.lighting;

import dev.ryanhcode.sable.render.light_bridge.VirtualLightManager;
import net.minecraft.world.level.lighting.BlockLightSectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overlays virtual block light from sub-level emitters on top of the world's stored block light.
 */
@Mixin(BlockLightSectionStorage.class)
public abstract class BlockLightEngineMixin {

    @Inject(method = "getLightValue", at = @At("RETURN"), cancellable = true)
    private void sable$overlayVirtualLight(final long packedPos, final CallbackInfoReturnable<Integer> cir) {
        final VirtualLightManager manager = VirtualLightManager.get();

        // Bail when the manager itself is asking the world for light, or when it has nothing to add.
        if (manager.isSampling() || !manager.hasAnyLights()) {
            return;
        }

        final int virtual = manager.getVirtualLight(packedPos);
        if (virtual > cir.getReturnValueI()) {
            cir.setReturnValue(virtual);
        }
    }
}
