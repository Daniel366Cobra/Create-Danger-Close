package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A rangefinder optics block (lens, eyepiece). Like a lectern, it only turns
 * in yaw: it looks along {@link #FACING} and never tips over.
 */
public abstract class RangefinderOpticBlock extends HorizontalDirectionalBlock implements IWrenchable {

    protected RangefinderOpticBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Looks the way the player is looking when placing it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
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
