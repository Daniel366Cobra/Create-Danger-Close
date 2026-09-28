package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

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

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
