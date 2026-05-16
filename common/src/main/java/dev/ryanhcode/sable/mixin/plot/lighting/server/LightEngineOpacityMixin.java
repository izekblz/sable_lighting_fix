package dev.ryanhcode.sable.mixin.plot.lighting.server;

import dev.ryanhcode.sable.render.light_bridge.ServerSubLevelLightInjector;
import dev.ryanhcode.sable.render.light_bridge.ServerSubLevelWorldInjector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class LightEngineOpacityMixin {

    @Inject(method = "getOpacity", at = @At("HEAD"), cancellable = true)
    private void sable$blockLightAtOpaquePositions(final BlockState state, final BlockPos pos, final CallbackInfoReturnable<Integer> cir) {
        final long packed = pos.asLong();
        if (ServerSubLevelWorldInjector.isOpaqueAt(packed)) {
            cir.setReturnValue(16);
            return;
        }
        if (ServerSubLevelLightInjector.isWorldOpaqueInPlot(packed)) {
            cir.setReturnValue(16);
        }
    }

    @Inject(method = "shapeOccludes", at = @At("HEAD"), cancellable = true)
    private void sable$shapeOccludesSubLevel(final long sourcePos, final BlockState sourceState, final long targetPos, final BlockState targetState, final Direction direction, final CallbackInfoReturnable<Boolean> cir) {
        final byte targetMask = ServerSubLevelWorldInjector.getShapeOcclusion(targetPos);
        if (targetMask != 0 && (targetMask & (1 << direction.getOpposite().ordinal())) != 0) {
            cir.setReturnValue(true);
            return;
        }
        final byte sourceMask = ServerSubLevelWorldInjector.getShapeOcclusion(sourcePos);
        if (sourceMask != 0 && (sourceMask & (1 << direction.ordinal())) != 0) {
            cir.setReturnValue(true);
        }
    }
}
