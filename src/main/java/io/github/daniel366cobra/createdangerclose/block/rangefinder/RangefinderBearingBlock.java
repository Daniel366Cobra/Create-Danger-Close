package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Carries a rangefinder tube in yaw. Its network rotation is a speed budget, not a direct drive. */
public class RangefinderBearingBlock extends BearingBlock implements IBE<RangefinderBearingBlockEntity> {

    public RangefinderBearingBlock(Properties properties) {
        super(properties);
    }

    /** Plate up on a deck, down under a hull. Sneaking flips it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace() == Direction.DOWN ? Direction.DOWN : Direction.UP;
        if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown())
            facing = facing.getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    /** Up or down, never sideways. */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState.setValue(FACING, originalState.getValue(FACING).getOpposite());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!player.mayBuild())
            return ItemInteractionResult.FAIL;
        if (player.isShiftKeyDown())
            return ItemInteractionResult.FAIL;
        if (!stack.isEmpty())
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide)
            return ItemInteractionResult.SUCCESS;

        withBlockEntityDo(level, pos, bearing -> bearing.toggleAssembly(player));
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        InteractionResult result = super.onWrenched(state, context);
        if (!context.getLevel().isClientSide && result.consumesAction()) {
            withBlockEntityDo(context.getLevel(), context.getClickedPos(),
                    RangefinderBearingBlockEntity::disassemble);
        }
        return result;
    }

    @Override
    public Class<RangefinderBearingBlockEntity> getBlockEntityClass() {
        return RangefinderBearingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RangefinderBearingBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.RANGEFINDER_BEARING.get();
    }
}