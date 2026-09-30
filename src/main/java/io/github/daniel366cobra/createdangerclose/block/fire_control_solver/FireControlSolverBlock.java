package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * The Ballistic Computer: the core of the fire control computer. It has no
 * shaft of its own; a Battle Network Interface facing it (or any module of
 * its bank) connects it to the battle network and drives it. Like the
 * modules, its instrument face only turns in yaw. (The registry name is still
 * {@code fire_control_solver}, from when it was a single kinetic block.)
 */
public class FireControlSolverBlock extends HorizontalFacedBlock implements IBE<FireControlSolverBlockEntity> {

    public static final MapCodec<FireControlSolverBlock> CODEC = simpleCodec(FireControlSolverBlock::new);

    public FireControlSolverBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<FireControlSolverBlockEntity> getBlockEntityClass() {
        return FireControlSolverBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FireControlSolverBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.FIRE_CONTROL_SOLVER.get();
    }
}
