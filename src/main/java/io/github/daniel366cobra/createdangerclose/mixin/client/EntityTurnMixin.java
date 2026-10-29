package io.github.daniel366cobra.createdangerclose.mixin.client;

import io.github.daniel366cobra.createdangerclose.client.rangefinder.RangefinderSight;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the operator's look on the tube (and pitch within the optics' range); the server takes the
 * rotation from the client.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class EntityTurnMixin {

    // Entity.turn's mouse scale.
    private static final float TURN_SCALE = 0.15f;

    @Inject(method = "turn", at = @At("TAIL"))
    private void createdangerclose$clampOperatorLook(double yRot, double xRot, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player || !RangefinderSight.isOperating())
            return;
        float partialTicks = AnimationTickHolder.getPartialTicks();
        float yaw = self.getYRot();
        float pitch = self.getXRot();
        float clampedYaw = RangefinderSight.clampYaw(yaw, yaw - (float) yRot * TURN_SCALE, partialTicks);
        float clampedPitch = RangefinderSight.clampPitch(pitch, pitch - (float) xRot * TURN_SCALE, partialTicks);
        self.setYRot(clampedYaw);
        self.setXRot(clampedPitch);
        // Moved by the same amount, so the frame's interpolation doesn't jump.
        self.yRotO += clampedYaw - yaw;
        self.xRotO += clampedPitch - pitch;
    }
}