package io.github.daniel366cobra.createdangerclose.mixin.client;

import io.github.daniel366cobra.createdangerclose.client.rangefinder.RangefinderSight;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clamps the operator's pitch to the optics' range; the server takes the rotation from the client. */
@Mixin(value = Entity.class, remap = false)
public abstract class EntityTurnMixin {

    @Inject(method = "turn", at = @At("TAIL"))
    private void createdangerclose$clampOperatorPitch(double yRot, double xRot, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player || !RangefinderSight.isOperating())
            return;
        float min = -RangefinderSight.MAX_ELEVATION_DEG;
        float max = RangefinderSight.MAX_DEPRESSION_DEG;
        self.setXRot(Mth.clamp(self.getXRot(), min, max));
        self.xRotO = Mth.clamp(self.xRotO, min, max);
    }
}