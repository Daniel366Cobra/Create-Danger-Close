package io.github.daniel366cobra.createdangerclose.api.sensor;

/**
 * A ship-frame aiming command for one mount.
 *
 * @param pitchRateDegPerTick how fast the commanded pitch is changing, for servo feed-forward
 * @param yawRateDegPerTick   how fast the commanded yaw is changing, for servo feed-forward
 * @param fuzeTicks           what to set a timed fuze to (time of flight plus correction); 0 leaves it
 * @param impactDelayTicks    what to set a delayed impact or inertia fuze's delay to; -1 leaves it
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

    /** A stationary command that sets no fuzes: the angles are not expected to change. */
    public FireSolution(double pitchDeg, double yawDeg, int fuzeTicks, boolean valid) {
        this(pitchDeg, yawDeg, fuzeTicks, -1, valid);
    }

    /** A stationary command: the angles are not expected to change. */
    public FireSolution(double pitchDeg, double yawDeg, int fuzeTicks, int impactDelayTicks, boolean valid) {
        this(pitchDeg, yawDeg, 0, 0, fuzeTicks, impactDelayTicks, valid);
    }

    public FireSolution withRates(double pitchRateDegPerTick, double yawRateDegPerTick) {
        return new FireSolution(pitchDeg, yawDeg, pitchRateDegPerTick, yawRateDegPerTick, fuzeTicks,
                impactDelayTicks, valid);
    }
}
