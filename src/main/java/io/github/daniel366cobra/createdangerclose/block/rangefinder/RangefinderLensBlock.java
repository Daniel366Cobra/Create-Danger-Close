package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;

/**
 * One of the two objective lenses at the ends of a rangefinder tube. The
 * distance between the two lenses of a tube is the rangefinder's baseline.
 */
public class RangefinderLensBlock extends RangefinderOpticBlock {

    public static final MapCodec<RangefinderLensBlock> CODEC = simpleCodec(RangefinderLensBlock::new);

    public RangefinderLensBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }
}
