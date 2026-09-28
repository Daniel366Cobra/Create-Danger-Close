package io.github.daniel366cobra.createdangerclose.api.sensor;

public record FireSolution(
        double pitchDeg,
        double yawDeg,
        int fuzeTicks,
        boolean valid
)

{
    public static final FireSolution NONE = new FireSolution(0, 0, 0, false);
}