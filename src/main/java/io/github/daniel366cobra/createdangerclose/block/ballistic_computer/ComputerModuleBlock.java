package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * A module of the fire control computer: a block that adds one
 * capability to the Ballistic Computer whose bank it touches (directly, or
 * through other modules). It has no shaft; the computer finds it by scanning
 * the bank. Its block entity only animates its moving parts, on the client.
 */
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
