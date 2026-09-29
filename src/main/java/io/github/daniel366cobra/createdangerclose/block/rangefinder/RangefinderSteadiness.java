package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Coincidence of the two rangefinder images, as the separation of the
 * reticle markers: 0 = merged, 1 = fully apart.
 *
 * <p>The markers merge at a constant rate while the sight is held steady and
 * drift apart while it is not. "Steady" is judged by jitter: how far the line
 * of sight's angular motion strays from a smoothed version of itself. A smooth
 * pan following a crossing target is steady; shaky corrections are not. The
 * motion is measured in world space, so a rolling ship disturbs it too.
 *
 * <p>Run identically on the server (authoritative, for measurements) and on
 * the client (for the markers).
 */
public class RangefinderSteadiness {

    // Marker offset at the reticle's first small tick (31 of 124 texels):
    // below this the rangefinder produces range readings...
    public static final float RANGING_SEPARATION = 31f / 124f;
    // ...and below half of it, velocity.
    public static final float VELOCITY_SEPARATION = RANGING_SEPARATION / 2;

    // Jitter below this counts as steady, degrees per tick.
    private static final double JITTER_THRESHOLD_DEG = 0.1;
    // How much of each tick's motion goes into the smoothed motion.
    private static final double SMOOTHING = 0.15;
    // Merge fully in 2 s of steady sight; drift apart in 0.5 s.
    private static final float CONVERGE_PER_TICK = 1f / 40;
    private static final float DIVERGE_PER_TICK = 1f / 10;

    @Nullable private Vec3 previousDirection;
    private Vec3 smoothedMotion = Vec3.ZERO;
    private float separation = 1f;
    // Separation before the latest update, for smooth per-frame rendering.
    private float previousSeparation = 1f;

    /**
     * @param direction   world-space line of sight (unit vector)
     * @param tubeOnTarget whether the tube has caught up with the operator's look;
     *                    if not, the images cannot coincide
     */
    public float update(Vec3 direction, boolean tubeOnTarget) {
        previousSeparation = separation;
        boolean steady = tubeOnTarget;
        if (previousDirection != null) {
            // For small angles, the cross product is the angular motion (radians per tick).
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

    /** Separation interpolated between the last two updates, for rendering. */
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
