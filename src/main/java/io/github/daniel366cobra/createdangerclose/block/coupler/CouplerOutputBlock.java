package io.github.daniel366cobra.createdangerclose.block.coupler;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairOutputBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The Kinetic Network Coupler's output half, turning a network of its own. */
public class CouplerOutputBlock extends PairOutputBlock<CouplerOutputBlockEntity> {

    public CouplerOutputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected Block inputBlock() {
        return CDCBlocks.KINETIC_NETWORK_COUPLER.get();
    }

    @Override
    public BlockEntityType<? extends CouplerOutputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.KINETIC_NETWORK_COUPLER_OUTPUT.get();
    }

    @Override
    public Class<CouplerOutputBlockEntity> getBlockEntityClass() {
        return CouplerOutputBlockEntity.class;
    }
}
