package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Draws the solver's plot lines on a map table's sheet: each rangefinder's
 * uncertainty circle (blue, dotted; the aim point itself is a marker, see
 * {@link MapTableMarker}), and each gun's maximum range (red, dashed). Lines are thin quads, so they work the same on the
 * table top and in the screen, and are clipped to the sheet a dash at a time.
 */
public final class MapTablePlot {

    // Uncertainty circles are drawn at 2σ: the fix is within them ~95% of the time.
    private static final double SIGMA_CIRCLE = 2;

    private static final int RANGEFINDER_COLOR = 0xE02B5FD9;
    private static final int GUN_COLOR = 0xE0B02E26;

    // In line pixels (see render): stroke width, and dash / gap lengths along the circle.
    private static final float LINE_WIDTH = 1;
    private static final float DOT = 1, DOT_GAP = 2;
    private static final float DASH = 4, DASH_GAP = 3;
    // Aim lines (a rangefinder's line of sight, a barrel's bearing): short, tight dashes.
    private static final float AIM_DASH = 2, AIM_GAP = 2;
    // Beyond this, a circle is cut into longer pieces rather than more of them.
    private static final int MAX_PATTERNS = 4000;

    private MapTablePlot() {}

    /**
     * Draws the plot onto a sheet spanning (0, 0)–(sheetSize, sheetSize) of the
     * current pose, north at the top (low y), laid out like the maps.
     *
     * @param pixel how big one line pixel is in pose units
     * @param lift  offset along the pose's z axis, to sit in front of the maps
     */
    public static void render(PoseStack pose, MultiBufferSource buffers, MapTableBlockEntity controller,
                              float sheetSize, float pixel, float lift, int packedLight) {
        BattlePlot plot = controller.getPlot();
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (plot.isEmpty() || sheet == null || controller.getLevel() == null
                || !sheet.dimension().equals(controller.getLevel().dimension()))
            return;

        // World blocks to pose units.
        double scale = sheetSize / (sheet.sizeInPixels() * sheet.blocksPerPixel());
        Pen pen = new Pen(buffers.getBuffer(RenderType.textBackground()), pose.last().pose(),
                sheetSize, lift, pixel * LINE_WIDTH, packedLight);

        for (BattlePlot.GunRange gun : plot.guns()) {
            pen.color = GUN_COLOR;
            double x = sheet.pixelX(gun.x()) / sheet.sizeInPixels() * sheetSize;
            double y = sheet.pixelY(gun.z()) / sheet.sizeInPixels() * sheetSize;
            double radius = gun.radius() * scale;
            pen.circle(x, y, radius, pixel * DASH, pixel * DASH_GAP);
            // Where the barrel points, out to the edge of its reach.
            if (gun.hasAim())
                pen.line(x, y, x + gun.aimX() * radius, y + gun.aimZ() * radius,
                        pixel * AIM_DASH, pixel * AIM_GAP);
        }
        for (BattlePlot.RangefinderFix fix : plot.fixes()) {
            pen.color = RANGEFINDER_COLOR;
            double x = sheet.pixelX(fix.x()) / sheet.sizeInPixels() * sheetSize;
            double y = sheet.pixelY(fix.z()) / sheet.sizeInPixels() * sheetSize;
            pen.circle(x, y, SIGMA_CIRCLE * fix.sigma() * scale, pixel * DOT, pixel * DOT_GAP);
            // The rangefinder's line of sight, from where it stands to its aim point.
            pen.line(sheet.pixelX(fix.originX()) / sheet.sizeInPixels() * sheetSize,
                    sheet.pixelY(fix.originZ()) / sheet.sizeInPixels() * sheetSize,
                    x, y, pixel * AIM_DASH, pixel * AIM_GAP);
        }
    }

    /** Emits thin quads, both sides, clipped to the sheet piece by piece. */
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

        /** A circle of dashes, each covering {@code dash} of every {@code dash + gap} along it. */
        void circle(double cx, double cy, double radius, float dash, float gap) {
            if (radius < width)
                return;
            double circumference = 2 * Math.PI * radius;
            int patterns = (int) Math.min(MAX_PATTERNS, Math.max(8, Math.round(circumference / (dash + gap))));
            double patternAngle = 2 * Math.PI / patterns;
            double dashAngle = patternAngle * dash / (dash + gap);
            double inner = radius - width / 2, outer = radius + width / 2;
            for (int i = 0; i < patterns; i++) {
                double a0 = i * patternAngle, a1 = a0 + dashAngle;
                double mid = (a0 + a1) / 2;
                if (!onSheet(cx + Math.cos(mid) * radius, cy + Math.sin(mid) * radius))
                    continue;
                double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
                quad(cx + c0 * inner, cy + s0 * inner, cx + c0 * outer, cy + s0 * outer,
                        cx + c1 * outer, cy + s1 * outer, cx + c1 * inner, cy + s1 * inner);
            }
        }

        /** A straight line of dashes from (x0, y0) to (x1, y1), starting with a dash at (x0, y0). */
        void line(double x0, double y0, double x1, double y1, float dash, float gap) {
            double dx = x1 - x0, dy = y1 - y0;
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length < dash)
                return;
            double ux = dx / length, uy = dy / length;
            // Half the stroke width across the line.
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

        private boolean onSheet(double x, double y) {
            return x >= 0 && y >= 0 && x <= sheetSize && y <= sheetSize;
        }

        private void quad(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            vertex(x0, y0);
            vertex(x1, y1);
            vertex(x2, y2);
            vertex(x3, y3);
            // And the other side, so culling cannot hide it whichever way the pose faces.
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
