package dev.ryanhcode.sable.render.light_bridge;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * Client-side accumulator for "virtual" block light produced by sub-level emitters.
 * <p>
 * Once per client tick we collect every emitting block on every loaded sub-level, transform the
 * positions into world space, and spread the light out with a Manhattan-distance falloff. The
 * resulting world-space map is what the lighting mixins overlay on top of vanilla block light so
 * that a torch carried by a moving sub-level visibly illuminates the world it floats through.
 * <p>
 * The class is a singleton because the lighting hooks have to reach it from anywhere and it owns
 * the only piece of mutable state involved (the spread map).
 */
public final class VirtualLightManager {

    /** Margin (in blocks) used to decide whether a section update is "near" a sub-level. */
    private static final double WORLD_UPDATE_MARGIN = 15.0;

    private static final VirtualLightManager INSTANCE = new VirtualLightManager();

    /** Combined spread map for every active sub-level: packed {@link BlockPos} → light level. */
    private final Long2IntOpenHashMap activeLights = new Long2IntOpenHashMap();

    /**
     * Set while we sample world block light from inside the manager itself, so that the
     * lighting mixins know not to re-enter and overlay virtual light back onto themselves.
     */
    private final ThreadLocal<Boolean> sampling = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private VirtualLightManager() {
        this.activeLights.defaultReturnValue(0);
    }

    public static VirtualLightManager get() {
        return INSTANCE;
    }

    /**
     * Recomputes the virtual light map from every loaded sub-level's emitters. Called once per
     * client tick from {@link ClientSubLevelContainer#tick()}.
     */
    public void tick(final ClientLevel level) {
        if (!(level instanceof final SubLevelContainerHolder holder)) {
            return;
        }

        final ClientSubLevelContainer container = (ClientSubLevelContainer) holder.sable$getPlotContainer();
        if (container == null) {
            return;
        }

        final Long2IntOpenHashMap rebuilt = new Long2IntOpenHashMap();
        rebuilt.defaultReturnValue(0);

        for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isFinalized()) {
                continue;
            }

            final Long2IntOpenHashMap emitters = SubLevelLightBridge.collectEmittingBlocks(subLevel);
            if (emitters.isEmpty()) {
                continue;
            }

            for (final var entry : emitters.long2IntEntrySet()) {
                spreadInto(rebuilt, entry.getLongKey(), entry.getIntValue());
            }
        }

        // Diff the two maps so we only flag the sections that actually changed.
        final LongSet changed = new LongOpenHashSet();

        for (final var entry : this.activeLights.long2IntEntrySet()) {
            if (rebuilt.get(entry.getLongKey()) != entry.getIntValue()) {
                changed.add(entry.getLongKey());
            }
        }
        for (final var entry : rebuilt.long2IntEntrySet()) {
            if (!this.activeLights.containsKey(entry.getLongKey())) {
                changed.add(entry.getLongKey());
            }
        }

        this.activeLights.clear();
        this.activeLights.putAll(rebuilt);

        markSectionsDirty(level, changed);
    }

    /**
     * Notifies the manager that a block-light update happened in a world section. If the change
     * touches the area around any sub-level the matching sections are flagged so the renderer
     * picks up the new virtual contribution on the next pass.
     * <p>
     * Currently this only gates the fast path (we always re-run {@link #tick} every tick anyway),
     * but it gives us a hook to do per-section invalidation later without the lighting mixins
     * needing to know about sub-levels.
     */
    public void onWorldLightUpdate(final ClientLevel level, final int sectionX, final int sectionY, final int sectionZ) {
        if (!(level instanceof final SubLevelContainerHolder holder)) {
            return;
        }

        final ClientSubLevelContainer container = (ClientSubLevelContainer) holder.sable$getPlotContainer();
        if (container == null) {
            return;
        }

        final double minX = SectionPos.sectionToBlockCoord(sectionX);
        final double minY = SectionPos.sectionToBlockCoord(sectionY);
        final double minZ = SectionPos.sectionToBlockCoord(sectionZ);
        final double maxX = minX + 16;
        final double maxY = minY + 16;
        final double maxZ = minZ + 16;

        for (final ClientSubLevel subLevel : container.getAllSubLevels()) {
            if (!subLevel.isFinalized()) {
                continue;
            }

            final BoundingBox3dc bounds = subLevel.boundingBox();
            if (intersectsWithMargin(bounds, minX, minY, minZ, maxX, maxY, maxZ)) {
                // Reserved for future per-section invalidation. The full rebuild in tick()
                // already keeps the overlay correct on the next frame.
                break;
            }
        }
    }

    /**
     * @return the virtual light level at the given packed block position, or 0 if none.
     */
    public int getVirtualLight(final long packedPos) {
        return this.activeLights.get(packedPos);
    }

    /**
     * @return the virtual light level at the given block position, or 0 if none.
     */
    public int getVirtualLight(final BlockPos pos) {
        return this.activeLights.get(pos.asLong());
    }

    /**
     * @return whether anything is currently emitting virtual light. Hot path — called from the
     *         lighting mixins on every block lookup, which is why we don't expose the map size.
     */
    public boolean hasAnyLights() {
        return !this.activeLights.isEmpty();
    }

    /**
     * @return whether the current thread is sampling world light from inside this manager.
     *         The mixins use this to avoid recursing back into themselves.
     */
    public boolean isSampling() {
        return this.sampling.get();
    }

    /**
     * Spreads a single emitter into {@code out} with a Manhattan-distance falloff, taking the max
     * over any value already at the same position.
     */
    private static void spreadInto(final Long2IntOpenHashMap out, final long source, final int emission) {
        final int sx = BlockPos.getX(source);
        final int sy = BlockPos.getY(source);
        final int sz = BlockPos.getZ(source);

        for (int dx = -emission; dx <= emission; dx++) {
            final int adx = Math.abs(dx);
            final int yzBudget = emission - adx;

            for (int dy = -yzBudget; dy <= yzBudget; dy++) {
                final int ady = Math.abs(dy);
                final int zBudget = yzBudget - ady;

                for (int dz = -zBudget; dz <= zBudget; dz++) {
                    final int level = emission - adx - ady - Math.abs(dz);
                    if (level <= 0) {
                        continue;
                    }

                    final long packed = BlockPos.asLong(sx + dx, sy + dy, sz + dz);
                    if (level > out.get(packed)) {
                        out.put(packed, level);
                    }
                }
            }
        }
    }

    /**
     * Asks the level to rebuild every section that contains a changed virtual light value.
     * Walks neighbours too, since face lighting reads from adjacent sections.
     */
    private static void markSectionsDirty(final ClientLevel level, final LongSet positions) {
        if (positions.isEmpty()) {
            return;
        }

        final LongSet sections = new LongOpenHashSet();
        final LongIterator it = positions.iterator();

        while (it.hasNext()) {
            final long packed = it.nextLong();
            sections.add(SectionPos.asLong(
                    SectionPos.blockToSectionCoord(BlockPos.getX(packed)),
                    SectionPos.blockToSectionCoord(BlockPos.getY(packed)),
                    SectionPos.blockToSectionCoord(BlockPos.getZ(packed))
            ));
        }

        final LongIterator sectionIt = sections.iterator();
        while (sectionIt.hasNext()) {
            final long sec = sectionIt.nextLong();
            level.setSectionDirtyWithNeighbors(SectionPos.x(sec), SectionPos.y(sec), SectionPos.z(sec));
        }
    }

    private static boolean intersectsWithMargin(
            final BoundingBox3dc bounds,
            final double minX, final double minY, final double minZ,
            final double maxX, final double maxY, final double maxZ) {
        return maxX + WORLD_UPDATE_MARGIN >= bounds.minX() && minX - WORLD_UPDATE_MARGIN <= bounds.maxX()
                && maxY + WORLD_UPDATE_MARGIN >= bounds.minY() && minY - WORLD_UPDATE_MARGIN <= bounds.maxY()
                && maxZ + WORLD_UPDATE_MARGIN >= bounds.minZ() && minZ - WORLD_UPDATE_MARGIN <= bounds.maxZ();
    }
}
