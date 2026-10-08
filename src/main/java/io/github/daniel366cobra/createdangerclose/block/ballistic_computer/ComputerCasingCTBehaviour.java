package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import io.github.daniel366cobra.createdangerclose.registries.CDCSpriteShifts;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Like Create's gearbox: plain casing faces join across the bank; the instrument face never joins,
 * so it is framed. Only quads using the casing texture are affected.
 */
public class ComputerCasingCTBehaviour extends ConnectedTextureBehaviour.Base {

    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
        return CDCSpriteShifts.COMPUTER_CASING;
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
                              BlockPos otherPos, Direction face) {
        if (isBeingBlocked(state, reader, pos, otherPos, face))
            return false;
        return isPlainCasing(state, face) && isPlainCasing(other, face);
    }

    /** The blockstate turns top and bottom textures with the facing: tell Create, as its item vault does. */
    @Override
    protected Direction getUpDirection(BlockAndTintGetter reader, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return super.getUpDirection(reader, pos, state, face);
    }

    /** Create flips this for the up and down faces, so the texture's left ends up as its right. */
    @Override
    protected Direction getRightDirection(BlockAndTintGetter reader, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getCounterClockWise();
        return super.getRightDirection(reader, pos, state, face);
    }

    private static boolean isPlainCasing(BlockState state, Direction face) {
        boolean cabinet = state.getBlock() instanceof BallisticComputerBlock
                || state.getBlock() instanceof ComputerModuleBlock;
        return cabinet && state.getValue(BlockStateProperties.HORIZONTAL_FACING) != face;
    }
}