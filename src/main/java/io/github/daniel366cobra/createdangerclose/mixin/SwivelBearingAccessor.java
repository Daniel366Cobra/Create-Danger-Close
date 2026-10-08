package io.github.daniel366cobra.createdangerclose.mixin;

import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a servo set a swivel's set-point exactly; the bearing's joint motor follows it. */
@Mixin(value = SwivelBearingBlockEntity.class, remap = false)
public interface SwivelBearingAccessor {

    @Accessor("targetAngleDegrees")
    void createdangerclose$setTargetAngleDegrees(double degrees);
}