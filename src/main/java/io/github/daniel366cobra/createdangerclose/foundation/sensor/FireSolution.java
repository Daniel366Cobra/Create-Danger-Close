package io.github.daniel366cobra.createdangerclose.foundation.sensor;

/**
 * A ship-frame aiming command for one mount.
 *
 * @param pitchRateDegPerTick for servo feed-forward
 * @param yawRateDegPerTick   for servo feed-forward
 * @param fuzeTicks           timed fuze setting; 0 leaves it
 * @param impactDelayTicks    delayed impact or inertia fuze setting; -1 leaves it
 */
public record FireSolution(
        double pitchDeg,
        double yawDeg,
        double pitchRateDegPerTick,
        double yawRateDegPerTick,
        int fuzeTicks,
        int impactDelayTicks,
        boolean valid
)

{
    public static final FireSolution NONE = new FireSolution(0, 0, 0, false);

    /** Stationary, setting no fuze delay. */
    public FireSolution(double pitchDeg, double yawDeg, int fuzeTicks, boolean valid) {
        this(pitchDeg, yawDeg, fuzeTicks, -1, valid);
    }

    /** Stationary. */
    public FireSolution(double pitchDeg, double yawDeg, int fuzeTicks, int impactDelayTicks, boolean valid) {
        this(pitchDeg, yawDeg, 0, 0, fuzeTicks, impactDelayTicks, valid);
    }

    public FireSolution withRates(double pitchRateDegPerTick, double yawRateDegPerTick) {
        return new FireSolution(pitchDeg, yawDeg, pitchRateDegPerTick, yawRateDegPerTick, fuzeTicks,
                impactDelayTicks, valid);
    }
}