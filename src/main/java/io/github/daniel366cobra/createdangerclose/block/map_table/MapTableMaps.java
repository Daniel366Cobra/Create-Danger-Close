package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.joml.Matrix4f;

/** Client map drawing for the table top and its screen. */
public final class MapTableMaps {

    private MapTableMaps() {}

    /**
     * Into (0, 0)–(128, 128), north at low y, as on an item frame; faces −Z.
     *
     * @return false without a map, or before its contents arrive
     */
    public static boolean render(PoseStack pose, MultiBufferSource buffers, ItemStack stack, int packedLight) {
        Minecraft mc = Minecraft.getInstance();
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mc.level == null || mapId == null)
            return false;
        MapItemSavedData data = MapItem.getSavedData(mapId, mc.level);
        if (data == null)
            return false;
        // As on an item frame: player markers hidden (spotters show green, on linked tables), frames and banners shown.
        mc.gameRenderer.getMapRenderer().render(pose, buffers, mapId, data, true, packedLight);
        return true;
    }

    /** The slice of a Ponder chart for the part at (dx, dz), drawn as {@link #render}. */
    public static void renderPonderChart(PoseStack pose, MultiBufferSource buffers, ResourceLocation texture,
                                         int dx, int dz, int width, int packedLight) {
        float u0 = (float) dx / width, u1 = (float) (dx + 1) / width;
        float v0 = (float) dz / width, v1 = (float) (dz + 1) / width;
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(texture));
        consumer.addVertex(matrix, 0, 128, -0.01f).setColor(-1).setUv(u0, v1).setLight(packedLight);
        consumer.addVertex(matrix, 128, 128, -0.01f).setColor(-1).setUv(u1, v1).setLight(packedLight);
        consumer.addVertex(matrix, 128, 0, -0.01f).setColor(-1).setUv(u1, v0).setLight(packedLight);
        consumer.addVertex(matrix, 0, 0, -0.01f).setColor(-1).setUv(u0, v0).setLight(packedLight);
    }
}