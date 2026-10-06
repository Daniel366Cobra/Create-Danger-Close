package io.github.daniel366cobra.createdangerclose.mixin;

import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonReading;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.FuzeOrderedBreech;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;

/**
 * Sets each round's fuze as the breech takes it to fire, to what the fire control last
 * ordered (see {@link FuzeOrderedBreech}).
 */
@Mixin(value = AbstractAutocannonBreechBlockEntity.class, remap = false)
public abstract class AutocannonBreechMixin implements FuzeOrderedBreech {

    // An order not renewed this long (renewed every tick a servo lays the gun, every poll for a fixed gun) lapses.
    @Unique
    private static final int createdangerclose$ORDER_LIFETIME_TICKS = 40;

    @Unique
    private int createdangerclose$timedTicks;
    @Unique
    private int createdangerclose$delayTicks = -1;
    @Unique
    private long createdangerclose$orderTick = Long.MIN_VALUE / 2;

    @Override
    public void createdangerclose$orderFuzes(int timedTicks, int delayTicks, long gameTime) {
        createdangerclose$timedTicks = timedTicks;
        createdangerclose$delayTicks = delayTicks;
        createdangerclose$orderTick = gameTime;
    }

    @Inject(method = "extractNextInput", at = @At("RETURN"))
    private void createdangerclose$setFuze(CallbackInfoReturnable<ItemStack> cir) {
        var level = ((BlockEntity) (Object) this).getLevel();
        // A contraption's block entities may have no level: then the order stands as given.
        if (level != null && level.getGameTime() - createdangerclose$orderTick > createdangerclose$ORDER_LIFETIME_TICKS)
            return;
        CannonReading.applyFuzeOrder(cir.getReturnValue(), createdangerclose$timedTicks, createdangerclose$delayTicks);
    }
}
