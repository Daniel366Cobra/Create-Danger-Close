package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The core of the fire control computer. No shaft: a facing Battle Network Interface drives it. */
public class BallisticComputerBlock extends HorizontalFacedBlock implements IBE<BallisticComputerBlockEntity> {

    public static final MapCodec<BallisticComputerBlock> CODEC = simpleCodec(BallisticComputerBlock::new);

    public BallisticComputerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<BallisticComputerBlockEntity> getBlockEntityClass() {
        return BallisticComputerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends BallisticComputerBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.BALLISTIC_COMPUTER.get();
    }
}