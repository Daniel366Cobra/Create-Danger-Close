package io.github.daniel366cobra.createdangerclose.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/** Light sampled away from a renderer's own block, with Sable's ship sky-light scaling, to match the ship. */
public final class RenderLight {

    private RenderLight() {}

    public static int at(BlockAndTintGetter level, BlockPos pos) {
        int light = LevelRenderer.getLightColor(level, pos);
        ClientSubLevel ship = Sable.HELPER.getContainingClient(pos);
        return ship != null ? ship.scaleLightColor(light) : light;
    }
}