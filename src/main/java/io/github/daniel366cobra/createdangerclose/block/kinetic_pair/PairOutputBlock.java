package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The output half of a kinetic pair, in front of its input half; breaking it breaks the pair. */
public abstract class PairOutputBlock<BE extends PairOutputBlockEntity> extends DirectionalKineticBlock implements IBE<BE> {

    protected PairOutputBlock(Properties properties) {
        super(properties);
    }

    /** The block of its input half. */
    protected abstract Block inputBlock();

    @Override
    public Direction getPreferredFacing(BlockPlaceContext context) {
        for (Direction side : Iterate.directions) {
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(side));
            if (neighbor.is(inputBlock()) && neighbor.getValue(FACING) == side.getOpposite())
                return side.getOpposite();
        }
        return null;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        Direction preferred = getPreferredFacing(context);
        return state == null || preferred == null ? null : state.setValue(FACING, preferred);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return new ItemStack(inputBlock());
    }

    /** Sneak-wrenching picks up the whole pair, by its input half. */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        BlockPos clickedPos = context.getClickedPos();
        Level level = context.getLevel();
        if (stillValid(level, clickedPos, state)) {
            BlockPos inputPos = getInput(clickedPos, state);
            context = new UseOnContext(level, context.getPlayer(), context.getHand(), context.getItemInHand(),
                    new BlockHitResult(context.getClickLocation(), context.getClickedFace(), inputPos,
                            context.isInside()));
            state = level.getBlockState(inputPos);
        }
        return super.onSneakWrenched(state, context);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (stillValid(level, pos, state))
            level.destroyBlock(getInput(pos, state), true);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (stillValid(level, pos, state)) {
            BlockPos inputPos = getInput(pos, state);
            level.destroyBlockProgress(inputPos.hashCode(), inputPos, -1);
            if (!level.isClientSide() && player.isCreative())
                level.destroyBlock(inputPos, false);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                  LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
        if (stillValid(level, currentPos, state)) {
            BlockPos inputPos = getInput(currentPos, state);
            if (!level.getBlockTicks().hasScheduledTick(inputPos, inputBlock()))
                level.scheduleTick(inputPos, inputBlock(), 1);
            return state;
        }
        // Its input half is gone: it goes too, next tick.
        if (level instanceof Level realLevel && !realLevel.isClientSide()
                && !realLevel.getBlockTicks().hasScheduledTick(currentPos, this))
            realLevel.scheduleTick(currentPos, this, 1);
        return state;
    }

    public static BlockPos getInput(BlockPos pos, BlockState state) {
        Direction direction = state.getOptionalValue(FACING).orElse(Direction.NORTH);
        return pos.relative(direction.getOpposite());
    }

    /** Whether its input half stands behind it, facing the same way. */
    public boolean stillValid(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.is(this))
            return false;
        Direction direction = state.getValue(FACING);
        BlockState inputState = level.getBlockState(pos.relative(direction.getOpposite()));
        return inputState.is(inputBlock()) && inputState.getValue(FACING) == direction;
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!stillValid(level, pos, state))
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }
}