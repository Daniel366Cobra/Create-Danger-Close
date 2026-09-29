package io.github.daniel366cobra.createdangerclose.mixin.client;

import io.github.daniel366cobra.createdangerclose.client.rangefinder.RangefinderSight;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Looks through the rangefinder's objectives: in first person, the operator's
 * camera is moved from their head to just in front of the lenses, so the
 * tube's own structure does not block the view. Only the view moves; the
 * player stays in the seat.
 */
@Mixin(value = Camera.class, remap = false)
public abstract class CameraMixin {

    // Sable wraps this to convert ship coordinates to world coordinates.
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("TAIL"))
    private void createdangerclose$lookThroughObjectives(BlockGetter level, Entity entity, boolean detached,
                                                          boolean thirdPersonReverse, float partialTick,
                                                          CallbackInfo ci) {
        if (detached || entity != Minecraft.getInstance().player)
            return;
        Vec3 viewPoint = RangefinderSight.getOperatorCameraPosition(partialTick);
        if (viewPoint != null)
            setPosition(viewPoint);
    }
}
