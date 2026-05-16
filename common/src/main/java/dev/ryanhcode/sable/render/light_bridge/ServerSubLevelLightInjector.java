package dev.ryanhcode.sable.render.light_bridge;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.plot.SubLevelPlayerChunkSender;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side light injection between sub-levels and the world (world → plot direction).
 * Scans world emitters near sub-levels and injects them into the plot's light engine.
 */
public final class ServerSubLevelLightInjector {

    private static final double LIGHT_MARGIN = 15.0;

    private static final ConcurrentHashMap<UUID, LongSet> injectedPositions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long2IntOpenHashMap> cachedWorldSources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastBlockPositions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, int[]> lastScanBounds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Vector3d> lastRescanPositions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> needsRescan = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> needsReinject = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> needsResend = new ConcurrentHashMap<>();

    /**
     * Plot-local positions that correspond to opaque world blocks near each sub-level.
     * Used by the light engine mixin to block light propagation inside plots at world-opaque positions.
     */
    private static final ConcurrentHashMap<UUID, LongSet> plotLocalWorldOpaque = new ConcurrentHashMap<>();

    private ServerSubLevelLightInjector() {
    }

    /**
     * @return true if the given plot-local position corresponds to an opaque world block
     *         for any sub-level. Called from the light engine mixin during plot propagation.
     */
    public static boolean isWorldOpaqueInPlot(final long packedPlotPos) {
        for (final LongSet set : plotLocalWorldOpaque.values()) {
            if (set.contains(packedPlotPos)) return true;
        }
        return false;
    }

    /**
     * Called when block light changes in a world section. May be called from the light thread.
     */
    public static void onServerLightUpdate(final ServerLevel level, final int sectionX, final int sectionY, final int sectionZ) {
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;

        final double secMinX = SectionPos.sectionToBlockCoord(sectionX);
        final double secMinY = SectionPos.sectionToBlockCoord(sectionY);
        final double secMinZ = SectionPos.sectionToBlockCoord(sectionZ);
        final double secMaxX = secMinX + 16;
        final double secMaxY = secMinY + 16;
        final double secMaxZ = secMinZ + 16;

        for (final SubLevel subLevel : container.getAllSubLevels()) {
            final BoundingBox3dc bounds = subLevel.boundingBox();
            if (bounds == null) continue;

            if (secMaxX + LIGHT_MARGIN >= bounds.minX() && secMinX - LIGHT_MARGIN <= bounds.maxX()
                    && secMaxY + LIGHT_MARGIN >= bounds.minY() && secMinY - LIGHT_MARGIN <= bounds.maxY()
                    && secMaxZ + LIGHT_MARGIN >= bounds.minZ() && secMinZ - LIGHT_MARGIN <= bounds.maxZ()) {
                final UUID id = subLevel.getUniqueId();
                needsRescan.put(id, Boolean.TRUE);
                needsReinject.put(id, Boolean.TRUE);
            }
        }
    }

    /**
     * Called when a light-emitting block changes on a sub-level's plot.
     */
    public static void onPlotBlockLightChanged(final ServerLevel level, final ServerSubLevel changedSubLevel) {
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;

        final BoundingBox3dc changedBounds = changedSubLevel.boundingBox();
        if (changedBounds == null) return;

        for (final SubLevel other : container.getAllSubLevels()) {
            if (other == changedSubLevel) continue;
            if (boundsOverlap(changedBounds, other.boundingBox())) {
                final UUID id = other.getUniqueId();
                needsRescan.put(id, Boolean.TRUE);
                needsReinject.put(id, Boolean.TRUE);
            }
        }
    }

    /**
     * @return the cached world emitter sources for a sub-level, or null.
     */
    public static Long2IntOpenHashMap getCachedWorldSources(final UUID subLevelId) {
        return cachedWorldSources.get(subLevelId);
    }

    public static void markNeedsFullRescan(final UUID subLevelId) {
        needsRescan.put(subLevelId, Boolean.TRUE);
    }

    /**
     * Called from ServerLevelPlot.tick() before light engine updates.
     */
    public static void tickPlot(final ServerLevel level, final ServerSubLevel subLevel, final ServerLevelPlot plot) {
        final UUID id = subLevel.getUniqueId();

        try {
            final Vector3dc currentPos = subLevel.logicalPose().position();
            final long currentBlock = BlockPos.asLong(
                    (int) Math.floor(currentPos.x()),
                    (int) Math.floor(currentPos.y()),
                    (int) Math.floor(currentPos.z()));
            final Long lastBlock = lastBlockPositions.get(id);
            if (lastBlock == null) {
                lastBlockPositions.put(id, currentBlock);
                needsRescan.put(id, Boolean.TRUE);
            } else if (lastBlock != currentBlock) {
                final int dx = BlockPos.getX(currentBlock) - BlockPos.getX(lastBlock);
                final int dy = BlockPos.getY(currentBlock) - BlockPos.getY(lastBlock);
                final int dz = BlockPos.getZ(currentBlock) - BlockPos.getZ(lastBlock);
                lastBlockPositions.put(id, currentBlock);
                needsReinject.put(id, Boolean.TRUE);
                markNearbyForRescan(level, subLevel);

                // Try incremental scan (works for any translation)
                if (cachedWorldSources.containsKey(id)) {
                    incrementalScan(level, subLevel, dx, dy, dz);
                } else {
                    needsRescan.put(id, Boolean.TRUE);
                }
            } else if (cachedWorldSources.containsKey(id)) {
                // Position didn't change but bounds may have changed due to rotation
                final BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds != null) {
                    final int margin = (int) LIGHT_MARGIN;
                    final int newMinX = (int) Math.floor(bounds.minX()) - margin;
                    final int newMinY = Math.max(level.getMinBuildHeight(), (int) Math.floor(bounds.minY()) - margin);
                    final int newMinZ = (int) Math.floor(bounds.minZ()) - margin;
                    final int newMaxX = (int) Math.ceil(bounds.maxX()) + margin;
                    final int newMaxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(bounds.maxY()) + margin);
                    final int newMaxZ = (int) Math.ceil(bounds.maxZ()) + margin;
                    final int[] prev = lastScanBounds.get(id);
                    if (prev != null && (newMinX != prev[0] || newMinY != prev[1] || newMinZ != prev[2]
                            || newMaxX != prev[3] || newMaxY != prev[4] || newMaxZ != prev[5])) {
                        // Bounds changed (rotation) - do incremental bounds-diff scan
                        boundsDiffScan(level, subLevel, prev, newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
                        needsReinject.put(id, Boolean.TRUE);
                        markNearbyForRescan(level, subLevel);
                    }
                }
            }

            if (needsRescan.remove(id, Boolean.TRUE)) {
                final BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds.minX() >= bounds.maxX() && bounds.minZ() >= bounds.maxZ()) {
                    needsRescan.put(id, Boolean.TRUE);
                } else {
                    fullRescan(level, subLevel);
                    needsReinject.put(id, Boolean.TRUE);
                }
            }

            if (needsReinject.remove(id, Boolean.TRUE)) {
                final boolean success = reinject(level, subLevel, plot);
                if (!success) {
                    final Long2IntOpenHashMap src = cachedWorldSources.get(id);
                    if (src != null && !src.isEmpty()) {
                        needsRescan.put(id, Boolean.TRUE);
                    }
                }
            }
        } catch (final Throwable ignored) {
        }
    }

    public static void afterPlotTick(final ServerLevel level, final ServerSubLevel subLevel, final ServerLevelPlot plot) {
        final UUID id = subLevel.getUniqueId();
        if (!needsResend.remove(id, Boolean.TRUE)) return;

        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            if (holder.getChunk() == null) continue;
            final ChunkPos globalPos = holder.getPos();
            final var players = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level).getPlayersTracking(globalPos);
            for (final ServerPlayer player : players) {
                SubLevelPlayerChunkSender.sendLightUpdate(player.connection::send, plot.getLightEngine(), globalPos);
            }
        }
    }

    public static void clear() {
        injectedPositions.clear();
        cachedWorldSources.clear();
        lastRescanPositions.clear();
        lastBlockPositions.clear();
        lastScanBounds.clear();
        needsRescan.clear();
        needsReinject.clear();
        needsResend.clear();
        plotLocalWorldOpaque.clear();
    }

    private static void fullRescan(final ServerLevel level, final ServerSubLevel subLevel) {
        final BoundingBox3dc bounds = subLevel.boundingBox();
        if (bounds == null) return;

        final UUID id = subLevel.getUniqueId();
        final int margin = (int) LIGHT_MARGIN;
        final int minX = (int) Math.floor(bounds.minX()) - margin;
        final int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(bounds.minY()) - margin);
        final int minZ = (int) Math.floor(bounds.minZ()) - margin;
        final int maxX = (int) Math.ceil(bounds.maxX()) + margin;
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(bounds.maxY()) + margin);
        final int maxZ = (int) Math.ceil(bounds.maxZ()) + margin;

        final Long2IntOpenHashMap sources = scanWorldEmitters(level, subLevel, minX, minY, minZ, maxX, maxY, maxZ);
        scanOtherSubLevelEmitters(level, subLevel, bounds, margin, sources);
        scanWorldOpaqueIntoPlot(level, subLevel, minX, minY, minZ, maxX, maxY, maxZ);

        cachedWorldSources.put(id, sources);
        lastRescanPositions.put(id, new Vector3d(subLevel.logicalPose().position()));
        lastScanBounds.put(id, new int[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    /**
     * Incremental world scan: shifts cached sources by removing the trailing slice
     * and scanning only the leading slice in the direction of movement.
     */
    private static void incrementalScan(final ServerLevel level, final ServerSubLevel subLevel, final int dx, final int dy, final int dz) {
        final UUID id = subLevel.getUniqueId();
        final BoundingBox3dc bounds = subLevel.boundingBox();
        if (bounds == null) return;

        final Long2IntOpenHashMap sources = cachedWorldSources.get(id);
        if (sources == null) return;

        final int margin = (int) LIGHT_MARGIN;
        final int minX = (int) Math.floor(bounds.minX()) - margin;
        final int minY = Math.max(level.getMinBuildHeight(), (int) Math.floor(bounds.minY()) - margin);
        final int minZ = (int) Math.floor(bounds.minZ()) - margin;
        final int maxX = (int) Math.ceil(bounds.maxX()) + margin;
        final int maxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(bounds.maxY()) + margin);
        final int maxZ = (int) Math.ceil(bounds.maxZ()) + margin;

        // Remove entries from trailing slice (outside new bounds)
        final var iter = sources.long2IntEntrySet().iterator();
        while (iter.hasNext()) {
            final var entry = iter.next();
            final int wx = BlockPos.getX(entry.getLongKey());
            final int wy = BlockPos.getY(entry.getLongKey());
            final int wz = BlockPos.getZ(entry.getLongKey());
            if (wx < minX || wx > maxX || wy < minY || wy > maxY || wz < minZ || wz > maxZ) {
                iter.remove();
            }
        }

        // Scan leading slices on each axis that moved
        final int sliceMinX = dx > 0 ? (maxX - dx + 1) : (dx < 0 ? minX : minX);
        final int sliceMaxX = dx > 0 ? maxX : (dx < 0 ? (minX - dx - 1) : maxX);
        final int sliceMinY = dy > 0 ? (maxY - dy + 1) : (dy < 0 ? minY : minY);
        final int sliceMaxY = dy > 0 ? maxY : (dy < 0 ? (minY - dy - 1) : maxY);
        final int sliceMinZ = dz > 0 ? (maxZ - dz + 1) : (dz < 0 ? minZ : minZ);
        final int sliceMaxZ = dz > 0 ? maxZ : (dz < 0 ? (minZ - dz - 1) : maxZ);

        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        LevelChunk lastChunk = null;
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

        for (int wy = sliceMinY; wy <= sliceMaxY; wy++) {
            for (int wx = sliceMinX; wx <= sliceMaxX; wx++) {
                for (int wz = sliceMinZ; wz <= sliceMaxZ; wz++) {
                    final int cx = wx >> 4, cz = wz >> 4;
                    if (cx != lastChunkX || cz != lastChunkZ) {
                        lastChunk = level.getChunkSource().getChunkNow(cx, cz);
                        lastChunkX = cx; lastChunkZ = cz;
                        if (lastChunk == null) continue;
                        final SubLevel atWorld = Sable.HELPER.getContaining(level, new ChunkPos(cx, cz));
                        if (atWorld == subLevel) { lastChunk = null; continue; }
                    }
                    if (lastChunk == null) continue;
                    mutable.set(wx, wy, wz);
                    final int emission = lastChunk.getBlockState(mutable).getLightEmission();
                    if (emission > 0) {
                        sources.put(BlockPos.asLong(wx, wy, wz), emission);
                    }
                }
            }
        }

        // Also re-scan other sub-level emitters
        scanOtherSubLevelEmitters(level, subLevel, bounds, margin, sources);
        // Also re-scan world opaque into plot
        scanWorldOpaqueIntoPlot(level, subLevel, minX, minY, minZ, maxX, maxY, maxZ);
        lastScanBounds.put(id, new int[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    /**
     * Bounds-diff scan for rotation: removes entries outside new bounds,
     * scans only the regions in new bounds that weren't in old bounds.
     */
    private static void boundsDiffScan(final ServerLevel level, final ServerSubLevel subLevel,
            final int[] prev, final int newMinX, final int newMinY, final int newMinZ,
            final int newMaxX, final int newMaxY, final int newMaxZ) {
        final UUID id = subLevel.getUniqueId();
        final Long2IntOpenHashMap sources = cachedWorldSources.get(id);
        if (sources == null) return;

        // Remove entries outside new bounds
        final var iter = sources.long2IntEntrySet().iterator();
        while (iter.hasNext()) {
            final var entry = iter.next();
            final int wx = BlockPos.getX(entry.getLongKey());
            final int wy = BlockPos.getY(entry.getLongKey());
            final int wz = BlockPos.getZ(entry.getLongKey());
            if (wx < newMinX || wx > newMaxX || wy < newMinY || wy > newMaxY || wz < newMinZ || wz > newMaxZ) {
                iter.remove();
            }
        }

        // Scan new regions (positions in new bounds but not in old bounds)
        final int oldMinX = prev[0], oldMinY = prev[1], oldMinZ = prev[2];
        final int oldMaxX = prev[3], oldMaxY = prev[4], oldMaxZ = prev[5];
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        LevelChunk lastChunk = null;
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

        for (int wy = newMinY; wy <= newMaxY; wy++) {
            for (int wx = newMinX; wx <= newMaxX; wx++) {
                for (int wz = newMinZ; wz <= newMaxZ; wz++) {
                    // Skip positions that were already in old bounds
                    if (wx >= oldMinX && wx <= oldMaxX && wy >= oldMinY && wy <= oldMaxY && wz >= oldMinZ && wz <= oldMaxZ) continue;
                    final int cx = wx >> 4, cz = wz >> 4;
                    if (cx != lastChunkX || cz != lastChunkZ) {
                        lastChunk = level.getChunkSource().getChunkNow(cx, cz);
                        lastChunkX = cx; lastChunkZ = cz;
                        if (lastChunk == null) continue;
                        final SubLevel atWorld = Sable.HELPER.getContaining(level, new ChunkPos(cx, cz));
                        if (atWorld == subLevel) { lastChunk = null; continue; }
                    }
                    if (lastChunk == null) continue;
                    mutable.set(wx, wy, wz);
                    final int emission = lastChunk.getBlockState(mutable).getLightEmission();
                    if (emission > 0) {
                        sources.put(BlockPos.asLong(wx, wy, wz), emission);
                    }
                }
            }
        }

        final int margin = (int) LIGHT_MARGIN;
        scanOtherSubLevelEmitters(level, subLevel, subLevel.boundingBox(), margin, sources);
        scanWorldOpaqueIntoPlot(level, subLevel, newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
        lastScanBounds.put(id, new int[]{newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ});
    }

    private static Long2IntOpenHashMap scanWorldEmitters(final ServerLevel level, final ServerSubLevel subLevel,
            final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        final Long2IntOpenHashMap sources = new Long2IntOpenHashMap();
        sources.defaultReturnValue(0);
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        LevelChunk lastChunk = null;
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

        for (int wy = minY; wy <= maxY; wy++) {
            for (int wx = minX; wx <= maxX; wx++) {
                for (int wz = minZ; wz <= maxZ; wz++) {
                    final int cx = wx >> 4, cz = wz >> 4;
                    if (cx != lastChunkX || cz != lastChunkZ) {
                        lastChunk = level.getChunkSource().getChunkNow(cx, cz);
                        lastChunkX = cx;
                        lastChunkZ = cz;
                        if (lastChunk == null) continue;
                        final SubLevel atWorld = Sable.HELPER.getContaining(level, new ChunkPos(cx, cz));
                        if (atWorld == subLevel) { lastChunk = null; continue; }
                    }
                    if (lastChunk == null) continue;

                    mutable.set(wx, wy, wz);
                    final int emission = lastChunk.getBlockState(mutable).getLightEmission();
                    if (emission > 0) {
                        sources.put(BlockPos.asLong(wx, wy, wz), emission);
                    }
                }
            }
        }
        return sources;
    }

    private static void scanWorldOpaqueIntoPlot(final ServerLevel level, final ServerSubLevel subLevel,
            final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        final UUID id = subLevel.getUniqueId();
        final Pose3dc pose = subLevel.logicalPose();
        final LongOpenHashSet worldOpaqueInPlot = new LongOpenHashSet();
        final Vector3d plotLocalPos = new Vector3d();
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        LevelChunk lastChunk = null;
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;

        for (int wy = minY; wy <= maxY; wy++) {
            for (int wx = minX; wx <= maxX; wx++) {
                for (int wz = minZ; wz <= maxZ; wz++) {
                    final int cx = wx >> 4, cz = wz >> 4;
                    if (cx != lastChunkX || cz != lastChunkZ) {
                        lastChunk = level.getChunkSource().getChunkNow(cx, cz);
                        lastChunkX = cx;
                        lastChunkZ = cz;
                        if (lastChunk == null) continue;
                        final SubLevel atWorld = Sable.HELPER.getContaining(level, new ChunkPos(cx, cz));
                        if (atWorld == subLevel) { lastChunk = null; continue; }
                    }
                    if (lastChunk == null) continue;

                    mutable.set(wx, wy, wz);
                    final BlockState worldBlockState = lastChunk.getBlockState(mutable);
                    if (!worldBlockState.canOcclude() || worldBlockState.useShapeForLightOcclusion()) continue;

                    plotLocalPos.set(wx + 0.5, wy + 0.5, wz + 0.5);
                    pose.transformPositionInverse(plotLocalPos);

                    worldOpaqueInPlot.add(BlockPos.asLong(
                            (int) Math.floor(plotLocalPos.x),
                            (int) Math.floor(plotLocalPos.y),
                            (int) Math.floor(plotLocalPos.z)));
                }
            }
        }

        // Also project other sub-levels' opaque world positions into this plot's local space.
        // This prevents light from sub-level A passing through sub-level B to reach this sub-level.
        final LongSet subLevelOpaqueWorld = ServerSubLevelWorldInjector.getOpaquePositions();
        final LongSet ownGapFills = ServerSubLevelWorldInjector.getGapFillsForSubLevel(id);
        for (final long worldPacked : subLevelOpaqueWorld) {
            if (ownGapFills.contains(worldPacked)) continue;
            plotLocalPos.set(
                    BlockPos.getX(worldPacked) + 0.5,
                    BlockPos.getY(worldPacked) + 0.5,
                    BlockPos.getZ(worldPacked) + 0.5);
            pose.transformPositionInverse(plotLocalPos);
            worldOpaqueInPlot.add(BlockPos.asLong(
                    (int) Math.floor(plotLocalPos.x),
                    (int) Math.floor(plotLocalPos.y),
                    (int) Math.floor(plotLocalPos.z)));
        }


        for (final PlotChunkHolder holder : subLevel.getPlot().getLoadedChunks()) {
            final LevelChunk plotChunk = holder.getChunk();
            if (plotChunk == null) continue;
            final int pcBaseX = plotChunk.getPos().getMinBlockX();
            final int pcBaseZ = plotChunk.getPos().getMinBlockZ();
            for (int sIdx = 0; sIdx < plotChunk.getSectionsCount(); sIdx++) {
                final var section = plotChunk.getSection(sIdx);
                if (section.hasOnlyAir()) continue;
                final int pcBaseY = plotChunk.getLevel().getSectionYFromSectionIndex(sIdx) << 4;
                for (int bx = 0; bx < 16; bx++)
                    for (int by = 0; by < 16; by++)
                        for (int bz = 0; bz < 16; bz++) {
                            if (!section.getBlockState(bx, by, bz).isAir()) {
                                worldOpaqueInPlot.remove(BlockPos.asLong(pcBaseX + bx, pcBaseY + by, pcBaseZ + bz));
                            }
                        }
            }
        }

        plotLocalWorldOpaque.put(id, worldOpaqueInPlot);
    }

    private static void scanOtherSubLevelEmitters(final ServerLevel level, final ServerSubLevel subLevel,
            final BoundingBox3dc bounds, final int margin, final Long2IntOpenHashMap sources) {
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;

        final Vector3d worldPos = new Vector3d();
        for (final SubLevel other : container.getAllSubLevels()) {
            if (other == subLevel || !(other instanceof final ServerSubLevel otherServer)) continue;

            final BoundingBox3dc otherBounds = other.boundingBox();
            if (otherBounds == null || !boundsOverlap(bounds, otherBounds)) continue;

            final var otherPlot = otherServer.getPlot();
            if (otherPlot.getBoundingBox() == null) continue;

            final Pose3dc otherPose = other.logicalPose();

            for (final PlotChunkHolder holder : otherPlot.getLoadedChunks()) {
                final var plotChunk = holder.getChunk();
                if (plotChunk == null) continue;

                final int baseX = plotChunk.getPos().getMinBlockX();
                final int baseZ = plotChunk.getPos().getMinBlockZ();

                for (int sIdx = 0; sIdx < plotChunk.getSectionsCount(); sIdx++) {
                    final var section = plotChunk.getSection(sIdx);
                    if (section.hasOnlyAir()) continue;

                    final int baseY = plotChunk.getLevel().getSectionYFromSectionIndex(sIdx) << 4;

                    for (int x = 0; x < 16; x++)
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++) {
                                final int em = section.getBlockState(x, y, z).getLightEmission();
                                if (em <= 0) continue;

                                worldPos.set(baseX + x + 0.5, baseY + y + 0.5, baseZ + z + 0.5);
                                otherPose.transformPosition(worldPos);

                                if (worldPos.x >= bounds.minX() - margin && worldPos.x <= bounds.maxX() + margin
                                        && worldPos.y >= bounds.minY() - margin && worldPos.y <= bounds.maxY() + margin
                                        && worldPos.z >= bounds.minZ() - margin && worldPos.z <= bounds.maxZ() + margin) {
                                    final long packed = BlockPos.asLong(
                                            (int) Math.floor(worldPos.x),
                                            (int) Math.floor(worldPos.y),
                                            (int) Math.floor(worldPos.z));
                                    final int existing = sources.get(packed);
                                    if (em > existing) sources.put(packed, em);
                                }
                            }
                }
            }
        }
    }

    private static boolean reinject(final ServerLevel level, final ServerSubLevel subLevel, final ServerLevelPlot plot) {
        final UUID subLevelId = subLevel.getUniqueId();
        final Pose3dc pose = subLevel.logicalPose();
        final LevelLightEngine plotLightEngine = plot.getLightEngine();
        if (plotLightEngine.blockEngine == null) return false;

        boolean changed = false;

        final LongSet oldPositions = injectedPositions.remove(subLevelId);
        if (oldPositions != null) {
            for (final long packed : oldPositions) {
                try {
                    final int oldLevel = plotLightEngine.blockEngine.storage.getStoredLevel(packed);
                    if (oldLevel > 0) {
                        plotLightEngine.blockEngine.storage.setStoredLevel(packed, 0);
                        plotLightEngine.blockEngine.enqueueDecrease(
                                packed, LightEngine.QueueEntry.decreaseAllDirections(oldLevel));
                        changed = true;
                    }
                } catch (final NullPointerException ignored) {}
            }
            if (changed) {
                do { plotLightEngine.runLightUpdates(); } while (plotLightEngine.hasLightWork());
            }
        }

        final Long2IntOpenHashMap sources = cachedWorldSources.get(subLevelId);
        if (sources == null || sources.isEmpty()) {
            if (changed) {
                plotLightEngine.blockEngine.storage.swapSectionMap();
                needsResend.put(subLevelId, Boolean.TRUE);
            }
            return changed;
        }

        final LongSet newPositions = new LongOpenHashSet();
        final Vector3d plotLocal = new Vector3d();

        for (final var entry : sources.long2IntEntrySet()) {
            final int wx = BlockPos.getX(entry.getLongKey());
            final int wy = BlockPos.getY(entry.getLongKey());
            final int wz = BlockPos.getZ(entry.getLongKey());
            final int emission = entry.getIntValue();

            plotLocal.set(wx + 0.5, wy + 0.5, wz + 0.5);
            pose.transformPositionInverse(plotLocal);

            if (!plot.contains(plotLocal)) continue;

            final long plotPacked = BlockPos.containing(plotLocal.x, plotLocal.y, plotLocal.z).asLong();
            try {
                plotLightEngine.blockEngine.storage.setStoredLevel(plotPacked, emission);
                plotLightEngine.blockEngine.enqueueIncrease(
                        plotPacked, LightEngine.QueueEntry.increaseLightFromEmission(emission, true));
                newPositions.add(plotPacked);
                changed = true;
            } catch (final NullPointerException ignored) {}
        }

        if (!newPositions.isEmpty()) {
            injectedPositions.put(subLevelId, newPositions);
        }

        if (changed) {
            plotLightEngine.blockEngine.storage.swapSectionMap();
            needsResend.put(subLevelId, Boolean.TRUE);
        }

        return !newPositions.isEmpty();
    }



    private static void markNearbyForRescan(final ServerLevel level, final ServerSubLevel movedSubLevel) {
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) return;

        final BoundingBox3dc movedBounds = movedSubLevel.boundingBox();
        if (movedBounds == null) return;

        for (final SubLevel other : container.getAllSubLevels()) {
            if (other == movedSubLevel) continue;
            if (boundsOverlap(movedBounds, other.boundingBox())) {
                final UUID id = other.getUniqueId();
                needsRescan.put(id, Boolean.TRUE);
                needsReinject.put(id, Boolean.TRUE);
            }
        }
    }

    private static boolean boundsOverlap(final BoundingBox3dc a, final BoundingBox3dc b) {
        if (a == null || b == null) return false;
        return a.maxX() + LIGHT_MARGIN >= b.minX() && a.minX() - LIGHT_MARGIN <= b.maxX()
                && a.maxY() + LIGHT_MARGIN >= b.minY() && a.minY() - LIGHT_MARGIN <= b.maxY()
                && a.maxZ() + LIGHT_MARGIN >= b.minZ() && a.minZ() - LIGHT_MARGIN <= b.maxZ();
    }
}
