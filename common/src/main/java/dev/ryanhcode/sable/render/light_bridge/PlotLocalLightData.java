package dev.ryanhcode.sable.render.light_bridge;

/**
 * Cached plot-local block data for a sub-level's light-relevant blocks.
 * Populated once on block change, re-projected to world space on movement.
 */
public final class PlotLocalLightData {

    /** Plot-local packed positions of fully opaque blocks (canOcclude && !useShapeForLightOcclusion). */
    public final long[] opaquePositions;

    /** Plot-local packed positions of light-emitting blocks. */
    public final long[] emitterPositions;

    /** Emission level (1-15) parallel to emitterPositions. */
    public final byte[] emitterLevels;

    /** Plot-local packed positions of shape-occluding blocks (useShapeForLightOcclusion). */
    public final long[] shapePositions;

    /** Face occlusion mask (6 bits) parallel to shapePositions. */
    public final byte[] shapeMasks;

    public PlotLocalLightData(final long[] opaquePositions, final long[] emitterPositions, final byte[] emitterLevels, final long[] shapePositions, final byte[] shapeMasks) {
        this.opaquePositions = opaquePositions;
        this.emitterPositions = emitterPositions;
        this.emitterLevels = emitterLevels;
        this.shapePositions = shapePositions;
        this.shapeMasks = shapeMasks;
    }

    public static final PlotLocalLightData EMPTY = new PlotLocalLightData(new long[0], new long[0], new byte[0], new long[0], new byte[0]);
}
