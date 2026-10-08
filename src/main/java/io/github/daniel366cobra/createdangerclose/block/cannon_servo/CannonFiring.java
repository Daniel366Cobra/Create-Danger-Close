package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannon_control.fixed_cannon_mount.FixedCannonMountBlockEntity;

/** Fires CBC cannons on command, as a redstone pulse on the mount would. */
public final class CannonFiring {

    private CannonFiring() {}

    /**
     * CBC handles empty or unprimed cannons itself.
     *
     * @return false without an assembled cannon
     */
    public static boolean fire(ServerLevel level, BlockPos mountPos) {
        PitchOrientedContraptionEntity cannonEntity = switch (level.getBlockEntity(mountPos)) {
            case CannonMountBlockEntity mount -> mount.getContraption();
            case FixedCannonMountBlockEntity mount -> mount.getContraption();
            case null, default -> null;
        };
        if (cannonEntity == null || !(cannonEntity.getContraption() instanceof AbstractMountedCannonContraption cannon))
            return false;
        cannon.fireShot(level, cannonEntity);
        return true;
    }

    /** The strongest redstone signal. */
    public static final int AUTOCANNON_FULL_RATE = 15;

    /**
     * As a redstone signal on the mount: the breech fires by itself at that rate while it has rounds; 0 ceases fire.
     *
     * @return false without an assembled autocannon
     */
    public static boolean setAutocannonFireRate(ServerLevel level, BlockPos mountPos, int rate) {
        BlockEntity be = level.getBlockEntity(mountPos);
        PitchOrientedContraptionEntity cannonEntity = switch (be) {
            case CannonMountBlockEntity mount -> mount.getContraption();
            case FixedCannonMountBlockEntity mount -> mount.getContraption();
            case null, default -> null;
        };
        if (cannonEntity == null || !(be instanceof ControlPitchContraption controller)
                || !(cannonEntity.getContraption() instanceof MountedAutocannonContraption autocannon))
            return false;
        autocannon.onRedstoneUpdate(level, cannonEntity, rate > 0, rate, controller);
        return true;
    }
}