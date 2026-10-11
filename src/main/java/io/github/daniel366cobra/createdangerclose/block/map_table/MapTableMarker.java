package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Markers on a map table's sheet, in vanilla's decoration sprites: the target (red X) and, on a
 * battle network, the table's own arrow, turning with the ship, or a banner ashore. Off the sheet the
 * arrow becomes a dot on the nearest edge, large within 256 map pixels, as vanilla does for players.
 */
public final class MapTableMarker {

    // Vanilla: within 320 map pixels of a map's center (256 past its edge).
    private static final float NEAR_OFF_SHEET_PIXELS = 256;

    /**
     * @param x, y  fraction of the sheet's width, north-west = (0, 0)
     * @param yaw   degrees (0 = south, 90 = west)
     */
    public record Marker(Holder<MapDecorationType> type, float x, float y, float yaw) {}

    private MapTableMarker() {}

    /** Ship heading arrow (off the sheet, a dot on its edge), or a banner ashore (on the edge if off). Null without a network, loaded maps, or in another dimension. */
    @Nullable
    public static Marker computeOwn(MapTableBlockEntity controller) {
        Level level = controller.getLevel();
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (level == null || sheet == null || !controller.isLinked() || !sheet.dimension().equals(level.dimension()))
            return null;
        int width = controller.getWidth();
        float sheetPixels = (float) sheet.sizeInPixels();

        BlockPos origin = controller.getBlockPos();
        Vec3 position = new Vec3(origin.getX() + width / 2.0, origin.getY() + 1, origin.getZ() + width / 2.0);
        Vec3 north = new Vec3(0, 0, -1);
        SubLevel ship = controller.getPonderChart() != null ? null : Sable.HELPER.getContaining(level, position);
        if (ship != null) {
            position = ship.logicalPose().transformPosition(position);
            north = ship.logicalPose().transformNormal(north);
        }

        float px = (float) sheet.pixelX(position.x);
        float py = (float) sheet.pixelY(position.z);
        float u = Mth.clamp(px, 0, sheetPixels) / sheetPixels;
        float v = Mth.clamp(py, 0, sheetPixels) / sheetPixels;
        // Ashore there is no heading: a pin, on the edge when off the sheet. The Ponder chart keeps the arrow, as its scene is a ship.
        if (ship == null && controller.getPonderChart() == null)
            return new Marker(MapDecorationTypes.BLUE_BANNER, u, v, 0);
        if (px >= 0 && py >= 0 && px <= sheetPixels && py <= sheetPixels) {
            float yaw = (float) Math.toDegrees(Math.atan2(-north.x, north.z));
            return new Marker(MapDecorationTypes.PLAYER, u, v, yaw);
        }

        float beyond = Math.max(Math.max(-px, px - sheetPixels), Math.max(-py, py - sheetPixels));
        Holder<MapDecorationType> type = beyond < NEAR_OFF_SHEET_PIXELS
                ? MapDecorationTypes.PLAYER_OFF_MAP : MapDecorationTypes.PLAYER_OFF_LIMITS;
        return new Marker(type, u, v, 0);
    }

    /** Null if none, or off the sheet. Shown without a network too, as a memo. */
    @Nullable
    public static Marker computeTarget(MapTableBlockEntity controller) {
        Vec3 target = controller.getTarget();
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (target == null || sheet == null)
            return null;
        double u = sheet.pixelX(target.x) / sheet.sizeInPixels();
        double v = sheet.pixelY(target.z) / sheet.sizeInPixels();
        if (u < 0 || v < 0 || u > 1 || v > 1)
            return null;
        return new Marker(MapDecorationTypes.RED_X, (float) u, (float) v, 0);
    }

    /** Players carrying a copy of a table map (vanilla's player markers), on a networked table, while within that map. */
    public static List<Marker> computeSpotters(MapTableBlockEntity controller) {
        Level level = controller.getLevel();
        if (level == null || !controller.isLinked())
            return List.of();
        if (controller.getPonderChart() != null)
            return controller.getPonderChart().spotters();
        int width = controller.getWidth();
        float sheetPixels = 128f * width;
        List<Marker> spotters = new ArrayList<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                MapTableBlockEntity part = controller.partAt(dx, dz);
                ItemStack map = part != null ? part.getMap() : ItemStack.EMPTY;
                MapItemSavedData data = map.isEmpty() ? null : MapItem.getSavedData(map, level);
                if (data == null)
                    continue;
                for (MapDecoration decoration : data.getDecorations()) {
                    if (decoration.type().value() != MapDecorationTypes.PLAYER.value())
                        continue;
                    // Decoration positions are in half pixels from the map's center; rotation in 16ths of a turn.
                    float px = dx * 128 + decoration.x() / 2f + 64;
                    float py = dz * 128 + decoration.y() / 2f + 64;
                    spotters.add(new Marker(MapDecorationTypes.FRAME, px / sheetPixels, py / sheetPixels,
                            decoration.rot() * 360 / 16f));
                }
            }
        }
        return spotters;
    }

    /** Rangefinder aim points from the plot, on the sheet only. */
    public static List<Marker> computeFixes(MapTableBlockEntity controller) {
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (sheet == null)
            return List.of();
        List<Marker> fixes = new ArrayList<>();
        for (BattlePlot.RangefinderFix fix : controller.getShownPlot(AnimationTickHolder.getPartialTicks()).fixes()) {
            double u = sheet.pixelX(fix.x()) / sheet.sizeInPixels();
            double v = sheet.pixelY(fix.z()) / sheet.sizeInPixels();
            if (u >= 0 && v >= 0 && u <= 1 && v <= 1)
                fixes.add(new Marker(MapDecorationTypes.TARGET_X, (float) u, (float) v, 0));
        }
        return fixes;
    }

    /** Bottom to top: aim points, target, spotters, own marker. */
    public static List<Marker> computeAll(MapTableBlockEntity controller) {
        List<Marker> markers = new ArrayList<>(computeFixes(controller));
        Marker target = computeTarget(controller);
        if (target != null)
            markers.add(target);
        markers.addAll(computeSpotters(controller));
        Marker own = computeOwn(controller);
        if (own != null)
            markers.add(own);
        return markers;
    }

    /**
     * Draws onto a sheet spanning (0, 0)–(sheetSize, sheetSize), north at low y.
     *
     * @param lift  along the pose's z, in front of the maps
     */
    public static void render(PoseStack pose, MultiBufferSource buffers, Marker marker, float sheetSize,
                              float size, float lift, int packedLight) {
        MapDecoration decoration = new MapDecoration(marker.type(), (byte) 0, (byte) 0, (byte) 0, Optional.empty());
        TextureAtlasSprite sprite = Minecraft.getInstance().getMapDecorationTextures().get(decoration);

        pose.pushPose();
        pose.translate(marker.x() * sheetSize, marker.y() * sheetSize, lift);
        pose.mulPose(Axis.ZP.rotationDegrees(marker.yaw()));
        pose.scale(size / 2, size / 2, 1);
        // Vanilla's decoration quad, so the sprite points the same way.
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(sprite.atlasLocation()));
        consumer.addVertex(matrix, -1, 1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(packedLight);
        consumer.addVertex(matrix, 1, 1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(packedLight);
        consumer.addVertex(matrix, 1, -1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(packedLight);
        consumer.addVertex(matrix, -1, -1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(packedLight);
        pose.popPose();
    }
}