package io.github.daniel366cobra.createdangerclose.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/** A block with one face that turns only in yaw; faces the player when placed, away when sneaking. */
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

    /** Top or bottom face turns it clockwise; sides do nothing, so it never tips. */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        if (targetedFace.getAxis() != Direction.Axis.Y)
            return originalState;
        return originalState.setValue(FACING, originalState.getValue(FACING).getClockWise());
    }
}