package io.github.daniel366cobra.createdangerclose.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Seats the rangefinder operator at a custom spot on the eyepiece, and steps them off beside it. */
@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class AbstractContraptionEntityMixin {

    @Shadow
    protected Contraption contraption;

    // Both dismount hooks are optional: if Create changes, operators get off where Create puts them.
    // Last seen at the eyepiece: on the client, the seat mapping is cleared before the passenger leaves.
    @Unique
    @Nullable
    private UUID createdangerclose$seatedOperator;
    // Leaving the eyepiece: the seat mapping is gone by the time the dismount spot is asked for.
    @Unique
    @Nullable
    private UUID createdangerclose$leavingOperator;

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
        createdangerclose$seatedOperator = passenger.getUUID();
        return localSeatPos.add(rc.getOperatorSeatOffset());
    }

    @Inject(method = "removePassenger", at = @At("HEAD"), require = 0)
    private void createdangerclose$noteLeavingOperator(Entity passenger, CallbackInfo ci) {
        if (!(contraption instanceof RangefinderContraption rc) || rc.getEyepiecePos() == null)
            return;
        BlockPos seat = rc.getSeatOf(passenger.getUUID());
        if (rc.getEyepiecePos().equals(seat)
                || seat == null && passenger.getUUID().equals(createdangerclose$seatedOperator))
            createdangerclose$leavingOperator = passenger.getUUID();
        if (passenger.getUUID().equals(createdangerclose$seatedOperator))
            createdangerclose$seatedOperator = null;
    }

    /**
     * Create's spot is the seat on the server, and the top of the contraption on the client; collision
     * pushes the operator out of the tube, or the client puts them on top of it.
     */
    @Inject(method = "getDismountLocationForPassenger", at = @At("RETURN"), cancellable = true, require = 0)
    private void createdangerclose$operatorDismount(LivingEntity passenger, CallbackInfoReturnable<Vec3> cir) {
        if (!passenger.getUUID().equals(createdangerclose$leavingOperator))
            return;
        createdangerclose$leavingOperator = null;
        if (!(contraption instanceof RangefinderContraption rc))
            return;
        Vec3 beside = rc.findOperatorDismount((AbstractContraptionEntity) (Object) this, passenger);
        if (beside != null)
            cir.setReturnValue(beside);
    }
}