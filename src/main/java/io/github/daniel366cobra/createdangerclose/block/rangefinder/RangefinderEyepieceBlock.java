package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The operator's station on a rangefinder tube. Once the tube is assembled,
 * right-clicking the eyepiece seats the player there, and the tube follows
 * where they look. Must face the same way as the lenses, centred on the
 * bearing axis.
 */
public class RangefinderEyepieceBlock extends RangefinderOpticBlock {

    public static final MapCodec<RangefinderEyepieceBlock> CODEC = simpleCodec(RangefinderEyepieceBlock::new);

    public RangefinderEyepieceBlock(Properties properties) {
        super(properties);
    }

    /**
     * Unlike the other faced blocks, the eyepiece looks the way the player
     * placing it is looking: it is placed from the operator's side.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
