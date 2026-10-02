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
 * The computer casing, like Create's gearbox: plain faces of the Ballistic
 * Computer and its modules join into one cabinet, while each block's
 * instrument face (the way it faces) keeps its own texture and never joins, so
 * the casing around it is framed. Only quads that use the casing texture are
 * affected; everything else in the models is left alone.
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

    /**
     * The blockstate turns the whole model with the block's facing, and with
     * it the texture on the top and bottom faces: the sheet's "up" points the
     * way the block faces, not always north. Tell Create, so it picks tiles
     * in the texture's frame (as Create's item vault does). Side faces are
     * not affected: turning about the vertical axis does not rotate them.
     */
    @Override
    protected Direction getUpDirection(BlockAndTintGetter reader, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return super.getUpDirection(reader, pos, state, face);
    }

    /**
     * See {@link #getUpDirection}. Create flips the returned direction for the
     * up face (and the down face), so this is the texture's left, which ends
     * up as its right.
     */
    @Override
    protected Direction getRightDirection(BlockAndTintGetter reader, BlockPos pos, BlockState state, Direction face) {
        if (face.getAxis().isVertical() && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING).getCounterClockWise();
        return super.getRightDirection(reader, pos, state, face);
    }

    /** Whether a block is part of the computer cabinet, and this face of it is plain casing. */
    private static boolean isPlainCasing(BlockState state, Direction face) {
        boolean cabinet = state.getBlock() instanceof BallisticComputerBlock
                || state.getBlock() instanceof ComputerModuleBlock;
        return cabinet && state.getValue(BlockStateProperties.HORIZONTAL_FACING) != face;
    }
}
