package dev.ryanhcode.sable.render.light_bridge;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side light injection from sub-level plots into the world (plot → world direction).
 * <p>
 * Handles three unified concerns triggered by sub-level movement or block changes:
 * <ul>
 *   <li>Emitter projection: sub-level light sources → world light engine</li>
 *   <li>Opacity projection: sub-level opaque blocks → light propagation barrier</li>
 *   <li>Neighbor notification: when a sub-level changes, nearby sub-levels re-propagate</li>
 * </ul>
 */
public final class ServerSubLevelWorldInjector {

    private static final double LIGHT_MARGIN = 15.0;

    private static final ConcurrentHashMap<UUID, LongSet> injectedPositions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long2IntOpenHashMap> cachedPlotSources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LongSet> perSubLevelOpaque = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, LongSet> perSubLevelOpaqueCore = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap> perSubLevelShapes = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, long[]> lastBoundsCorners = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Boolean> needsUpdate = new ConcurrentHashMap<>();

    /** Plot-local block data cache. Only rebuilt on block change, re-projected on movement. */
    private static final ConcurrentHashMap<UUID, PlotLocalLightData> plotLocalCache = new ConcurrentHashMap<>();
    /** Sub-levels that need their plot-local cache rebuilt (block changed). */
    private static final ConcurrentHashMap<UUID, Boolean> needsPlotRescan = new ConcurrentHashMap<>();

    // --- Global opaque state ---
    private static volatile LongSet opaquePositions = new LongOpenHashSet();
    /** Opaque positions without gap-fills - used for sub-level-to-sub-level projection. */
    private static volatile LongSet opaquePositionsCore = new LongOpenHashSet();
    private static volatile boolean opaqueDirty = false;

    /**
     * Shape-occluding sub-level blocks in world space: packed pos → 6-bit face mask.
     * Bit i set means the block's shape fully covers face Direction.values()[i].
     * Used by the shapeOccludes mixin for slabs/stairs.
     */
    private static volatile it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap shapeOcclusionMap = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();

    private ServerSubLevelWorldInjector() {
    }

    // --- Public API ---

    /**
     * @return true if a sub-level's opaque block occupies the given packed world position.
     *         Called from the light engine mixin on the light thread.
     */
    public static boolean isOpaqueAt(final long packedPos) {
        return opaquePositions.contains(packedPos);
    }

    /**
     * @return the global set of all sub-level opaque world positions.
     */
    public static LongSet getOpaquePositions() {
        return opaquePositions;
    }

    /**
     * @return the gap-fill positions for a specific sub-level (positions to exclude from its own plot).
     */
    public static LongSet getGapFillsForSubLevel(final java.util.UUID subLevelId) {
        final LongSet full = perSubLevelOpaque.get(subLevelId);
        final LongSet core = perSubLevelOpaqueCore.get(subLevelId);
        if (full == null || core == null) return new LongOpenHashSet();
        final LongOpenHashSet gaps = new LongOpenHashSet(full);
        gaps.removeAll(core);
        return gaps;
    }


    /**
     * @return the face occlusion mask for a shape-occluding sub-level block at the given position,
     *         or 0 if none. Bit i set = face Direction.values()[i] is fully occluded.
     */
    public static byte getShapeOcclusion(final long packedPos) {
        return shapeOcclusionMap.get(packedPos);
    }

    /**
     * Marks a sub-level as needing a full update (rescan emitters + opacity + reinject).
     */
    public static void markNeedsFullRescan(final UUID subLevelId) {
        needsPlotRescan.put(subLevelId, Boolean.TRUE);
        needsUpdate.put(subLevelId, Boolean.TRUE);
    }

    /**
     * Called when a block changes on a sub-level's plot that may affect world lighting.
     */
    public static void onPlotBlockChanged(final ServerSubLevel subLevel) {
        final UUID id = subLevel.getUniqueId();
        needsPlotRescan.put(id, Boolean.TRUE);
        needsUpdate.put(id, Boolean.TRUE);
    }

    /**
     * Called once per server tick after all plots have ticked, from ServerSubLevelContainer.tick().
     */
    public static void tick(final ServerLevel level) {
        final var container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        final List<? extends SubLevel> allSubLevels = container.getAllSubLevels();

        // Phase 1: Detect movement, run scans for sub-levels that changed
        for (final SubLevel subLevel : allSubLevels) {
            if (!(subLevel instanceof final ServerSubLevel ssl)) continue;
            detectMovement(ssl);
        }

        // Phase 2: Process updates and collect which sub-levels changed
        final java.util.HashSet<UUID> changedIds = new java.util.HashSet<>();
        final java.util.ArrayList<BoundingBox3dc> changedBounds = new java.util.ArrayList<>(4);

        for (final SubLevel subLevel : allSubLevels) {
            if (!(subLevel instanceof final ServerSubLevel ssl)) continue;
            final UUID id = ssl.getUniqueId();

            // Rebuild plot-local cache if blocks changed (expensive, rare)
            if (needsPlotRescan.remove(id, Boolean.TRUE)) {
                rebuildPlotLocalCache(ssl);
            }

            if (needsUpdate.remove(id, Boolean.TRUE)) {
                // Re-project cached plot-local data to world space (cheap)
                projectToWorldSpace(ssl);
                opaqueDirty = true;

                final BoundingBox3dc bounds = ssl.boundingBox();
                if (bounds != null) {
                    changedIds.add(id);
                    changedBounds.add(bounds);
                }
            }
        }

        // Phase 3: Rebuild global opaque set if anything changed (before reinjections)
        if (opaqueDirty) {
            opaqueDirty = false;
            final LongOpenHashSet merged = new LongOpenHashSet();
            for (final LongSet set : perSubLevelOpaque.values()) {
                merged.addAll(set);
            }
            opaquePositions = merged;
            final LongOpenHashSet mergedCore = new LongOpenHashSet();
            for (final LongSet set : perSubLevelOpaqueCore.values()) { mergedCore.addAll(set); }
            opaquePositionsCore = mergedCore;

            final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap mergedShapes = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
            mergedShapes.defaultReturnValue((byte) 0);
            for (final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap map : perSubLevelShapes.values()) {
                for (final var entry : map.long2ByteEntrySet()) {
                    mergedShapes.mergeByte(entry.getLongKey(), entry.getByteValue(), (a, b) -> (byte) (a | b));
                }
            }
            shapeOcclusionMap = mergedShapes;

            // Force world light re-propagation: find world emitters near changed sub-levels,
            // fully clear their light and re-propagate. Must be done on the light thread.
            final net.minecraft.world.level.lighting.LevelLightEngine wle = level.getLightEngine();
            if (wle instanceof final net.minecraft.server.level.ThreadedLevelLightEngine tle) {
                // Collect world emitter positions near changed bounds
                final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap worldEmitters = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
                for (final BoundingBox3dc cb : changedBounds) {
                    final int margin = 15;
                    final int mnX = (int) Math.floor(cb.minX()) - margin;
                    final int mnY = Math.max(level.getMinBuildHeight(), (int) Math.floor(cb.minY()) - margin);
                    final int mnZ = (int) Math.floor(cb.minZ()) - margin;
                    final int mxX = (int) Math.ceil(cb.maxX()) + margin;
                    final int mxY = Math.min(level.getMaxBuildHeight() - 1, (int) Math.ceil(cb.maxY()) + margin);
                    final int mxZ = (int) Math.ceil(cb.maxZ()) + margin;
                    final BlockPos.MutableBlockPos mut = new BlockPos.MutableBlockPos();
                    net.minecraft.world.level.chunk.LevelChunk lc = null;
                    int lcx = Integer.MIN_VALUE, lcz = Integer.MIN_VALUE;
                    for (int wy = mnY; wy <= mxY; wy++)
                        for (int wx = mnX; wx <= mxX; wx++)
                            for (int wz = mnZ; wz <= mxZ; wz++) {
                                final int cx = wx >> 4, cz = wz >> 4;
                                if (cx != lcx || cz != lcz) { lc = level.getChunkSource().getChunkNow(cx, cz); lcx = cx; lcz = cz; }
                                if (lc == null) continue;
                                mut.set(wx, wy, wz);
                                final int emLvl = lc.getBlockState(mut).getLightEmission();
                                if (emLvl > 0)
                                    worldEmitters.put(BlockPos.asLong(wx, wy, wz), emLvl);
                            }
                }
                if (!worldEmitters.isEmpty()) {
                    // On light thread: clear each emitter's light fully, then re-emit
                    tle.taskMailbox.tell(() -> {
                        if (tle.blockEngine == null) return;
                        // Clear
                        for (final long ep : worldEmitters.keySet()) {
                            final int stored = tle.blockEngine.storage.getStoredLevel(ep);
                            if (stored > 0) {
                                tle.blockEngine.storage.setStoredLevel(ep, 0);
                                tle.blockEngine.enqueueDecrease(ep,
                                        net.minecraft.world.level.lighting.LightEngine.QueueEntry.decreaseAllDirections(stored));
                            }
                        }
                        tle.blockEngine.runLightUpdates();
                        // Re-emit (skip emitters that are now inside opaque sub-level geometry)
                        for (final var entry : worldEmitters.long2IntEntrySet()) {
                            final long ep = entry.getLongKey();
                            if (opaquePositionsCore.contains(ep)) continue;
                            final int em = entry.getIntValue();
                            tle.blockEngine.storage.setStoredLevel(ep, em);
                            tle.blockEngine.enqueueIncrease(ep,
                                    net.minecraft.world.level.lighting.LightEngine.QueueEntry.increaseLightFromEmission(em, true));
                        }
                        tle.blockEngine.runLightUpdates();
                        tle.blockEngine.storage.swapSectionMap();
                        // Notify clients
                        final LongOpenHashSet sections = new LongOpenHashSet();
                        for (final long ep : worldEmitters.keySet()) {
                            addNeighborSections(sections, ep);
                        }
                        level.getServer().execute(() -> sendWorldLightUpdates(level, sections));
                    });
                    tle.tryScheduleUpdate();
                }
            }
        }

        // Phase 4: If any sub-level changed, notify nearby emitting sub-levels
        if (!changedBounds.isEmpty()) {
            for (final SubLevel subLevel : allSubLevels) {
                if (!(subLevel instanceof final ServerSubLevel ssl)) continue;
                final UUID id = ssl.getUniqueId();
                // Skip if already updated this tick
                if (needsUpdate.containsKey(id)) continue;
                final Long2IntOpenHashMap src = cachedPlotSources.get(id);
                if ((src == null || src.isEmpty()) && !injectedPositions.containsKey(id)) continue;

                final BoundingBox3dc emitterBounds = ssl.boundingBox();
                if (emitterBounds == null) continue;

                for (final BoundingBox3dc changed : changedBounds) {
                    if (boundsOverlap(emitterBounds, changed)) {
                        enqueueReinject(level, id);
                        break;
                    }
                }
            }
        }

        // Phase 5: Enqueue reinjections for sub-levels that were scanned in phase 2
        for (final UUID id : changedIds) {
            enqueueReinject(level, id);
        }
    }

    /**
     * Cleans up state for a removed sub-level.
     */
    public static void onSubLevelRemoved(final ServerLevel level, final UUID subLevelId) {
        final LevelLightEngine lightEngine = level.getLightEngine();
        if (!(lightEngine instanceof final ThreadedLevelLightEngine threadedEngine)) return;

        final LongSet oldPositions = injectedPositions.remove(subLevelId);
        if (oldPositions != null && !oldPositions.isEmpty() && threadedEngine.blockEngine != null) {
            threadedEngine.taskMailbox.tell(() -> {
                if (threadedEngine.blockEngine == null) return;
                for (final long packed : oldPositions) {
                    threadedEngine.blockEngine.checkBlock(new BlockPos(
                            BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed)));
                }
                threadedEngine.blockEngine.runLightUpdates();
                threadedEngine.blockEngine.storage.swapSectionMap();
            });
            threadedEngine.tryScheduleUpdate();
        }

        cachedPlotSources.remove(subLevelId);
        lastBoundsCorners.remove(subLevelId);
        needsUpdate.remove(subLevelId);
        needsPlotRescan.remove(subLevelId);
        plotLocalCache.remove(subLevelId);
        perSubLevelOpaqueCore.remove(subLevelId);
        if (perSubLevelOpaque.remove(subLevelId) != null || perSubLevelShapes.remove(subLevelId) != null) {
            opaqueDirty = true;
        }
    }

    public static void clear() {
        injectedPositions.clear();
        cachedPlotSources.clear();
        perSubLevelOpaque.clear();
        perSubLevelOpaqueCore.clear();
        opaquePositionsCore = new LongOpenHashSet();
        perSubLevelShapes.clear();
        lastBoundsCorners.clear();
        needsUpdate.clear();
        plotLocalCache.clear();
        needsPlotRescan.clear();
        opaquePositions = new LongOpenHashSet();
        shapeOcclusionMap = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        opaqueDirty = false;
    }

    // --- Internal ---

    private static boolean boundsOverlap(final BoundingBox3dc a, final BoundingBox3dc b) {
        return a.maxX() + LIGHT_MARGIN >= b.minX() && a.minX() - LIGHT_MARGIN <= b.maxX()
                && a.maxY() + LIGHT_MARGIN >= b.minY() && a.minY() - LIGHT_MARGIN <= b.maxY()
                && a.maxZ() + LIGHT_MARGIN >= b.minZ() && a.minZ() - LIGHT_MARGIN <= b.maxZ();
    }

    private static void detectMovement(final ServerSubLevel subLevel) {
        final UUID id = subLevel.getUniqueId();
        final BoundingBox3dc bounds = subLevel.boundingBox();
        if (bounds == null) return;

        // Compute 8 bounding box corners as block positions
        final long[] currentCorners = new long[] {
            BlockPos.asLong((int) Math.floor(bounds.minX()), (int) Math.floor(bounds.minY()), (int) Math.floor(bounds.minZ())),
            BlockPos.asLong((int) Math.floor(bounds.maxX()), (int) Math.floor(bounds.minY()), (int) Math.floor(bounds.minZ())),
            BlockPos.asLong((int) Math.floor(bounds.minX()), (int) Math.floor(bounds.maxY()), (int) Math.floor(bounds.minZ())),
            BlockPos.asLong((int) Math.floor(bounds.maxX()), (int) Math.floor(bounds.maxY()), (int) Math.floor(bounds.minZ())),
            BlockPos.asLong((int) Math.floor(bounds.minX()), (int) Math.floor(bounds.minY()), (int) Math.floor(bounds.maxZ())),
            BlockPos.asLong((int) Math.floor(bounds.maxX()), (int) Math.floor(bounds.minY()), (int) Math.floor(bounds.maxZ())),
            BlockPos.asLong((int) Math.floor(bounds.minX()), (int) Math.floor(bounds.maxY()), (int) Math.floor(bounds.maxZ())),
            BlockPos.asLong((int) Math.floor(bounds.maxX()), (int) Math.floor(bounds.maxY()), (int) Math.floor(bounds.maxZ()))
        };

        final long[] lastCorners = lastBoundsCorners.get(id);
        if (lastCorners == null) {
            lastBoundsCorners.put(id, currentCorners);
            needsUpdate.put(id, Boolean.TRUE);
        } else {
            boolean changed = false;
            for (int i = 0; i < 8; i++) {
                if (currentCorners[i] != lastCorners[i]) { changed = true; break; }
            }
            if (changed) {
                lastBoundsCorners.put(id, currentCorners);
                needsUpdate.put(id, Boolean.TRUE);
            }
        }
    }

    /**
     * Rebuilds the plot-local cache by iterating plot chunks. Only called on block change.
     */
    private static void rebuildPlotLocalCache(final ServerSubLevel subLevel) {
        final UUID id = subLevel.getUniqueId();
        final ServerLevelPlot plot = subLevel.getPlot();
        final BoundingBox3ic plotBounds = plot.getBoundingBox();
        if (plotBounds == null) {
            plotLocalCache.put(id, PlotLocalLightData.EMPTY);
            return;
        }

        final it.unimi.dsi.fastutil.longs.LongArrayList opaqueList = new it.unimi.dsi.fastutil.longs.LongArrayList();
        final it.unimi.dsi.fastutil.longs.LongArrayList emitterList = new it.unimi.dsi.fastutil.longs.LongArrayList();
        final it.unimi.dsi.fastutil.bytes.ByteArrayList emitterLevelList = new it.unimi.dsi.fastutil.bytes.ByteArrayList();
        final it.unimi.dsi.fastutil.longs.LongArrayList shapeList = new it.unimi.dsi.fastutil.longs.LongArrayList();
        final it.unimi.dsi.fastutil.bytes.ByteArrayList shapeMaskList = new it.unimi.dsi.fastutil.bytes.ByteArrayList();

        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;

            final int baseX = chunk.getPos().getMinBlockX();
            final int baseZ = chunk.getPos().getMinBlockZ();

            for (int sIdx = 0; sIdx < chunk.getSectionsCount(); sIdx++) {
                final LevelChunkSection section = chunk.getSection(sIdx);
                if (section.hasOnlyAir()) continue;

                final int baseY = chunk.getLevel().getSectionYFromSectionIndex(sIdx) << 4;

                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            final BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;

                            final long plotLocal = BlockPos.asLong(baseX + x, baseY + y, baseZ + z);

                            final int emission = state.getLightEmission();
                            if (emission > 0) {
                                emitterList.add(plotLocal);
                                emitterLevelList.add((byte) emission);
                            }

                            if (state.canOcclude() && !state.useShapeForLightOcclusion()) {
                                opaqueList.add(plotLocal);
                            } else if (state.useShapeForLightOcclusion()) {
                                byte mask = 0;
                                final BlockPos localPos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                                for (final net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                                    final net.minecraft.world.phys.shapes.VoxelShape shape = state.getFaceOcclusionShape(chunk.getLevel(), localPos, dir);
                                    if (net.minecraft.world.phys.shapes.Shapes.faceShapeOccludes(shape, net.minecraft.world.phys.shapes.Shapes.empty())) {
                                        mask |= (byte) (1 << dir.ordinal());
                                    }
                                }
                                if (mask != 0) {
                                    shapeList.add(plotLocal);
                                    shapeMaskList.add(mask);
                                }
                            }
                        }
                    }
                }
            }
        }

        plotLocalCache.put(id, new PlotLocalLightData(
                opaqueList.toLongArray(),
                emitterList.toLongArray(),
                emitterLevelList.toByteArray(),
                shapeList.toLongArray(),
                shapeMaskList.toByteArray()
        ));
    }

    /**
     * Projects cached plot-local data to world space using the current pose. Cheap — just matrix transforms.
     */
    private static void projectToWorldSpace(final ServerSubLevel subLevel) {
        final UUID id = subLevel.getUniqueId();
        final PlotLocalLightData cache = plotLocalCache.get(id);
        if (cache == null || cache == PlotLocalLightData.EMPTY) {
            cachedPlotSources.put(id, new Long2IntOpenHashMap());
            perSubLevelOpaque.put(id, new LongOpenHashSet());
            perSubLevelShapes.put(id, new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap());
            return;
        }

        final Pose3dc pose = subLevel.logicalPose();
        final Vector3d worldPos = new Vector3d();

        // Project emitters
        final Long2IntOpenHashMap emitters = new Long2IntOpenHashMap();
        emitters.defaultReturnValue(0);
        for (int i = 0; i < cache.emitterPositions.length; i++) {
            worldPos.set(
                    BlockPos.getX(cache.emitterPositions[i]) + 0.5,
                    BlockPos.getY(cache.emitterPositions[i]) + 0.5,
                    BlockPos.getZ(cache.emitterPositions[i]) + 0.5);
            pose.transformPosition(worldPos);
            final long packed = BlockPos.asLong((int) Math.floor(worldPos.x), (int) Math.floor(worldPos.y), (int) Math.floor(worldPos.z));
            final int emission = cache.emitterLevels[i] & 0xFF;
            if (emission > emitters.get(packed)) {
                emitters.put(packed, emission);
            }
        }

        // Project opaque: project plot-local block centres, then fill any cells along the line between two
        // adjacent plot-local blocks whose world projections aren't axis-adjacent. Prevents diagonal light
        // leaks under rotation.
        final LongOpenHashSet opaque = new LongOpenHashSet();
        // First pass: project centres
        final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap plotToWorld = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        for (final long plotLocal : cache.opaquePositions) {
            worldPos.set(BlockPos.getX(plotLocal) + 0.5, BlockPos.getY(plotLocal) + 0.5, BlockPos.getZ(plotLocal) + 0.5);
            pose.transformPosition(worldPos);
            final long worldCell = BlockPos.asLong((int) Math.floor(worldPos.x), (int) Math.floor(worldPos.y), (int) Math.floor(worldPos.z));
            opaque.add(worldCell);
            plotToWorld.put(plotLocal, worldCell);
        }
        // Save core (no gaps) for sub-level-to-sub-level projection
        perSubLevelOpaqueCore.put(id, new LongOpenHashSet(opaque));
        // Second pass: fill gaps between adjacent plot-local blocks
        for (final long plotLocal : cache.opaquePositions) {
            final long wA = plotToWorld.get(plotLocal);
            final int ax = BlockPos.getX(wA), ay = BlockPos.getY(wA), az = BlockPos.getZ(wA);
            final int px = BlockPos.getX(plotLocal), py = BlockPos.getY(plotLocal), pz = BlockPos.getZ(plotLocal);
            // Check 6 face + 12 edge neighbors in plot-local space
            final long[] neighbors = {
                BlockPos.asLong(px+1,py,pz), BlockPos.asLong(px-1,py,pz),
                BlockPos.asLong(px,py+1,pz), BlockPos.asLong(px,py-1,pz),
                BlockPos.asLong(px,py,pz+1), BlockPos.asLong(px,py,pz-1),
                BlockPos.asLong(px+1,py+1,pz), BlockPos.asLong(px+1,py-1,pz),
                BlockPos.asLong(px-1,py+1,pz), BlockPos.asLong(px-1,py-1,pz),
                BlockPos.asLong(px+1,py,pz+1), BlockPos.asLong(px+1,py,pz-1),
                BlockPos.asLong(px-1,py,pz+1), BlockPos.asLong(px-1,py,pz-1),
                BlockPos.asLong(px,py+1,pz+1), BlockPos.asLong(px,py+1,pz-1),
                BlockPos.asLong(px,py-1,pz+1), BlockPos.asLong(px,py-1,pz-1)
            };
            for (final long neighborPlot : neighbors) {
                if (!plotToWorld.containsKey(neighborPlot)) continue;
                final long wB = plotToWorld.get(neighborPlot);
                final int bx = BlockPos.getX(wB), by = BlockPos.getY(wB), bz = BlockPos.getZ(wB);
                // If world cells are not adjacent (Manhattan distance > 1), fill all cells along the line
                final int dist = Math.abs(ax-bx) + Math.abs(ay-by) + Math.abs(az-bz);
                if (dist > 1) {
                    final int steps = Math.max(Math.max(Math.abs(ax-bx), Math.abs(ay-by)), Math.abs(az-bz));
                    for (int s = 1; s < steps; s++) {
                        opaque.add(BlockPos.asLong(
                                ax + (bx-ax) * s / steps,
                                ay + (by-ay) * s / steps,
                                az + (bz-az) * s / steps));
                    }
                }
            }
        }

        // Project shapes
        final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap shapes = new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap();
        shapes.defaultReturnValue((byte) 0);
        for (int i = 0; i < cache.shapePositions.length; i++) {
            worldPos.set(
                    BlockPos.getX(cache.shapePositions[i]) + 0.5,
                    BlockPos.getY(cache.shapePositions[i]) + 0.5,
                    BlockPos.getZ(cache.shapePositions[i]) + 0.5);
            pose.transformPosition(worldPos);
            final long packed = BlockPos.asLong((int) Math.floor(worldPos.x), (int) Math.floor(worldPos.y), (int) Math.floor(worldPos.z));
            shapes.put(packed, cache.shapeMasks[i]);
        }

        cachedPlotSources.put(id, emitters);
        perSubLevelOpaque.put(id, opaque);
        perSubLevelShapes.put(id, shapes);
    }

    /**
     * Enqueues light injection work onto the light thread.
     */
    private static void enqueueReinject(final ServerLevel level, final UUID id) {
        final LevelLightEngine lightEngine = level.getLightEngine();
        if (!(lightEngine instanceof final ThreadedLevelLightEngine threadedEngine)) return;
        if (threadedEngine.blockEngine == null) return;

        final Long2IntOpenHashMap sources = cachedPlotSources.get(id);
        final LongSet oldPositions = injectedPositions.remove(id);

        threadedEngine.taskMailbox.tell(() -> {
            if (threadedEngine.blockEngine == null) return;

            final LongSet sectionsToUpdate = new LongOpenHashSet();

            // Clear old injected light
            if (oldPositions != null && !oldPositions.isEmpty()) {
                for (final long packed : oldPositions) {
                    threadedEngine.blockEngine.checkBlock(new BlockPos(
                            BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed)));
                    addNeighborSections(sectionsToUpdate, packed);
                }
                threadedEngine.blockEngine.runLightUpdates();
            }

            // Inject new emitter positions
            final LongSet newPositions = new LongOpenHashSet();
            if (sources != null && !sources.isEmpty()) {
                for (final var entry : sources.long2IntEntrySet()) {
                    final long packed = entry.getLongKey();
                    final int emission = entry.getIntValue();
                    try {
                        threadedEngine.blockEngine.storage.setStoredLevel(packed, emission);
                        threadedEngine.blockEngine.enqueueIncrease(
                                packed, LightEngine.QueueEntry.increaseLightFromEmission(emission, true));
                        newPositions.add(packed);
                        addNeighborSections(sectionsToUpdate, packed);
                    } catch (final NullPointerException ignored) {}
                }
            }

            if (!newPositions.isEmpty()) {
                injectedPositions.put(id, newPositions);
            }

            if (!sectionsToUpdate.isEmpty()) {
                threadedEngine.blockEngine.runLightUpdates();
                threadedEngine.blockEngine.storage.swapSectionMap();
                level.getServer().execute(() -> sendWorldLightUpdates(level, sectionsToUpdate));
            }
        });

        threadedEngine.tryScheduleUpdate();
    }

    private static void addNeighborSections(final LongSet out, final long packed) {
        final int sx = SectionPos.blockToSectionCoord(BlockPos.getX(packed));
        final int sy = SectionPos.blockToSectionCoord(BlockPos.getY(packed));
        final int sz = SectionPos.blockToSectionCoord(BlockPos.getZ(packed));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    out.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                }
            }
        }
    }

    private static void sendWorldLightUpdates(final ServerLevel level, final LongSet sections) {
        if (sections.isEmpty()) return;

        final LongSet sentChunks = new LongOpenHashSet();
        for (final long sec : sections) {
            final int sx = SectionPos.x(sec);
            final int sz = SectionPos.z(sec);
            final long chunkKey = ChunkPos.asLong(sx, sz);
            if (!sentChunks.add(chunkKey)) continue;

            final ChunkPos chunkPos = new ChunkPos(sx, sz);
            if (level.getChunkSource().getChunkNow(sx, sz) == null) continue;

            for (final ServerPlayer player : level.players()) {
                if (player.getChunkTrackingView().contains(chunkPos)) {
                    player.connection.send(new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(
                            chunkPos, level.getLightEngine(), null, null));
                }
            }
        }
    }
}
