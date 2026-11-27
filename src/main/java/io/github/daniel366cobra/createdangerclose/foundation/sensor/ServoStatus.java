package io.github.daniel366cobra.createdangerclose.foundation.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @param mounts the one cannon mount driven, for a mount axis
 * @param swivel for {@link ServoAxis#SWIVEL}; else null
 * @param limits as set on the dial
 * @param supply what the munitions chutes on the cannon side can stage: shell type to the strongest
 *               filled cartridge power pairable with it; empty for a hand-loaded gun
 * @param chuteFed whether any munitions chute rides the cannon side, stocked or not: such a gun is
 *                 already supplied, so no battle-network chute claims it
 */
public record ServoStatus(
        BlockPos servoPos,
        ServoAxis axis,
        boolean powered,
        List<MountSnapshot> mounts,
        @Nullable SwivelSnapshot swivel,
        ServoLimits limits,
        Map<ResourceLocation, Float> supply,
        boolean chuteFed
) {
    public ServoStatus(BlockPos servoPos, ServoAxis axis, boolean powered, List<MountSnapshot> mounts,
                       @Nullable SwivelSnapshot swivel, ServoLimits limits) {
        this(servoPos, axis, powered, mounts, swivel, limits, Map.of(), false);
    }

    public record MountSnapshot(
            BlockPos mountPos,
            float facingYaw,
            // Pitch: barrel elevation. Yaw: the mount's yaw.
            float currentAngle,
            // CBC's, whichever axis the servo drives.
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
            // Effective muzzle speed, blocks/tick (Going Ballistic's, when that mod computes it; 0: use the charge power).
            float muzzleVelocity,
            // The cannon's rotation center (level coordinates; null if unassembled) and the shell's spawn distance along the barrel.
            @Nullable Vec3 pivot,
            double muzzleDistance,
            // Unit vector in the cannon's own frame; null if unassembled.
            @Nullable Vec3 barrel,
            // Rounds expire after this many ticks (0: no limit).
            boolean autocannon,
            int maxFlightTicks
    ) {}

    /**
     * @param plate       the sub-level its plate carries, or null if unassembled
     * @param hull        the sub-level it stands on, or null in the world
     * @param facing      in its hull's frame; the angle turns the plate right-handed about it
     * @param targetAngle degrees, as its cog has wound in
     * @param actualAngle degrees, physically following the target
     */
    public record SwivelSnapshot(
            BlockPos swivelPos,
            @Nullable UUID plate,
            @Nullable UUID hull,
            Direction facing,
            // Tilting a cradle: which way its angle raises the barrels (+1 or -1).
            int elevationSense,
            double targetAngle,
            double actualAngle
    ) {}
}