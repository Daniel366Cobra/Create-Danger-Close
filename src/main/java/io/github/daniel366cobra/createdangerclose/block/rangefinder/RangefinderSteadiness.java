package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Marker separation (0 = merged, 1 = apart). Merges at a constant rate while the sight is steady,
 * judged by jitter against a smoothed motion in world space (a smooth pan is steady; a rolling ship
 * is not). Runs identically on server and client.
 */
public class RangefinderSteadiness {

    // The reticle's first small tick (31 of 124 texels): below it, range readings...
    public static final float RANGING_SEPARATION = 31f / 124f;
    // ...and below half, velocity.
    public static final float VELOCITY_SEPARATION = RANGING_SEPARATION / 2;

    // Degrees per tick.
    private static final double JITTER_THRESHOLD_DEG = 0.1;
    private static final double SMOOTHING = 0.15;
    // Fully in 2 s of steady sight; apart in 0.5 s.
    private static final float CONVERGE_PER_TICK = 1f / 40;
    private static final float DIVERGE_PER_TICK = 1f / 10;

    @Nullable private Vec3 previousDirection;
    private Vec3 smoothedMotion = Vec3.ZERO;
    private float separation = 1f;
    // For per-frame interpolation.
    private float previousSeparation = 1f;

    /**
     * @param direction    world-space unit vector
     * @param tubeOnTarget if not, the images cannot coincide
     */
    public float update(Vec3 direction, boolean tubeOnTarget) {
        previousSeparation = separation;
        boolean steady = tubeOnTarget;
        if (previousDirection != null) {
            // For small angles the cross product is the angular motion.
            Vec3 motion = previousDirection.cross(direction);
            smoothedMotion = smoothedMotion.lerp(motion, SMOOTHING);
            double jitterDeg = Math.toDegrees(motion.subtract(smoothedMotion).length());
            steady &= jitterDeg < JITTER_THRESHOLD_DEG;
        }
        previousDirection = direction;
        separation = Mth.clamp(separation + (steady ? -CONVERGE_PER_TICK : DIVERGE_PER_TICK), 0f, 1f);
        return separation;
    }

    public void reset() {
        previousDirection = null;
        smoothedMotion = Vec3.ZERO;
        separation = previousSeparation = 1f;
    }

    public float getSeparation() {
        return separation;
    }

    public float getSeparation(float partialTick) {
        return Mth.lerp(partialTick, previousSeparation, separation);
    }

    public boolean canRange() {
        return separation <= RANGING_SEPARATION;
    }

    public boolean canTrackVelocity() {
        return separation <= VELOCITY_SEPARATION;
    }
}