package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Charts terrain as vanilla's {@code MapItem.update} does, but never loads chunks. */
final class MapCharting {

    private MapCharting() {}

    /** Vanilla's 16. */
    static final int STAGGER_COLUMNS = 16;

    /** Map pixels. */
    static int holderRadius(ServerLevel level, MapItemSavedData data) {
        int radius = 128 / (1 << data.scale);
        return level.dimensionType().hasCeiling() ? radius / 2 : radius;
    }

    /**
     * @param radius map pixels
     * @param step   only this column step (0..15), as vanilla spreads drawing over ticks; -1 for all
     * @return whether any pixel changed
     */
    static boolean chart(ServerLevel level, MapItemSavedData data, double x, double z, int radius, int step) {
        if (data.locked || level.dimension() != data.dimension || radius <= 0)
            return false;
        int scale = 1 << data.scale;
        int centerX = data.centerX, centerZ = data.centerZ;
        int px = Mth.floor(x - centerX) / scale + 64;
        int pz = Mth.floor(z - centerZ) / scale + 64;
        boolean ceiling = level.dimensionType().hasCeiling();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        boolean redrawNext = false, changed = false;
        for (int column = px - radius + 1; column < px + radius; column++) {
            if (step >= 0 && (column & 15) != step && !redrawNext)
                continue;
            redrawNext = false;
            double previousHeight = 0;
            for (int row = pz - radius - 1; row < pz + radius; row++) {
                if (column < 0 || row < -1 || column >= 128 || row >= 128)
                    continue;
                int distanceSq = Mth.square(column - px) + Mth.square(row - pz);
                boolean nearEdge = distanceSq > (radius - 2) * (radius - 2);
                int worldX = (centerX / scale + column - 64) * scale;
                int worldZ = (centerZ / scale + row - 64) * scale;
                int chunkX = SectionPos.blockToSectionCoord(worldX), chunkZ = SectionPos.blockToSectionCoord(worldZ);
        // The one change from vanilla.
                if (!level.hasChunk(chunkX, chunkZ))
                    continue;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                if (chunk.isEmpty())
                    continue;

                Multiset<MapColor> colors = LinkedHashMultiset.create();
                int waterDepth = 0;
                double height = 0;
                if (ceiling) {
                    int noise = worldX + worldZ * 231871;
                    noise = noise * noise * 31287121 + noise * 11;
                    if ((noise >> 20 & 1) == 0)
                        colors.add(Blocks.DIRT.defaultBlockState().getMapColor(level, BlockPos.ZERO), 10);
                    else
                        colors.add(Blocks.STONE.defaultBlockState().getMapColor(level, BlockPos.ZERO), 100);
                    height = 100;
                } else {
                    for (int dx = 0; dx < scale; dx++) {
                        for (int dz = 0; dz < scale; dz++) {
                            pos.set(worldX + dx, 0, worldZ + dz);
                            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) + 1;
                            BlockState state;
                            if (y <= level.getMinBuildHeight() + 1) {
                                state = Blocks.BEDROCK.defaultBlockState();
                            } else {
                                do {
                                    pos.setY(--y);
                                    state = chunk.getBlockState(pos);
                                } while (state.getMapColor(level, pos) == MapColor.NONE && y > level.getMinBuildHeight());

                                if (y > level.getMinBuildHeight() && !state.getFluidState().isEmpty()) {
                                    int depthY = y - 1;
                                    below.set(pos);
                                    BlockState belowState;
                                    do {
                                        below.setY(depthY--);
                                        belowState = chunk.getBlockState(below);
                                        waterDepth++;
                                    } while (depthY > level.getMinBuildHeight() && !belowState.getFluidState().isEmpty());
                                    state = fluidSurface(level, state, pos);
                                }
                            }
                            data.checkBanners(level, pos.getX(), pos.getZ());
                            height += (double) y / (scale * scale);
                            colors.add(state.getMapColor(level, pos));
                        }
                    }
                }

                waterDepth /= scale * scale;
                MapColor color = Iterables.getFirst(Multisets.copyHighestCountFirst(colors), MapColor.NONE);
                MapColor.Brightness brightness;
                if (color == MapColor.WATER) {
                    double shade = waterDepth * 0.1 + (column + row & 1) * 0.2;
                    brightness = shade < 0.5 ? MapColor.Brightness.HIGH
                            : shade > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                } else {
                    double shade = (height - previousHeight) * 4.0 / (scale + 4) + ((column + row & 1) - 0.5) * 0.4;
                    brightness = shade > 0.6 ? MapColor.Brightness.HIGH
                            : shade < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                }
                previousHeight = height;
                if (row >= 0 && distanceSq < radius * radius && (!nearEdge || (column + row & 1) != 0))
                    redrawNext |= data.updateColor(column, row, color.getPackedId(brightness));
            }
            changed |= redrawNext;
        }
        return changed;
    }

    /** As vanilla's MapItem. */
    private static BlockState fluidSurface(ServerLevel level, BlockState state, BlockPos pos) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP) ? fluid.createLegacyBlock() : state;
    }
}