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

/** No view turning with the tube ("rotateWhenSeated"): the tube follows the look, so it would feed back. */
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