package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Lays the table's maps on its top face as one stitched sheet, inset one
 * pixel inside the frame trim that runs along the table's outer edge. Drawn by
 * the controller for the whole table.
 */
public class MapTableRenderer implements BlockEntityRenderer<MapTableBlockEntity> {

    // Frame trim left visible around the sheet, in blocks (one texture pixel).
    public static final float BORDER = 1 / 16f;
    // Lift above the top face, against z-fighting.
    private static final float LIFT = 0.001f;
    // Further lift for the position marker, above the maps.
    private static final float MARKER_LIFT = 0.002f;
    // Size of one plot line pixel on the table top, in blocks.
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
                // Map x → east, map y (down the page) → south, facing up.
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
        // A Ponder chart draws its lines and markers bolder, to read in the small view.
        MapTableBlockEntity.PonderChart chart = table.getPonderChart();
        float bold = chart != null ? chart.boldness() : 1;
        pose.pushPose();
        pose.translate(BORDER, 1 + LIFT, BORDER);
        pose.mulPose(Axis.XP.rotationDegrees(90));
        // Plot lines between the maps and the markers; 1/64 block per line pixel.
        MapTablePlot.render(pose, buffers, table, width * cell, PLOT_PIXEL * bold, -MARKER_LIFT / 2, light);
        pose.popPose();

        List<MapTableMarker.Marker> markers = MapTableMarker.computeAll(table);
        if (!markers.isEmpty()) {
            pose.pushPose();
            pose.translate(BORDER, 1 + LIFT, BORDER);
            pose.mulPose(Axis.XP.rotationDegrees(90));
            // Vanilla size: 8 map pixels. The pose's -Z is up, so a negative lift clears the maps;
            // each later marker sits a little higher, over the earlier ones.
            for (int i = 0; i < markers.size(); i++)
                MapTableMarker.render(pose, buffers, markers.get(i), width * cell, 8 * texel * bold,
                        -MARKER_LIFT * (i + 1), light);
            pose.popPose();
        }
    }

    /** The controller draws across the whole table, not just its own block. */
    @Override
    public AABB getRenderBoundingBox(MapTableBlockEntity table) {
        int width = table.getWidth();
        return new AABB(table.getBlockPos()).expandTowards(width - 1, 0.1, width - 1);
    }
}
