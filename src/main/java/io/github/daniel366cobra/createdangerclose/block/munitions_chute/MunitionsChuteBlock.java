package io.github.daniel366cobra.createdangerclose.block.munitions_chute;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Dispenses a round of shell and cartridge from the inventories on top and on its shaftless sides. */
public class MunitionsChuteBlock extends HorizontalAxisKineticBlock implements IBE<MunitionsChuteBlockEntity> {

    public MunitionsChuteBlock(Properties properties) {
        super(properties);
    }

    // Only the top and bottom turn the shaft; the sides would tip it vertical.
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (context.getClickedFace().getAxis().isHorizontal())
            return InteractionResult.PASS;
        return super.onWrenched(state, context);
    }

    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        return originalState.cycle(HORIZONTAL_AXIS);
    }

    @Override
    public Class<MunitionsChuteBlockEntity> getBlockEntityClass() {
        return MunitionsChuteBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MunitionsChuteBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.MUNITIONS_CHUTE.get();
    }
}
