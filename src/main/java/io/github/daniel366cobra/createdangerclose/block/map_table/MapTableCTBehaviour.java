package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import io.github.daniel366cobra.createdangerclose.registries.CDCSpriteShifts;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Joins the textures of blocks in the same map table multiblock, so the frame
 * trim runs only along the table's outer edge (like Create's item vault).
 */
public class MapTableCTBehaviour extends ConnectedTextureBehaviour.Base {

    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction direction, @Nullable TextureAtlasSprite sprite) {
        return switch (direction) {
            case UP -> CDCSpriteShifts.MAP_TABLE_TOP;
            case DOWN -> CDCSpriteShifts.MAP_TABLE_BOTTOM;
            default -> CDCSpriteShifts.MAP_TABLE_SIDE;
        };
    }

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos,
                              BlockPos otherPos, Direction face) {
        return state.getBlock() == other.getBlock() && ConnectivityHandler.isConnected(reader, pos, otherPos);
    }
}