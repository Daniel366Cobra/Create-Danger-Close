package io.github.daniel366cobra.createdangerclose.api.sensor;

/**
 * A ship-frame aiming command for one mount.
 *
 * @param pitchRateDegPerTick how fast the commanded pitch is changing, for servo feed-forward
 * @param yawRateDegPerTick   how fast the commanded yaw is changing, for servo feed-forward
 */
public record FireSolution(
        double pitchDeg,
        double yawDeg,
        double pitchRateDegPerTick,
        double yawRateDegPerTick,
        int fuzeTicks,
        boolean valid
)

{
    public static final FireSolution NONE = new FireSolution(0, 0, 0, false);

    /** A stationary command: the angles are not expected to change. */
    public FireSolution(double pitchDeg, double yawDeg, int fuzeTicks, boolean valid) {
        this(pitchDeg, yawDeg, 0, 0, fuzeTicks, valid);
    }

    public FireSolution withRates(double pitchRateDegPerTick, double yawRateDegPerTick) {
        return new FireSolution(pitchDeg, yawDeg, pitchRateDegPerTick, yawRateDegPerTick, fuzeTicks, valid);
    }
}
