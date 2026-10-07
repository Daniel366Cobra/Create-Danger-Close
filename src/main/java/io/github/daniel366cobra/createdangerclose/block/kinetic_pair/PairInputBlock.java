package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The input half of a two-block kinetic pair, the half that is placed: its shaft faces
 * back into the input network, and its output half stands in front of it. It places the
 * output half itself, and takes it along when removed.
 */
public abstract class PairInputBlock<BE extends PairInputBlockEntity> extends DirectionalKineticBlock implements IBE<BE> {

    protected PairInputBlock(Properties properties) {
        super(properties);
    }

    /** The block of its output half. */
    protected abstract Block outputBlock();

    private BlockState outputHalf(Direction facing) {
        return outputBlock().defaultBlockState().setValue(FACING, facing);
    }

    private BlockState getBaseStateForPlacement(BlockPlaceContext context) {
        Direction preferred = getPreferredFacing(context);
        boolean sneaking = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        if (preferred == null || sneaking) {
            Direction looking = context.getNearestLookingDirection();
            return defaultBlockState().setValue(FACING, sneaking ? looking.getOpposite() : looking);
        }
        return defaultBlockState().setValue(FACING, preferred.getOpposite());
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = getBaseStateForPlacement(context);
        BlockPos outputPos = context.getClickedPos().relative(state.getValue(FACING));
        // No room for the output half: not placed (the item shows where it would go).
        return context.getLevel().getBlockState(outputPos).canBeReplaced() ? state : null;
    }

    /** Which way it would face if placed now. */
    public Direction getDirectionForPlacement(BlockPlaceContext context) {
        return getBaseStateForPlacement(context).getValue(FACING);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);
        if (!level.getBlockTicks().hasScheduledTick(pos, this))
            level.scheduleTick(pos, this, 1);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!newState.is(this)) {
            Direction facing = state.getValue(FACING);
            BlockPos outputPos = pos.relative(facing);
            if (level.getBlockState(outputPos).equals(outputHalf(facing)))
                level.destroyBlock(outputPos, false);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** Puts its output half in front of it, or breaks if something else is there. */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Direction facing = state.getValue(FACING);
        BlockPos outputPos = pos.relative(facing);
        BlockState occupied = level.getBlockState(outputPos);
        BlockState required = outputHalf(facing);
        if (occupied.equals(required))
            return;
        if (!occupied.canBeReplaced()) {
            level.destroyBlock(pos, true);
            return;
        }
        level.setBlockAndUpdate(outputPos, required);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING).getOpposite();
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }
}
