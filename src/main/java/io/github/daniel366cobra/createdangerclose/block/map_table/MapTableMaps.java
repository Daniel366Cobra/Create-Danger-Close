package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.MapDecorationTextureManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
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
        // As on an item frame: player markers hidden, frames and banners shown.
        mc.gameRenderer.getMapRenderer().render(pose, buffers, mapId, data, true, packedLight);
        renderPlayers(pose, buffers, data, packedLight);
        return true;
    }

    /** Player arrows only: vanilla's own way also dots every map's edge, marking the seams between them. */
    private static void renderPlayers(PoseStack pose, MultiBufferSource buffers, MapItemSavedData data, int packedLight) {
        MapDecorationTextureManager textures = Minecraft.getInstance().getMapDecorationTextures();
        int index = 0;
        for (MapDecoration decoration : data.getDecorations()) {
            if (!decoration.type().is(MapDecorationTypes.PLAYER))
                continue;
            TextureAtlasSprite sprite = textures.get(decoration);
            pose.pushPose();
            pose.translate(decoration.x() / 2f + 64, decoration.y() / 2f + 64, -0.02f);
            pose.mulPose(Axis.ZP.rotationDegrees(decoration.rot() * 360 / 16f));
            pose.scale(4, 4, 3);
            pose.translate(-0.125f, 0.125f, 0);
            Matrix4f matrix = pose.last().pose();
            float z = -0.001f * index++;
            VertexConsumer consumer = buffers.getBuffer(RenderType.text(sprite.atlasLocation()));
            consumer.addVertex(matrix, -1, 1, z).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(packedLight);
            consumer.addVertex(matrix, 1, 1, z).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(packedLight);
            consumer.addVertex(matrix, 1, -1, z).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(packedLight);
            consumer.addVertex(matrix, -1, -1, z).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(packedLight);
            pose.popPose();
        }
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