package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** The core of the fire control computer. No shaft: a facing Battle Network Interface drives it. */
public class BallisticComputerBlock extends HorizontalFacedBlock implements IBE<BallisticComputerBlockEntity> {

    public static final MapCodec<BallisticComputerBlock> CODEC = simpleCodec(BallisticComputerBlock::new);

    public BallisticComputerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player && !level.isClientSide && ComputerBank.completedBy(level, pos))
            CDCTriggers.FULL_BANK.get().trigger(player);
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