package io.github.daniel366cobra.createdangerclose.api.sensor;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

public interface SolverServo {
    /** Called by the solver to read the servo's current state. */
    ServoStatus getServoStatus();

    /** Called by the solver to push a computed solution. null clears the command. */
    void receiveFiringSolution(@Nullable FireSolution solution);
}
