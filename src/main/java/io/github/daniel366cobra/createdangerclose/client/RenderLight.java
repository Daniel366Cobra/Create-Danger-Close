package io.github.daniel366cobra.createdangerclose.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * Light for renderers that sample it at a position other than their own
 * block. On a ship, Sable scales the sky light it gives block entity renderers
 * by how much sky the ship sees; this does the same, so parts lit this way
 * match the ship's blocks around them.
 */
public final class RenderLight {

    private RenderLight() {}

    public static int at(BlockAndTintGetter level, BlockPos pos) {
        int light = LevelRenderer.getLightColor(level, pos);
        ClientSubLevel ship = Sable.HELPER.getContainingClient(pos);
        return ship != null ? ship.scaleLightColor(light) : light;
    }
}
