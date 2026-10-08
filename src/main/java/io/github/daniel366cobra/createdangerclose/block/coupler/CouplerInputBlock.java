package io.github.daniel366cobra.createdangerclose.block.coupler;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The Kinetic Network Coupler: placed by its input half, on the network it draws from. */
public class CouplerInputBlock extends PairInputBlock<CouplerInputBlockEntity> {

    public CouplerInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected Block outputBlock() {
        return CDCBlocks.KINETIC_NETWORK_COUPLER_OUTPUT.get();
    }

    @Override
    public BlockEntityType<? extends CouplerInputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.KINETIC_NETWORK_COUPLER.get();
    }

    @Override
    public Class<CouplerInputBlockEntity> getBlockEntityClass() {
        return CouplerInputBlockEntity.class;
    }
}