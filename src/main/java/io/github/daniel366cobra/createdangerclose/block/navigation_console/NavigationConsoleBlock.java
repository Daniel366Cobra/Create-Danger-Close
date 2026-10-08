package io.github.daniel366cobra.createdangerclose.block.navigation_console;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The ship's own attitude, speed and altitude, as far as the network has sensors. */
public class NavigationConsoleBlock extends HorizontalFacedBlock implements IBE<NavigationConsoleBlockEntity> {

    public static final MapCodec<NavigationConsoleBlock> CODEC = simpleCodec(NavigationConsoleBlock::new);

    // The desk and its backboard (rising into the space above, like a fence), facing north.
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 16, 16),
            Block.box(0, 16, 12, 16, 20, 16)), Direction.NORTH);

    public NavigationConsoleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
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