package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;

/**
 * A rangefinder optics block (lens, eyepiece). Like a lectern, it only turns
 * in yaw: it looks along {@link #FACING} and never tips over.
 */
public abstract class RangefinderOpticBlock extends HorizontalFacedBlock {

    protected RangefinderOpticBlock(Properties properties) {
        super(properties);
    }
}