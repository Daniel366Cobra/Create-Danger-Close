package io.github.daniel366cobra.createdangerclose.api.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * @param mounts the cannon mounts the servo drives (one), for a mount axis
 * @param swivel the swivel bearing the servo drives, for {@link ServoAxis#SWIVEL}; else null
 * @param limits the angles the servo may turn what it drives through (set on its dial)
 */
public record ServoStatus(
        BlockPos servoPos,
        ServoAxis axis,
        boolean powered,
        List<MountSnapshot> mounts,
        @Nullable SwivelSnapshot swivel,
        ServoLimits limits
) {
    public record MountSnapshot(
            BlockPos mountPos,
            float facingYaw,
            // Pitch servo: the barrel's elevation; yaw servo: the mount's yaw.
            float currentAngle,
            // The mount's yaw (CBC's), whichever axis the servo drives.
            float mountYaw,
            float maxElevation,
            float maxDepression,
            boolean hasShell,
            @Nullable ResourceLocation shellType,
            ItemStack fuzeStack,
            int fuzeTimer,
            boolean isTimedFuze,
            double gravity,
            double drag,
            boolean isQuadraticDrag,
            float addedChargePower,
            float minimumChargePower,
            float totalChargePower,
            // Where the shell is launched from: the cannon's rotation centre (level
            // coordinates; null if unassembled) and the distance along the barrel to
            // the point the shell appears at, blocks.
            @Nullable Vec3 pivot,
            double muzzleDistance,
            // Where the barrel points: a unit vector in the frame the cannon stands in
            // (its sub-level's, or the world's); null if unassembled.
            @Nullable Vec3 barrel,
            // An autocannon (fires in bursts; fired from the Autocannon Control Console), and how
            // long its rounds fly before they expire, ticks (0: no limit).
            boolean autocannon,
            int maxFlightTicks
    ) {}

    /**
     * A swivel bearing turning what its plate carries.
     *
     * @param plate       the sub-level its plate carries (a turret, a cradle), or null if unassembled
     * @param hull        the sub-level the bearing itself stands on, or null if it stands in the world
     * @param facing      the way the bearing faces, in its hull's frame: its angle turns the plate
     *                    right-handed about this direction
     * @param targetAngle the angle it is turning to, degrees (what its cog has wound in)
     * @param actualAngle the angle the plate is at now, degrees (it follows the target physically)
     */
    public record SwivelSnapshot(
            BlockPos swivelPos,
            @Nullable UUID plate,
            @Nullable UUID hull,
            Direction facing,
            // For a swivel lying on its side (tilting a cradle): which way its angle raises
            // the barrels, as last found (+1 or -1).
            int elevationSense,
            double targetAngle,
            double actualAngle
    ) {}
}
