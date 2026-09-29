package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/** Client-side map drawing shared by the table's top face and its screen. */
public final class MapTableMaps {

    private MapTableMaps() {}

    /**
     * Draws a filled map into the square (0, 0)–(128, 128) of the current
     * pose, north at the top (low y), as vanilla does for item frames. The
     * quad faces −Z of the pose.
     *
     * @return false if there is no map, or its contents have not arrived yet
     */
    public static boolean render(PoseStack pose, MultiBufferSource buffers, ItemStack stack, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mc.level == null || mapId == null)
            return false;
        MapItemSavedData data = MapItem.getSavedData(mapId, mc.level);
        if (data == null)
            return false;
        // "active" = framed: player markers are hidden, as on an item frame.
        mc.gameRenderer.getMapRenderer().render(pose, buffers, mapId, data, true, packedLight);
        return true;
    }
}
