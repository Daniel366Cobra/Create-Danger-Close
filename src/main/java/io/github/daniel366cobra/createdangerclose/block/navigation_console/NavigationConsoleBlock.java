package io.github.daniel366cobra.createdangerclose.block.navigation_console;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Shows the ship's own attitude, speed and altitude, as far as the battle
 * network has sensors for them. A Battle Network Interface facing it connects
 * it, like a map table.
 */
public class NavigationConsoleBlock extends HorizontalFacedBlock implements IBE<NavigationConsoleBlockEntity> {

    public static final MapCodec<NavigationConsoleBlock> CODEC = simpleCodec(NavigationConsoleBlock::new);

    public NavigationConsoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<NavigationConsoleBlockEntity> getBlockEntityClass() {
        return NavigationConsoleBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends NavigationConsoleBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.NAVIGATION_CONSOLE.get();
    }
}
