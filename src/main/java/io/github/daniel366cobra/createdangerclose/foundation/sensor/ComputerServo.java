package io.github.daniel366cobra.createdangerclose.foundation.sensor;

import org.jetbrains.annotations.Nullable;

public interface ComputerServo {
    /** Called by the Ballistic Computer to read the servo's current state. */
    ServoStatus getServoStatus();

    /** Called by the Ballistic Computer to push a computed solution. null clears the command. */
    void receiveFiringSolution(@Nullable FireSolution solution);

    /**
     * For a servo turning a swivel bearing that tilts a cradle: which way its angle
     * raises the barrels (+1 or -1), as the computer sees from the gun it lays. The
     * servo's dial reads elevation with it.
     */
    default void receiveElevationSense(int sense) {}
}
