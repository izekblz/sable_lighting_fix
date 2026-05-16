package dev.ryanhcode.sable.render.light_bridge;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Vector3d;

/**
 * Glue between sub-levels and the {@link VirtualLightManager}.
 * <p>
 * For now there is only one piece of glue: walking a sub-level's plot chunks, picking out every
 * light-emitting block, and reporting their world-space positions. The result feeds straight into
 * the manager's spread step.
 */
public final class SubLevelLightBridge {

    private SubLevelLightBridge() {
    }

    /**
     * Walks the loaded chunks of {@code subLevel}'s plot, transforms every emitting block's position
     * into world space using the sub-level's logical pose, and returns a packed-pos → emission map.
     * <p>
     * If two emitters land on the same world block the higher emission wins.
     *
     * @param subLevel the sub-level whose emitters we are projecting
     * @return a fresh map keyed by packed world {@link BlockPos}; empty when the plot has no bounds
     *         or no emitting blocks
     */
    public static Long2IntOpenHashMap collectEmittingBlocks(final ClientSubLevel subLevel) {
        final Long2IntOpenHashMap result = new Long2IntOpenHashMap();
        result.defaultReturnValue(0);

        final LevelPlot plot = subLevel.getPlot();
        final BoundingBox3ic bounds = plot.getBoundingBox();
        if (bounds == null) {
            return result;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d worldPos = new Vector3d();

        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }

            final int baseX = chunk.getPos().getMinBlockX();
            final int baseZ = chunk.getPos().getMinBlockZ();

            for (int sectionIdx = 0; sectionIdx < chunk.getSectionsCount(); sectionIdx++) {
                final LevelChunkSection section = chunk.getSection(sectionIdx);
                if (section.hasOnlyAir()) {
                    continue;
                }

                final int baseY = chunk.getLevel().getSectionYFromSectionIndex(sectionIdx) << 4;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            final BlockState state = section.getBlockState(x, y, z);
                            final int emission = state.getLightEmission();
                            if (emission <= 0) {
                                continue;
                            }

                            // Use block centers so rotated sub-levels don't drift to the wrong cell.
                            worldPos.set(baseX + x + 0.5, baseY + y + 0.5, baseZ + z + 0.5);
                            pose.transformPosition(worldPos);

                            final long packed = BlockPos.asLong(
                                    (int) Math.floor(worldPos.x),
                                    (int) Math.floor(worldPos.y),
                                    (int) Math.floor(worldPos.z)
                            );

                            if (emission > result.get(packed)) {
                                result.put(packed, emission);
                            }
                        }
                    }
                }
            }
        }

        return result;
    }
}
