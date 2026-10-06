package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class CouplerInputBlock extends DirectionalKineticBlock implements IBE<CouplerInputBlockEntity> {

    public CouplerInputBlock(Properties properties) {
        super(properties);
    }

    private BlockState getBaseStateForPlacement(BlockPlaceContext context) {
        Direction preferred = getPreferredFacing(context);
        if (preferred == null || (context.getPlayer() != null && context.getPlayer().isShiftKeyDown())) {
            Direction nearestLookingDirection = context.getNearestLookingDirection();
            return defaultBlockState().setValue(FACING,
                    context.getPlayer() != null && context.getPlayer().isShiftKeyDown()
                            ? nearestLookingDirection.getOpposite()
                            : nearestLookingDirection);
        }
        return defaultBlockState().setValue(FACING, preferred.getOpposite());
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState stateForPlacement = getBaseStateForPlacement(context);
        BlockPos pos = context.getClickedPos();
        Direction facing = stateForPlacement.getValue(FACING);

        BlockPos outputPos = pos.relative(facing);
        BlockState occupiedState = context.getLevel().getBlockState(outputPos);
        if (!occupiedState.canBeReplaced()) {
            return null;
        }

        return stateForPlacement;
    }

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
        if (!level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, 1);
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!newState.is(this)) {
            Direction facing = state.getValue(FACING);
            BlockPos outputPos = pos.relative(facing);

            BlockState occupiedState = level.getBlockState(outputPos);
            BlockState requiredStructure = CDCBlocks.KINETIC_NETWORK_COUPLER_OUTPUT.getDefaultState()
                    .setValue(CouplerOutputBlock.FACING, facing);
            if (occupiedState.equals(requiredStructure)) {
                level.destroyBlock(outputPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Direction facing = state.getValue(FACING);
        BlockPos outputPos = pos.relative(facing);

        BlockState occupiedState = level.getBlockState(outputPos);
        BlockState requiredStructure = CDCBlocks.KINETIC_NETWORK_COUPLER_OUTPUT.getDefaultState()
                .setValue(CouplerOutputBlock.FACING, facing);
        if (!occupiedState.equals(requiredStructure)) {
            if (!occupiedState.canBeReplaced()) {
                level.destroyBlock(pos, true);
                return;
            }
            level.setBlockAndUpdate(outputPos, requiredStructure);
        }
    }

    @Override
    public BlockEntityType<? extends CouplerInputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.KINETIC_NETWORK_COUPLER.get();
    }

    @Override
    public Class<CouplerInputBlockEntity> getBlockEntityClass() {
        return CouplerInputBlockEntity.class;
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