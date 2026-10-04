package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
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
 * Markers on a map table's stitched sheet, drawn with vanilla's map
 * decoration sprites: the designated target (a red X), and, once the table is
 * on a battle network, the "you are here" marker for the table and the ship
 * carrying it. On the sheet it is an arrow pointing the way the table's north edge
 * faces in the world, so it turns as the ship turns. Off the sheet it becomes a
 * dot pinned to the nearest edge: large while within 256 map pixels of the
 * sheet, small further out, as vanilla maps do for players.
 */
public final class MapTableMarker {

    // Vanilla: players up to 320 map pixels from a map's centre (256 past its edge) get the large dot.
    private static final float NEAR_OFF_SHEET_PIXELS = 256;

    /**
     * @param x, y  position on the sheet as a fraction of its width, north-west = (0, 0)
     * @param yaw   Minecraft yaw of the arrow in degrees (0 = south, 90 = west)
     */
    public record Marker(Holder<MapDecorationType> type, float x, float y, float yaw) {}

    private MapTableMarker() {}

    /**
     * The table's own position marker: on a ship an arrow showing the ship's
     * heading, in the world a blue banner. Off the sheet, either becomes a large or
     * small circle at its edge. Null if the table is not on a battle network, none
     * of its maps are loaded, or they show another dimension.
     */
    @Nullable
    public static Marker computeOwn(MapTableBlockEntity controller) {
        Level level = controller.getLevel();
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (level == null || sheet == null || !controller.isLinked() || !sheet.dimension().equals(level.dimension()))
            return null;
        int width = controller.getWidth();
        float sheetPixels = (float) sheet.sizeInPixels();

        // The middle of the table, and the way its north edge faces, in the world.
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
        if (px >= 0 && py >= 0 && px <= sheetPixels && py <= sheetPixels) {
            // A table in the world (a fort's plotting room) has no heading to show: a pin.
            // The Ponder chart keeps the ship's arrow, as its scene describes a ship.
            if (ship == null && controller.getPonderChart() == null)
                return new Marker(MapDecorationTypes.BLUE_BANNER, px / sheetPixels, py / sheetPixels, 0);
            float yaw = (float) Math.toDegrees(Math.atan2(-north.x, north.z));
            return new Marker(MapDecorationTypes.PLAYER, px / sheetPixels, py / sheetPixels, yaw);
        }

        float beyond = Math.max(Math.max(-px, px - sheetPixels), Math.max(-py, py - sheetPixels));
        Holder<MapDecorationType> type = beyond < NEAR_OFF_SHEET_PIXELS
                ? MapDecorationTypes.PLAYER_OFF_MAP : MapDecorationTypes.PLAYER_OFF_LIMITS;
        return new Marker(type, Mth.clamp(px, 0, sheetPixels) / sheetPixels,
                Mth.clamp(py, 0, sheetPixels) / sheetPixels, 0);
    }

    /**
     * The designated target as a red X, or null if there is none or it lies
     * off the sheet (the maps under it were taken away). Shown whether or not
     * the table is on a network: without one it is just a memo.
     */
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

    /**
     * Spotters: players carrying a copy of one of the table's maps, as green
     * arrows. Vanilla already tracks them as the map's player markers (and a
     * spotter holding the copy keeps its pixels up to date). Only on a table on
     * a battle network, and only while they are within the map they carry.
     */
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
                    // Decoration positions are in half pixels from the map's centre; rotation in 16ths of a turn.
                    float px = dx * 128 + decoration.x() / 2f + 64;
                    float py = dz * 128 + decoration.y() / 2f + 64;
                    spotters.add(new Marker(MapDecorationTypes.FRAME, px / sheetPixels, py / sheetPixels,
                            decoration.rot() * 360 / 16f));
                }
            }
        }
        return spotters;
    }

    /**
     * Rangefinder aim points from the computer's plot, as white X's. Only on the
     * sheet; the plot is empty unless the table is on a battle network.
     */
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

    /**
     * All markers on the table, bottom to top: rangefinder aim points, the
     * designated target, spotters, and the ship's own marker.
     */
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
     * Draws the marker onto a sheet spanning (0, 0)–(sheetSize, sheetSize) of the
     * current pose, north at the top (low y), laid out like the maps themselves.
     *
     * @param size  the marker's width in pose units
     * @param lift  offset along the pose's z axis, to sit in front of the maps
     */
    public static void render(PoseStack pose, MultiBufferSource buffers, Marker marker, float sheetSize,
                              float size, float lift, int packedLight) {
        MapDecoration decoration = new MapDecoration(marker.type(), (byte) 0, (byte) 0, (byte) 0, Optional.empty());
        TextureAtlasSprite sprite = Minecraft.getInstance().getMapDecorationTextures().get(decoration);

        pose.pushPose();
        pose.translate(marker.x() * sheetSize, marker.y() * sheetSize, lift);
        pose.mulPose(Axis.ZP.rotationDegrees(marker.yaw()));
        pose.scale(size / 2, size / 2, 1);
        // Same quad as vanilla's map decorations, so the sprite points the same way.
        Matrix4f matrix = pose.last().pose();
        VertexConsumer consumer = buffers.getBuffer(RenderType.text(sprite.atlasLocation()));
        consumer.addVertex(matrix, -1, 1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV0()).setLight(packedLight);
        consumer.addVertex(matrix, 1, 1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV0()).setLight(packedLight);
        consumer.addVertex(matrix, 1, -1, 0).setColor(-1).setUv(sprite.getU1(), sprite.getV1()).setLight(packedLight);
        consumer.addVertex(matrix, -1, -1, 0).setColor(-1).setUv(sprite.getU0(), sprite.getV1()).setLight(packedLight);
        pose.popPose();
    }
}
