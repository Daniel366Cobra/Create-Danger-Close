package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

/** Firing a CBC cannon mount on command, as a redstone pulse on the mount would. */
public final class CannonFiring {

    private CannonFiring() {}

    /**
     * Fires the cannon on the mount at this position, with whatever it has
     * loaded (CBC handles empty or unprimed cannons itself).
     *
     * @return false if there is no assembled cannon there
     */
    public static boolean fire(ServerLevel level, BlockPos mountPos) {
        if (!(level.getBlockEntity(mountPos) instanceof CannonMountBlockEntity mount))
            return false;
        PitchOrientedContraptionEntity cannonEntity = mount.getContraption();
        if (cannonEntity == null || !(cannonEntity.getContraption() instanceof AbstractMountedCannonContraption cannon))
            return false;
        cannon.fireShot(level, cannonEntity);
        return true;
    }
}
