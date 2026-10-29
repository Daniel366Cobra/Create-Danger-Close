package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity.FireControlStatus;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The eyepiece view, laid out like vanilla's spyglass: a square mask on the short side, the rest
 * filled. White textures tinted in code: etched with the lamp off, amber by redstone signal.
 * Everything is placed in mask texels under one pose transform, so nothing rounds apart.
 */
public final class RangefinderOverlay {

    private static final ResourceLocation MASK = CreateDangerClose.asResource("textures/gui/rangefinder/scope_overlay.png");
    private static final ResourceLocation CRACKS = CreateDangerClose.asResource("textures/gui/rangefinder/scope_overlay_cracks.png");
    private static final ResourceLocation DUST = CreateDangerClose.asResource("textures/gui/rangefinder/scope_overlay_dust.png");
    private static final ResourceLocation RETICLE =CreateDangerClose.asResource("textures/gui/rangefinder/scope_reticle.png");
    private static final ResourceLocation MARKER = CreateDangerClose.asResource("textures/gui/rangefinder/scope_marker.png");
    private static final ResourceLocation BRACKETS = CreateDangerClose.asResource("textures/gui/rangefinder/scope_brackets.png");

    // Texels; all share the mask's scale.
    private static final int MASK_PX = 512;
    private static final int RETICLE_W = 376, RETICLE_H = 86;
    private static final int MARKER_W = 20, MARKER_H = 116;
    private static final int BRACKETS_W = 36, BRACKETS_H = 42;
    // Fully apart, in mask texels (the reticle's second major tick).
    private static final float MARKER_MAX_OFFSET_PX = 124;
    private static final int FILL_COLOR = 0xFF000000;

    // Mask texels: gap from the chevrons, and the rate column's edge (first major tick).
    private static final float READOUT_GAP_PX = 6;
    private static final float RATE_COLUMN_PX = 62;
    private static final String UNAVAILABLE = "--";

    // The bearing scale: half its width in mask texels, and the degrees that spans either side.
    private static final float SCALE_HALF_WIDTH_PX = 120;
    private static final double SCALE_HALF_SPAN_DEG = 45;
    // GUI pixels: tick heights every 5° and 10°, and at the labeled points every 45°.
    private static final int MINOR_TICK = 2, MAJOR_TICK = 4, POINT_TICK = 6;
    // The outer quarter of each half fades out.
    private static final float SCALE_FADE = 0.25f;
    private static final String[] COMPASS_POINTS = {"north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest"};
    // Every 90° from the bow.
    private static final String[] RELATIVE_POINTS = {"bow", "starboard", "stern", "port"};
    // GUI pixels between a label row and the next (7-row letters and a gap), and between readouts.
    private static final int LABEL_ROW = 9, READOUT_SPACING = 8;

    // RGB.
    private static final float[] ETCHED = {0.08f, 0.08f, 0.08f};
    private static final float[] ILLUMINATED = {1.0f, 0.62f, 0.2f};
    // At signal 1; 15 is full.
    private static final float MIN_LAMP_BRIGHTNESS = 0.3f;

    private RangefinderOverlay() {}

    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!RangefinderSight.isViewActive())
            return;

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float size = Math.min(width, height);
        float left = (width - size) / 2f;
        float top = (height - size) / 2f;
        float center = MASK_PX / 2f;

        PoseStack pose = graphics.pose();
        pose.pushPose();
        // Units are mask texels from here.
        pose.translate(left, top, 0);
        pose.scale(size / MASK_PX, size / MASK_PX, 1);

        RenderSystem.enableBlend();
        // Under the mask, so its rim covers them; the second lens mirrored.
        RangefinderContraption tube = RangefinderSight.getOperatedTube();
        List<BlockPos> lenses = tube != null ? tube.getLenses() : List.of();
        // Dust as thick as on each lens, cracks over it.
        RangefinderBearingBlockEntity bearing = RangefinderSight.getOperatedBearing();
        for (int i = 0; i < lenses.size() && bearing != null; i++) {
            float dust = bearing.getLensDust(i);
            if (dust <= 0)
                continue;
            boolean mirrored = i == 1;
            graphics.setColor(1, 1, 1, dust);
            graphics.blit(DUST, 0, 0, MASK_PX, MASK_PX, mirrored ? MASK_PX : 0, 0,
                    mirrored ? -MASK_PX : MASK_PX, MASK_PX, MASK_PX, MASK_PX);
        }
        graphics.setColor(1, 1, 1, 1);
        for (int i = 0; i < lenses.size(); i++) {
            if (!tube.isLensCracked(lenses.get(i)))
                continue;
            boolean mirrored = i == 1;
            graphics.blit(CRACKS, 0, 0, MASK_PX, MASK_PX, mirrored ? MASK_PX : 0, 0,
                    mirrored ? -MASK_PX : MASK_PX, MASK_PX, MASK_PX, MASK_PX);
        }
        drawAt(graphics, MASK, 0, 0, MASK_PX, MASK_PX);

        float[] tint = markingTint();
        graphics.setColor(tint[0], tint[1], tint[2], 1f);
        drawAt(graphics, RETICLE, center - RETICLE_W / 2f, center - RETICLE_H / 2f, RETICLE_W, RETICLE_H);

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        float markerOffset = RangefinderSight.getMarkerSeparation(partialTick) * MARKER_MAX_OFFSET_PX;
        float markerTop = center - MARKER_H / 2f;
        drawAt(graphics, MARKER, center - markerOffset - MARKER_W / 2f, markerTop, MARKER_W, MARKER_H);
        drawAt(graphics, MARKER, center + markerOffset - MARKER_W / 2f, markerTop, MARKER_W, MARKER_H);

        if (RangefinderSight.isRanging())
            drawAt(graphics, BRACKETS, center - BRACKETS_W / 2f, center - BRACKETS_H / 2f, BRACKETS_W, BRACKETS_H);
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        pose.popPose();

        drawReadouts(graphics, left, top, size / MASK_PX, tint);

        // Rounded outward onto the opaque edge, so no world shows at half pixels.
        int innerLeft = Mth.ceil(left), innerRight = Mth.floor(left + size);
        int innerTop = Mth.ceil(top), innerBottom = Mth.floor(top + size);
        graphics.fill(RenderType.guiOverlay(), 0, 0, width, innerTop, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), 0, innerBottom, width, height, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), 0, innerTop, innerLeft, innerBottom, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), innerRight, innerTop, width, innerBottom, FILL_COLOR);
    }

    /** Laid out in mask texels, drawn in GUI pixels so the font stays crisp. */
    private static void drawReadouts(GuiGraphics graphics, float left, float top, float texel, float[] tint) {
        Font font = Minecraft.getInstance().font;
        int color = 0xFF000000
                | Math.round(tint[0] * 255) << 16
                | Math.round(tint[1] * 255) << 8
                | Math.round(tint[2] * 255);
        float center = MASK_PX / 2f;
        int centerX = Math.round(left + center * texel);
        // Above the markers: the bearing scale's line and ticks, compass labels, bow labels, then the angle readouts.
        int scaleLineY = Math.round(top + (center - MARKER_H / 2f - READOUT_GAP_PX) * texel);
        int compassLabelY = scaleLineY - POINT_TICK - 2 - 7;
        int relativeLabelY = compassLabelY - LABEL_ROW;
        int anglesY = relativeLabelY - 3 - font.lineHeight;
        int rangeY = Math.round(top + (center + MARKER_H / 2f + READOUT_GAP_PX) * texel);
        int rateX = Math.round(left + (center + RATE_COLUMN_PX) * texel);

        RangefinderBearingBlockEntity bearing = RangefinderSight.getOperatedBearing();
        Double trueBearing = RangefinderSight.getBearing();
        Double relativeBearing = RangefinderSight.getRelativeBearing();
        Double elevation = RangefinderSight.getElevation();
        // Also gate on the local prediction, so readouts blank the instant the markers split.
        boolean hasRange = bearing != null && bearing.hasRange() && RangefinderSight.canRange();
        boolean hasVelocity = bearing != null && bearing.hasVelocity() && RangefinderSight.canTrackVelocity();

        drawBearingScale(graphics, font, trueBearing, relativeBearing, centerX, scaleLineY,
                compassLabelY, trueBearing != null ? relativeLabelY : compassLabelY,
                Math.round(SCALE_HALF_WIDTH_PX * texel), color);
        // BRG, REL with a Navigation Console, ELE: one row centered over the scale.
        List<Component> angles = new ArrayList<>();
        angles.add(readout("bearing", trueBearing != null ? format("%05.1f°", trueBearing) : null));
        if (relativeBearing != null)
            angles.add(readout("bearing_relative", format("%05.1f°", relativeBearing)));
        angles.add(readout("elevation", elevation != null ? format("%+.1f°", elevation) : null));
        int anglesWidth = READOUT_SPACING * (angles.size() - 1);
        for (Component angle : angles)
            anglesWidth += font.width(angle);
        int angleX = centerX - anglesWidth / 2;
        for (Component angle : angles) {
            graphics.drawString(font, angle, angleX, anglesY, color, false);
            angleX += font.width(angle) + READOUT_SPACING;
        }
        drawCentered(graphics, font, readout("range", hasRange
                ? format("%.0f m", bearing.getRange()) : null), centerX, rangeY, color);
        graphics.drawString(font, readout("range_rate", hasVelocity
                ? format("%+.1f m/s", bearing.getRangeRate()) : null), rateX, rangeY, color, false);
        graphics.drawString(font, readout("bearing_rate", hasVelocity
                ? format("%+.2f°/s", bearing.getBearingRate()) : null), rateX, rangeY + font.lineHeight + 1, color, false);

        // Fire control role: DIR, MRG or STBY.
        FireControlStatus status = bearing != null ? bearing.getFireControlStatus() : FireControlStatus.NONE;
        if (status != FireControlStatus.NONE) {
            Component label = Component.translatable(
                    "createdangerclose.rangefinder.readout.status." + status.name().toLowerCase(Locale.ROOT));
            int statusRight = Math.round(left + (center - RATE_COLUMN_PX) * texel);
            graphics.drawString(font, label, statusRight - font.width(label), rangeY, color, false);
        }
    }

    /**
     * A line with ticks every 5°, sliding with the bearing; the reticle's center mark reads it. Compass
     * points label it every 45° when north is known; the bow's every 90°, a row above, with a Navigation
     * Console. Ticks follow north if known, else the bow. Fades out toward its ends; none for neither.
     */
    private static void drawBearingScale(GuiGraphics graphics, Font font, @Nullable Double trueBearing,
                                         @Nullable Double relativeBearing, int centerX, int lineY, int compassLabelY,
                                         int relativeLabelY, int halfWidth, int color) {
        Double reference = trueBearing != null ? trueBearing : relativeBearing;
        if (reference == null)
            return;
        double pxPerDeg = halfWidth / SCALE_HALF_SPAN_DEG;
        for (int dx = -halfWidth; dx <= halfWidth; dx++)
            graphics.fill(centerX + dx, lineY, centerX + dx + 1, lineY + 1, faded(color, dx, halfWidth));

        double first = Math.ceil((reference - SCALE_HALF_SPAN_DEG) / 5) * 5;
        for (double deg = first; deg <= reference + SCALE_HALF_SPAN_DEG; deg += 5) {
            int dx = (int) Math.round((deg - reference) * pxPerDeg);
            int tickColor = faded(color, dx, halfWidth);
            if (tickColor >>> 24 < 8)
                continue;
            int marked = Math.floorMod((int) Math.round(deg), 360);
            int height = marked % 45 == 0 ? POINT_TICK : marked % 10 == 0 ? MAJOR_TICK : MINOR_TICK;
            graphics.fill(centerX + dx, lineY - height, centerX + dx + 1, lineY, tickColor);
            if (trueBearing != null && marked % 45 == 0)
                drawScaleLabel(graphics, font, COMPASS_POINTS[marked / 45], centerX + dx, compassLabelY, tickColor);
        }

        if (relativeBearing == null)
            return;
        for (int i = 0; i < RELATIVE_POINTS.length; i++) {
            double offset = Mth.wrapDegrees(i * 90 - relativeBearing);
            int dx = (int) Math.round(offset * pxPerDeg);
            int labelColor = faded(color, dx, halfWidth);
            if (Math.abs(offset) <= SCALE_HALF_SPAN_DEG && labelColor >>> 24 >= 8)
                drawScaleLabel(graphics, font, RELATIVE_POINTS[i], centerX + dx, relativeLabelY, labelColor);
        }
    }

    private static void drawScaleLabel(GuiGraphics graphics, Font font, String point, int x, int y, int color) {
        Component label = Component.translatable("createdangerclose.rangefinder.scale." + point);
        graphics.drawString(font, label, x - (font.width(label) - 1) / 2, y, color, false);
    }

    /** The color with its alpha faded over the scale's outer ends. */
    private static int faded(int color, int dx, int halfWidth) {
        float fade = Mth.clamp((halfWidth - Math.abs(dx)) / (halfWidth * SCALE_FADE), 0, 1);
        return Math.round(fade * 255) << 24 | color & 0xFFFFFF;
    }

    private static Component readout(String key, @Nullable String value) {
        return Component.translatable("createdangerclose.rangefinder.readout." + key,
                value != null ? value : UNAVAILABLE);
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }

    private static void drawCentered(GuiGraphics graphics, Font font, Component text, int centerX, int y, int color) {
        graphics.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    /** At native texel size, at a possibly fractional position. */
    private static void drawAt(GuiGraphics graphics, ResourceLocation texture, float x, float y, int w, int h) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        graphics.blit(texture, 0, 0, 0, 0f, 0f, w, h, w, h);
        pose.popPose();
    }

    private static float[] markingTint() {
        RangefinderBearingBlockEntity bearing = RangefinderSight.getOperatedBearing();
        int signal = bearing != null ? bearing.getIllumination() : 0;
        if (signal <= 0)
            return ETCHED;
        float brightness = Mth.lerp((signal - 1) / 14f, MIN_LAMP_BRIGHTNESS, 1f);
        return new float[]{
                ILLUMINATED[0] * brightness,
                ILLUMINATED[1] * brightness,
                ILLUMINATED[2] * brightness};
    }
}