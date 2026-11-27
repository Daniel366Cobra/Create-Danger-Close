package io.github.daniel366cobra.createdangerclose.foundation.sensor;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public interface ComputerServo {
    /** Called by the Ballistic Computer to read the servo's current state. */
    ServoStatus getServoStatus();

    /** Called by the Ballistic Computer to push a computed solution. null clears the command. */
    void receiveFiringSolution(@Nullable FireSolution solution);

    /** For a swivel tilting a cradle: which way its angle raises the barrels (+1 or -1); its dial reads elevation by it. */
    default void receiveElevationSense(int sense) {}

    /** The name players gave it, shown for the guns it lays; empty if none. */
    default String getServoName() {
        return "";
    }

    /** Its guns show another name, set closer to them (null: its own, or no guns). */
    default void receiveShownName(@Nullable String shownAs) {}

    /** The shell type the computer's current aim calls for from this servo's gun; null for whatever is loaded. */
    default void offerShellRequest(@Nullable ResourceLocation shell, int rounds, int missionSerial) {}
}