package io.github.daniel366cobra.createdangerclose.api.sensor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record ServoStatus(
        BlockPos servoPos,
        ServoAxis axis,
        boolean powered,
        List<MountSnapshot> mounts
) {
    public record MountSnapshot(
            BlockPos mountPos,
            float facingYaw,
            float currentAngle,
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
            double muzzleDistance
    ) {}
}
