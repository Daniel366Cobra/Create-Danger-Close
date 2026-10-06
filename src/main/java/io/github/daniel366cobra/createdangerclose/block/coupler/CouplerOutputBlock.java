package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class CouplerOutputBlock extends DirectionalKineticBlock implements IBE<CouplerOutputBlockEntity> {

    public CouplerOutputBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction getPreferredFacing(BlockPlaceContext context) {
        for (Direction side : Iterate.directions) {
            BlockState blockState = context.getLevel().getBlockState(context.getClickedPos().relative(side));
            if (!(blockState.getBlock() instanceof CouplerInputBlock)) {
                continue;
            }
            if (blockState.getValue(FACING) == side.getOpposite()) {
                return side.getOpposite();
            }
        }
        return null;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState stateForPlacement = super.getStateForPlacement(context);
        if (stateForPlacement == null) {
            return null;
        }
        Direction preferredFacing = getPreferredFacing(context);
        if (preferredFacing == null) {
            return null;
        }
        return stateForPlacement.setValue(FACING, preferredFacing);
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return CDCBlocks.KINETIC_NETWORK_COUPLER.asStack();
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        BlockPos clickedPos = context.getClickedPos();
        Level level = context.getLevel();

        if (stillValid(level, clickedPos, state)) {
            BlockPos inputPos = getInput(clickedPos, state);
            if (!level.getBlockState(inputPos).is(CDCBlocks.KINETIC_NETWORK_COUPLER.get())) {
                return super.onSneakWrenched(state, context);
            }
            context = new UseOnContext(level, context.getPlayer(), context.getHand(), context.getItemInHand(),
                    new BlockHitResult(context.getClickLocation(), context.getClickedFace(), inputPos,
                            context.isInside()));
            state = level.getBlockState(inputPos);
        }

        return super.onSneakWrenched(state, context);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (stillValid(level, pos, state)) {
            BlockPos inputPos = getInput(pos, state);
            if (level.getBlockState(inputPos).is(CDCBlocks.KINETIC_NETWORK_COUPLER.get())) {
                level.destroyBlock(inputPos, true);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (stillValid(level, pos, state)) {
            BlockPos inputPos = getInput(pos, state);
            if (!level.getBlockState(inputPos).is(CDCBlocks.KINETIC_NETWORK_COUPLER.get())) {
                return super.playerWillDestroy(level, pos, state, player);
            }
            level.destroyBlockProgress(inputPos.hashCode(), inputPos, -1);
            if (!level.isClientSide() && player.isCreative()) {
                level.destroyBlock(inputPos, false);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                  LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
        if (stillValid(level, currentPos, state)) {
            BlockPos inputPos = getInput(currentPos, state);
            if (level.getBlockState(inputPos).is(CDCBlocks.KINETIC_NETWORK_COUPLER.get())) {
                if (!level.getBlockTicks().hasScheduledTick(inputPos, CDCBlocks.KINETIC_NETWORK_COUPLER.get())) {
                    level.scheduleTick(inputPos, CDCBlocks.KINETIC_NETWORK_COUPLER.get(), 1);
                }
            }
            return state;
        }
        if (!(level instanceof Level realLevel) || realLevel.isClientSide()) {
            return state;
        }
        if (!realLevel.getBlockTicks().hasScheduledTick(currentPos, this)) {
            realLevel.scheduleTick(currentPos, this, 1);
        }
        return state;
    }

    public static BlockPos getInput(BlockPos pos, BlockState state) {
        Direction direction = state.getOptionalValue(FACING).orElse(Direction.NORTH);
        return pos.relative(direction.getOpposite());
    }

    public boolean stillValid(BlockGetter level, BlockPos pos, BlockState state) {
        if (!state.is(this)) {
            return false;
        }
        Direction direction = state.getValue(FACING);
        BlockPos inputPos = pos.relative(direction.getOpposite());
        BlockState inputState = level.getBlockState(inputPos);
        return inputState.getBlock() instanceof CouplerInputBlock
                && inputState.getValue(CouplerInputBlock.FACING) == direction;
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!stillValid(level, pos, state)) {
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    @Override
    public BlockEntityType<? extends CouplerOutputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.KINETIC_NETWORK_COUPLER_OUTPUT.get();
    }

    @Override
    public Class<CouplerOutputBlockEntity> getBlockEntityClass() {
        return CouplerOutputBlockEntity.class;
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