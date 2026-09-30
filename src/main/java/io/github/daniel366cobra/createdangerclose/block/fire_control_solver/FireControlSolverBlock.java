package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.block.WrenchableDirectionalBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Ballistic Computer: the core of the fire control computer. It has no
 * shaft of its own; a Battle Network Interface facing it connects it to the
 * battle network and drives it. (The registry name is still
 * {@code fire_control_solver}, from when it was a single kinetic block.)
 */
public class FireControlSolverBlock extends WrenchableDirectionalBlock implements IBE<FireControlSolverBlockEntity> {

    public FireControlSolverBlock(Properties properties) {
        super(properties);
    }

    /** Like Create's directional blocks: faces the player placing it, or away when they sneak. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction looking = context.getNearestLookingDirection();
        boolean sneaking = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        return defaultBlockState().setValue(FACING, sneaking ? looking : looking.getOpposite());
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
