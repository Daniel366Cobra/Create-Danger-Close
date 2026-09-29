package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity.FireControlStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The view through the rangefinder eyepiece, drawn like vanilla's spyglass
 * overlay: a square mask sized to the screen's short side and centred, with
 * the rest of the screen filled in the mask's edge colour, so it never
 * stretches on any aspect ratio.
 *
 * <p>The reticle and markers are white textures tinted in code: dark
 * ("etched") when the reticle lamp is off, lit amber when the rangefinder
 * bearing receives a redstone signal, brighter with a stronger signal.
 *
 * <p>Everything is drawn in the mask's texel space: one pose transform maps
 * mask texels to the screen, and the reticle and markers are placed at texel
 * coordinates inside it. That keeps them aligned to each other exactly, with
 * no per-texture rounding to whole GUI pixels.
 */
public final class RangefinderOverlay {

    private static final ResourceLocation MASK = CreateDangerClose.asResource("textures/gui/rangefinder/scope_overlay.png");
    private static final ResourceLocation RETICLE = CreateDangerClose.asResource("textures/gui/rangefinder/scope_reticle.png");
    private static final ResourceLocation MARKER = CreateDangerClose.asResource("textures/gui/rangefinder/scope_marker.png");
    private static final ResourceLocation BRACKETS = CreateDangerClose.asResource("textures/gui/rangefinder/scope_brackets.png");

    // Texture sizes, in texels. All textures share the mask's texel scale.
    private static final int MASK_PX = 512;
    private static final int RETICLE_W = 376, RETICLE_H = 86;
    private static final int MARKER_W = 20, MARKER_H = 116;
    private static final int BRACKETS_W = 36, BRACKETS_H = 42;
    // Marker distance from the centre when fully apart, in mask texels
    // (the reticle's second major tick).
    private static final float MARKER_MAX_OFFSET_PX = 124;
    // The mask's opaque edge colour, used to fill the rest of the screen.
    private static final int FILL_COLOR = 0xFF000000;

    // Readout layout, in mask texels: gap between the markers' chevrons and
    // the text, and the rate column's left edge (the reticle's first major tick).
    private static final float READOUT_GAP_PX = 6;
    private static final float RATE_COLUMN_PX = 62;
    // Shown in place of a value that is not available yet.
    private static final String UNAVAILABLE = "--";

    // Marking tints, RGB: etched (lamp off) and fully illuminated.
    private static final float[] ETCHED = {0.08f, 0.08f, 0.08f};
    private static final float[] ILLUMINATED = {1.0f, 0.62f, 0.2f};
    // Lamp brightness at signal 1; signal 15 is full brightness.
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
        // From here on, one unit is one mask texel.
        pose.translate(left, top, 0);
        pose.scale(size / MASK_PX, size / MASK_PX, 1);

        RenderSystem.enableBlend();
        drawAt(graphics, MASK, 0, 0, MASK_PX, MASK_PX);

        float[] tint = markingTint();
        graphics.setColor(tint[0], tint[1], tint[2], 1f);
        drawAt(graphics, RETICLE, center - RETICLE_W / 2f, center - RETICLE_H / 2f, RETICLE_W, RETICLE_H);

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        float markerOffset = RangefinderSight.getMarkerSeparation(partialTick) * MARKER_MAX_OFFSET_PX;
        float markerTop = center - MARKER_H / 2f;
        drawAt(graphics, MARKER, center - markerOffset - MARKER_W / 2f, markerTop, MARKER_W, MARKER_H);
        drawAt(graphics, MARKER, center + markerOffset - MARKER_W / 2f, markerTop, MARKER_W, MARKER_H);

        // "Ranging" brackets around the central diamond while the button is held.
        if (RangefinderSight.isRanging())
            drawAt(graphics, BRACKETS, center - BRACKETS_W / 2f, center - BRACKETS_H / 2f, BRACKETS_W, BRACKETS_H);
        graphics.setColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        pose.popPose();

        drawReadouts(graphics, left, top, size / MASK_PX, tint);

        // Fill around the mask. Rounded outwards onto the mask's opaque edge,
        // so no sliver of the world shows when the mask sits on a half pixel.
        int innerLeft = Mth.ceil(left), innerRight = Mth.floor(left + size);
        int innerTop = Mth.ceil(top), innerBottom = Mth.floor(top + size);
        graphics.fill(RenderType.guiOverlay(), 0, 0, width, innerTop, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), 0, innerBottom, width, height, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), 0, innerTop, innerLeft, innerBottom, FILL_COLOR);
        graphics.fill(RenderType.guiOverlay(), innerRight, innerTop, width, innerBottom, FILL_COLOR);
    }

    /**
     * Bearing above the central diamond, range below it, range rate and
     * bearing rate in a column to the right of the range. Laid out in mask
     * texels but drawn in GUI pixels, so the font stays crisp.
     */
    private static void drawReadouts(GuiGraphics graphics, float left, float top, float texel, float[] tint) {
        Font font = Minecraft.getInstance().font;
        int color = 0xFF000000
                | Math.round(tint[0] * 255) << 16
                | Math.round(tint[1] * 255) << 8
                | Math.round(tint[2] * 255);
        float center = MASK_PX / 2f;
        int centerX = Math.round(left + center * texel);
        // Clear of the markers' chevrons, which reach MARKER_H / 2 above and below.
        int bearingY = Math.round(top + (center - MARKER_H / 2f - READOUT_GAP_PX) * texel) - font.lineHeight;
        int rangeY = Math.round(top + (center + MARKER_H / 2f + READOUT_GAP_PX) * texel);
        int rateX = Math.round(left + (center + RATE_COLUMN_PX) * texel);

        RangefinderBearingBlockEntity bearing = RangefinderSight.getOperatedBearing();
        Double trueBearing = RangefinderSight.getBearing();
        // The server clears readouts once the images drift apart; gating on the
        // local prediction as well blanks them the instant the markers split.
        boolean hasRange = bearing != null && bearing.hasRange() && RangefinderSight.canRange();
        boolean hasVelocity = bearing != null && bearing.hasVelocity() && RangefinderSight.canTrackVelocity();

        drawCentered(graphics, font, readout("bearing", trueBearing != null
                ? format("%05.1f°", trueBearing) : null), centerX, bearingY, color);
        drawCentered(graphics, font, readout("range", hasRange
                ? format("%.0f m", bearing.getRange()) : null), centerX, rangeY, color);
        graphics.drawString(font, readout("range_rate", hasVelocity
                ? format("%+.1f m/s", bearing.getRangeRate()) : null), rateX, rangeY, color, false);
        graphics.drawString(font, readout("bearing_rate", hasVelocity
                ? format("%+.2f°/s", bearing.getBearingRate()) : null), rateX, rangeY + font.lineHeight + 1, color, false);

        // Fire control role, mirroring the rate column on the left: whether
        // the guns follow this rangefinder (DIR), merge its readings (MRG), or
        // another rangefinder is directing (STBY).
        FireControlStatus status = bearing != null ? bearing.getFireControlStatus() : FireControlStatus.NONE;
        if (status != FireControlStatus.NONE) {
            Component label = Component.translatable(
                    "createdangerclose.rangefinder.readout.status." + status.name().toLowerCase(Locale.ROOT));
            int statusRight = Math.round(left + (center - RATE_COLUMN_PX) * texel);
            graphics.drawString(font, label, statusRight - font.width(label), rangeY, color, false);
        }
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

    /**
     * Draws a whole texture at a (possibly fractional) texel position in the
     * current pose, at its native texel size.
     */
    private static void drawAt(GuiGraphics graphics, ResourceLocation texture, float x, float y, int w, int h) {
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        graphics.blit(texture, 0, 0, 0, 0f, 0f, w, h, w, h);
        pose.popPose();
    }

    /** Etched when the reticle lamp is off, amber scaled by the redstone signal when on. */
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
