package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Alpha-beta filter over measured world positions: smooths range noise and estimates velocity for lead. */
public class RangeTracker {

    private static final double ALPHA = 0.5;
    private static final double BETA = 0.2;
    // Start over after a pause this long...
    private static final long RESET_GAP_TICKS = 100;
    // ...or a sample this far off the prediction (blocks, or that many sample sigmas if larger).
    private static final double RESET_JUMP_BLOCKS = 32;
    private static final double RESET_JUMP_SIGMAS = 5;

    @Nullable private Vec3 position;
    private Vec3 velocity = Vec3.ZERO; // blocks per tick
    private long lastTick;
    private int samples;

    public void update(Vec3 measured, long tick, double sigma) {
        if (position != null && tick - lastTick <= RESET_GAP_TICKS) {
            double dt = Math.max(1, tick - lastTick);
            Vec3 predicted = position.add(velocity.scale(dt));
            Vec3 residual = measured.subtract(predicted);
            if (residual.length() <= Math.max(RESET_JUMP_BLOCKS, RESET_JUMP_SIGMAS * sigma)) {
                position = predicted.add(residual.scale(ALPHA));
                velocity = velocity.add(residual.scale(BETA / dt));
                samples++;
                lastTick = tick;
                return;
            }
        }
        position = measured;
        velocity = Vec3.ZERO;
        samples = 1;
        lastTick = tick;
    }

    public void reset() {
        position = null;
        velocity = Vec3.ZERO;
        samples = 0;
    }

    @Nullable
    public Vec3 getPosition() {
        return position;
    }

    /** World space. */
    public Vec3 getVelocity() {
        return velocity;
    }

    public int getSamples() {
        return samples;
    }
}