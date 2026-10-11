package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Marker separation (0 = merged, 1 = apart). Merges at a constant rate while the sight is steady,
 * judged by jitter against a smoothed motion in world space (a smooth pan is steady; a rolling ship
 * is not); a little over the allowance holds, well over it parts them. Runs identically on server
 * and client (the tolerance is synced server config).
 */
public class RangefinderSteadiness {

    // The reticle's first small tick (31 of 124 texels): below it, range readings...
    public static final float RANGING_SEPARATION = 31f / 124f;
    // ...and below half, velocity.
    public static final float VELOCITY_SEPARATION = RANGING_SEPARATION / 2;

    // Jitter allowed, degrees per tick, times the server's tracking tolerance.
    private static final double JITTER_THRESHOLD_DEG = 0.1;
    // Plus this share of the pan speed: mouse input arrives per frame, so a smooth pan comes in uneven
    // steps per tick (2 or 3 frames apart at 50 fps, 1 or 2 at 30).
    private static final double JITTER_PER_PAN_SPEED = 0.35;
    // Between the allowance and this many times it, the images neither merge nor part.
    private static final double HOLD_FACTOR = 2;
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
        float change = tubeOnTarget ? -CONVERGE_PER_TICK : DIVERGE_PER_TICK;
        if (previousDirection != null) {
            // For small angles the cross product is the angular motion.
            Vec3 motion = previousDirection.cross(direction);
            smoothedMotion = smoothedMotion.lerp(motion, SMOOTHING);
            double jitterDeg = Math.toDegrees(motion.subtract(smoothedMotion).length());
            double allowedDeg = (JITTER_THRESHOLD_DEG + JITTER_PER_PAN_SPEED * Math.toDegrees(smoothedMotion.length()))
                    * CDCConfig.server().rangefinder.trackingTolerance.get().factor;
            if (jitterDeg >= allowedDeg * HOLD_FACTOR)
                change = DIVERGE_PER_TICK;
            else if (jitterDeg >= allowedDeg && change < 0)
                change = 0;
        }
        previousDirection = direction;
        separation = Mth.clamp(separation + change, 0f, 1f);
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