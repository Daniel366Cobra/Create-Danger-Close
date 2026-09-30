package io.github.daniel366cobra.createdangerclose.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A block with one face (a lens, dials, a screen) that only turns in yaw:
 * vanilla's {@link HorizontalDirectionalBlock} plus this mod's placing and
 * wrenching rules. Placed like Create's directional blocks: the face looks at
 * the player, or away from them when they sneak.
 */
public abstract class HorizontalFacedBlock extends HorizontalDirectionalBlock implements IWrenchable {

    protected HorizontalFacedBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction looking = context.getHorizontalDirection();
        boolean sneaking = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        return defaultBlockState().setValue(FACING, sneaking ? looking : looking.getOpposite());
    }

    /**
     * Wrenching the top or bottom face turns it clockwise (seen from above);
     * the side faces do nothing, so it never tips over.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        if (targetedFace.getAxis() != Direction.Axis.Y)
            return originalState;
        return originalState.setValue(FACING, originalState.getValue(FACING).getClockWise());
    }
}
