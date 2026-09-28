package io.github.daniel366cobra.createdangerclose.mixin.client;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.seat.ContraptionPlayerPassengerRotation;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create turns a seated player's view along with a rotating contraption
 * (client config "rotateWhenSeated"). On a rangefinder tube the operator's
 * look is what the tube follows, so turning the look with the tube would feed
 * back into itself and make the aim behave like a rate joystick.
 */
@Mixin(value = ContraptionPlayerPassengerRotation.class, remap = false)
public abstract class ContraptionPlayerPassengerRotationMixin {

    @Inject(method = "frame", at = @At("HEAD"), cancellable = true)
    private static void createdangerclose$keepRangefinderOperatorLook(CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player != null
                && player.getVehicle() instanceof AbstractContraptionEntity contraptionEntity
                && contraptionEntity.getContraption() instanceof RangefinderContraption) {
            ci.cancel();
        }
    }
}
