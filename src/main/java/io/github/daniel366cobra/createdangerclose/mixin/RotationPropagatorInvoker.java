package io.github.daniel366cobra.createdangerclose.mixin;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Create's connection rules, so a servo can trace its drive train while it stands still. */
@Mixin(value = RotationPropagator.class, remap = false)
public interface RotationPropagatorInvoker {

    @Invoker("getConnectedNeighbours")
    static List<KineticBlockEntity> createdangerclose$getConnectedNeighbours(KineticBlockEntity be) {
        throw new AssertionError();
    }

    @Invoker("getRotationSpeedModifier")
    static float createdangerclose$getRotationSpeedModifier(KineticBlockEntity from, KineticBlockEntity to) {
        throw new AssertionError();
    }

    @Invoker("isLargeCogToSpeedController")
    static boolean createdangerclose$isLargeCogToSpeedController(BlockState from, BlockState to, BlockPos diff) {
        throw new AssertionError();
    }
}