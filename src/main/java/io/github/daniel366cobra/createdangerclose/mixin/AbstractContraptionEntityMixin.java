package io.github.daniel366cobra.createdangerclose.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Seats the rangefinder operator at a custom spot on the eyepiece. */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class AbstractContraptionEntityMixin {

    @Shadow
    protected Contraption contraption;

    /** The seat's local position before conversion to world. Ordinal 1: the seated-passenger branch. */
    @ModifyArg(method = "getPassengerPosition",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;toGlobalVector(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;",
                    ordinal = 1),
            index = 0)
    private Vec3 createdangerclose$offsetOperatorSeat(Vec3 localSeatPos, @Local(argsOnly = true) Entity passenger) {
        if (!(contraption instanceof RangefinderContraption rc))
            return localSeatPos;
        BlockPos eyepiece = rc.getEyepiecePos();
        if (eyepiece == null || !eyepiece.equals(rc.getSeatOf(passenger.getUUID())))
            return localSeatPos;
        return localSeatPos.add(rc.getOperatorSeatOffset());
    }
}