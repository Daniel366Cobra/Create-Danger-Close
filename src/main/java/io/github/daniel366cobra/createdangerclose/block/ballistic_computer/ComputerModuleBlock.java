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

/** Adds one capability to the Ballistic Computer whose bank it touches. No shaft; client-only animation. */
public class ComputerModuleBlock extends HorizontalFacedBlock implements IBE<ComputerModuleBlockEntity> {

    private final ComputerModule module;
    private final MapCodec<ComputerModuleBlock> codec;

    public ComputerModuleBlock(Properties properties, ComputerModule module) {
        super(properties);
        this.module = module;
        this.codec = simpleCodec(p -> new ComputerModuleBlock(p, module));
    }

    public ComputerModule getModule() {
        return module;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer instanceof Player player && !level.isClientSide && ComputerBank.completedBy(level, pos))
            CDCTriggers.FULL_BANK.get().trigger(player);
    }

    @Override
    public Class<ComputerModuleBlockEntity> getBlockEntityClass() {
        return ComputerModuleBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ComputerModuleBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.COMPUTER_MODULE.get();
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return codec;
    }
}