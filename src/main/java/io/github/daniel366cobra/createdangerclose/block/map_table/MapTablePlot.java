package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.daniel366cobra.createdangerclose.config.CClient;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.DyeColor;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Plot lines on a map table's sheet: rangefinder uncertainty circles (dotted) and gun ranges (dashed).
 * Thin quads, so the table and the screen draw alike; clipped to the sheet a dash at a time.
 */
public final class MapTablePlot {

    // 2σ: the fix is within ~95% of the time.
    private static final double SIGMA_CIRCLE = 2;

    // Colors per player (client config).
    private static final int LINE_ALPHA = 0xE0;
    // Safe zones' infill: about 35%.
    private static final int FILL_ALPHA = 0x59;

    // Line pixels (stroke width: config).
    private static final float DOT = 1, DOT_GAP = 2;
    private static final float DASH = 4, DASH_GAP = 3;
    // Lines of sight and barrel bearings.
    private static final float AIM_DASH = 2, AIM_GAP = 2;
    // Beyond this, a circle is cut into longer pieces rather than more of them.
    private static final int MAX_PATTERNS = 4000;

    private MapTablePlot() {}

    /**
     * Onto a sheet spanning (0, 0)–(sheetSize, sheetSize), north at low y.
     *
     * @param pixel one line pixel in pose units
     * @param type  untextured, position and color at least
     * @param lift  along the pose's z, in front of the maps
     */
    public static void render(PoseStack pose, MultiBufferSource buffers, RenderType type, MapTableBlockEntity controller,
                              float sheetSize, float pixel, float lift, int packedLight) {
        BattlePlot plot = controller.getShownPlot(AnimationTickHolder.getPartialTicks());
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (plot.isEmpty() || sheet == null || controller.getLevel() == null
                || !sheet.dimension().equals(controller.getLevel().dimension()))
            return;

        double scale = sheetSize / (sheet.sizeInPixels() * sheet.blocksPerPixel());
        Pen pen = new Pen(buffers.getBuffer(type), pose.last().pose(),
                sheetSize, lift, pixel * CDCConfig.client().lineWidth.getF(), packedLight);

        CClient colors = CDCConfig.client();
        int gunRangeColor = lineColor(colors.gunRangeColor.get());
        int gunAimColor = lineColor(colors.gunAimColor.get());
        int rangefinderColor = lineColor(colors.rangefinderColor.get());
        for (BattlePlot.GunRange gun : plot.guns()) {
            pen.color = gunRangeColor;
            double x = sheet.pixelX(gun.x()) / sheet.sizeInPixels() * sheetSize;
            double y = sheet.pixelY(gun.z()) / sheet.sizeInPixels() * sheetSize;
            double radius = gun.radius() * scale;
            // A pie slice if the arc is limited. A bearing lies a quarter turn on from east on the sheet.
            double from = gun.limitsTraverse() ? Math.toRadians(gun.arcFrom() + 90) : 0;
            double sweep = gun.limitsTraverse() ? Math.toRadians(gun.arcLength()) : 2 * Math.PI;
            pen.arc(x, y, radius, from, sweep, pixel * DASH, pixel * DASH_GAP);
            // Dead zone: too close for the steepest or lowest shot.
            if (gun.minRadius() > 0)
                pen.arc(x, y, gun.minRadius() * scale, from, sweep, pixel * AIM_DASH, pixel * AIM_GAP);
            if (gun.limitsTraverse()) {
                for (double edge : new double[]{from, from + sweep})
                    pen.line(x, y, x + Math.cos(edge) * radius, y + Math.sin(edge) * radius,
                            pixel * DASH, pixel * DASH_GAP);
            }
            if (gun.hasAim()) {
                pen.color = gunAimColor;
                pen.line(x, y, x + gun.aimX() * radius, y + gun.aimZ() * radius,
                        pixel * AIM_DASH, pixel * AIM_GAP);
            }
        }
        BattlePlot.MergedFix merged = plot.merged();
        pen.color = rangefinderColor;
        for (BattlePlot.RangefinderFix fix : plot.fixes()) {
            // Merged rangefinders' sights all end on the one solid fix, in place of their own dotted ones.
            boolean inMerge = fix.merged() && merged != null;
            double x = sheet.pixelX(inMerge ? merged.x() : fix.x()) / sheet.sizeInPixels() * sheetSize;
            double y = sheet.pixelY(inMerge ? merged.z() : fix.z()) / sheet.sizeInPixels() * sheetSize;
            if (!inMerge)
                pen.circle(x, y, SIGMA_CIRCLE * fix.sigma() * scale, pixel * DOT, pixel * DOT_GAP);
            pen.line(sheet.pixelX(fix.originX()) / sheet.sizeInPixels() * sheetSize,
                    sheet.pixelY(fix.originZ()) / sheet.sizeInPixels() * sheetSize,
                    x, y, pixel * AIM_DASH, pixel * AIM_GAP);
        }
        if (merged != null) {
            double x = sheet.pixelX(merged.x()) / sheet.sizeInPixels() * sheetSize;
            double y = sheet.pixelY(merged.z()) / sheet.sizeInPixels() * sheetSize;
            pen.circle(x, y, SIGMA_CIRCLE * merged.sigma() * scale, pixel * DASH, 0);
        }
    }

    /**
     * Safe zones, on any table (unlinked, they are notes). Same sheet and pose as {@link #render}.
     *
     * @param borderLift above {@code lift}, so the border does not z-fight the infill
     * @param drawing    a zone being dragged out, in sheet fractions (u0, v0, u1, v1), or null
     */
    public static void renderSafeZones(PoseStack pose, MultiBufferSource buffers, RenderType type,
                                       MapTableBlockEntity controller,
                                       float sheetSize, float pixel, float lift, float borderLift, int packedLight,
                                       @Nullable float[] drawing) {
        List<MapTableBlockEntity.SafeZone> zones = controller.getSafeZones();
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (sheet == null || zones.isEmpty() && drawing == null)
            return;
        VertexConsumer consumer = buffers.getBuffer(type);
        float width = pixel * CDCConfig.client().lineWidth.getF();
        Pen fill = new Pen(consumer, pose.last().pose(), sheetSize, lift, width, packedLight);
        Pen border = new Pen(consumer, pose.last().pose(), sheetSize, borderLift, width, packedLight);
        int rgb = CDCConfig.client().safeZoneColor.get().getTextureDiffuseColor() & 0xFFFFFF;
        double toSheet = sheetSize / sheet.sizeInPixels();
        for (MapTableBlockEntity.SafeZone zone : zones) {
            // Edges on block boundaries: the far ones past the last block.
            safeZone(fill, border, rgb, sheet.pixelX(zone.minX()) * toSheet, sheet.pixelY(zone.minZ()) * toSheet,
                    sheet.pixelX(zone.maxX() + 1) * toSheet, sheet.pixelY(zone.maxZ() + 1) * toSheet, pixel);
        }
        if (drawing != null)
            safeZone(fill, border, rgb, drawing[0] * sheetSize, drawing[1] * sheetSize,
                    drawing[2] * sheetSize, drawing[3] * sheetSize, pixel);
    }

    private static void safeZone(Pen fill, Pen pen, int rgb, double x0, double y0, double x1, double y1, float pixel) {
        fill.color = FILL_ALPHA << 24 | rgb;
        fill.fill(x0, y0, x1, y1);
        pen.color = LINE_ALPHA << 24 | rgb;
        double left = Math.min(x0, x1), right = Math.max(x0, x1), top = Math.min(y0, y1), bottom = Math.max(y0, y1);
        pen.line(left, top, right, top, pixel * DOT, pixel * DOT_GAP);
        pen.line(right, top, right, bottom, pixel * DOT, pixel * DOT_GAP);
        pen.line(right, bottom, left, bottom, pixel * DOT, pixel * DOT_GAP);
        pen.line(left, bottom, left, top, pixel * DOT, pixel * DOT_GAP);
    }

    /** The dye's map shade (as wool and banners), slightly see-through. */
    private static int lineColor(DyeColor dye) {
        return LINE_ALPHA << 24 | dye.getTextureDiffuseColor() & 0xFFFFFF;
    }

    /** Thin two-sided quads, clipped to the sheet piece by piece. */
    private static final class Pen {
        private final VertexConsumer consumer;
        private final Matrix4f matrix;
        private final float sheetSize, z, width;
        private final int light;
        int color;

        Pen(VertexConsumer consumer, Matrix4f matrix, float sheetSize, float z, float width, int light) {
            this.consumer = consumer;
            this.matrix = matrix;
            this.sheetSize = sheetSize;
            this.z = z;
            this.width = width;
            this.light = light;
        }

        void circle(double cx, double cy, double radius, float dash, float gap) {
            arc(cx, cy, radius, 0, 2 * Math.PI, dash, gap);
        }

    /** Radians, clockwise on the sheet. */
        void arc(double cx, double cy, double radius, double from, double sweep, float dash, float gap) {
            if (radius < width)
                return;
            double length = sweep * radius;
            int patterns = (int) Math.min(MAX_PATTERNS, Math.max(sweep >= 2 * Math.PI ? 8 : 2, Math.round(length / (dash + gap))));
            double patternAngle = sweep / patterns;
            double dashAngle = patternAngle * dash / (dash + gap);
            double inner = radius - width / 2, outer = radius + width / 2;
            for (int i = 0; i < patterns; i++) {
                double a0 = from + i * patternAngle, a1 = a0 + dashAngle;
                double mid = (a0 + a1) / 2;
                if (!onSheet(cx + Math.cos(mid) * radius, cy + Math.sin(mid) * radius))
                    continue;
                double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
                quad(cx + c0 * inner, cy + s0 * inner, cx + c0 * outer, cy + s0 * outer,
                        cx + c1 * outer, cy + s1 * outer, cx + c1 * inner, cy + s1 * inner);
            }
        }

        void line(double x0, double y0, double x1, double y1, float dash, float gap) {
            double dx = x1 - x0, dy = y1 - y0;
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < dash)
                return;
            double ux = dx / length, uy = dy / length;
            double nx = -uy * width / 2, ny = ux * width / 2;
            int patterns = (int) Math.min(MAX_PATTERNS, Math.ceil(length / (dash + gap)));
            double step = length / Math.max(1, (int) Math.ceil(length / (dash + gap)));
            for (int i = 0; i < patterns; i++) {
                double t0 = i * step, t1 = Math.min(length, t0 + dash);
                double ax = x0 + ux * t0, ay = y0 + uy * t0, bx = x0 + ux * t1, by = y0 + uy * t1;
                if (!onSheet((ax + bx) / 2, (ay + by) / 2))
                    continue;
                quad(ax - nx, ay - ny, ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny);
            }
        }

        /** A rectangle, cut to the sheet. */
        void fill(double x0, double y0, double x1, double y1) {
            double left = Math.max(0, Math.min(x0, x1)), right = Math.min(sheetSize, Math.max(x0, x1));
            double top = Math.max(0, Math.min(y0, y1)), bottom = Math.min(sheetSize, Math.max(y0, y1));
            if (left < right && top < bottom)
                quad(left, top, right, top, right, bottom, left, bottom);
        }

        private boolean onSheet(double x, double y) {
            return x >= 0 && y >= 0 && x <= sheetSize && y <= sheetSize;
        }

        private void quad(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            vertex(x0, y0);
            vertex(x1, y1);
            vertex(x2, y2);
            vertex(x3, y3);
            // Both sides, so culling cannot hide it.
            vertex(x3, y3);
            vertex(x2, y2);
            vertex(x1, y1);
            vertex(x0, y0);
        }

        private void vertex(double x, double y) {
            consumer.addVertex(matrix, (float) x, (float) y, z).setColor(color).setLight(light);
        }
    }
}