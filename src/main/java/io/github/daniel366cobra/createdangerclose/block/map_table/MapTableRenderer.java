package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.daniel366cobra.createdangerclose.client.CDCRenderTypes;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** The table's maps as one sheet on its top face, inset one pixel from the trim. Drawn by the controller. */
public class MapTableRenderer implements BlockEntityRenderer<MapTableBlockEntity> {

    // One texture pixel, blocks.
    public static final float BORDER = 1 / 16f;
    // Against z-fighting.
    private static final float LIFT = 0.001f;
    private static final float MARKER_LIFT = 0.002f;
    // Blocks.
    private static final float PLOT_PIXEL = 1 / 64f;

    public MapTableRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(MapTableBlockEntity table, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        if (!table.isController() || table.getLevel() == null)
            return;

        int width = table.getWidth();
        float cell = (width - 2 * BORDER) / width;
        float texel = cell / 128f;

        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                MapTableBlockEntity part = table.partAt(dx, dz);
                if (part == null)
                    continue;
                ItemStack map = part.getMap();
                if (map.isEmpty())
                    continue;

                pose.pushPose();
                pose.translate(BORDER + dx * cell, 1 + LIFT, BORDER + dz * cell);
        // Map x → east, map y → south, facing up.
                pose.mulPose(Axis.XP.rotationDegrees(90));
                pose.scale(texel, texel, texel);
                int mapLight = RenderLight.at(table.getLevel(), part.getBlockPos().above());
                MapTableBlockEntity.PonderChart chart = table.getPonderChart();
                if (chart != null)
                    MapTableMaps.renderPonderChart(pose, buffers, chart.texture(), dx, dz, width, mapLight);
                else
                    MapTableMaps.render(pose, buffers, map, mapLight);
                pose.popPose();
            }
        }

        int light = RenderLight.at(table.getLevel(), table.getBlockPos().above());
        // Ponder charts are bolder, for the small view.
        MapTableBlockEntity.PonderChart chart = table.getPonderChart();
        float bold = chart != null ? chart.boldness() : 1;
        pose.pushPose();
        pose.translate(BORDER, 1 + LIFT, BORDER);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        // Safe zones under the plot lines.
        MapTablePlot.renderSafeZones(pose, buffers, CDCRenderTypes.overlay(), table, width * cell, PLOT_PIXEL * bold,
                -MARKER_LIFT / 4, -MARKER_LIFT * 3 / 8, light, null);
        MapTablePlot.render(pose, buffers, CDCRenderTypes.overlay(), table, width * cell, PLOT_PIXEL * bold, -MARKER_LIFT / 2, light);
        pose.popPose();

        List<MapTableMarker.Marker> markers = MapTableMarker.computeAll(table);
        if (!markers.isEmpty()) {
            pose.pushPose();
            pose.translate(BORDER, 1 + LIFT, BORDER);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            // Vanilla size: 8 map pixels. The pose's -Z is up; later markers sit higher.
            for (int i = 0; i < markers.size(); i++)
                MapTableMarker.render(pose, buffers, markers.get(i), width * cell, 8 * texel * bold,
                        -MARKER_LIFT * (i + 1), light);
            pose.popPose();
        }
    }

    /** It draws across the whole table. */
    @Override
    public AABB getRenderBoundingBox(MapTableBlockEntity table) {
        int width = table.getWidth();
        return new AABB(table.getBlockPos()).expandTowards(width - 1, 0.1, width - 1);
    }
}