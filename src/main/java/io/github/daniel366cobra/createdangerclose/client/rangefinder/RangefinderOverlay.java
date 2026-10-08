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
        int bearingY = Math.round(top + (center - MARKER_H / 2f - READOUT_GAP_PX) * texel) - font.lineHeight;
        int rangeY = Math.round(top + (center + MARKER_H / 2f + READOUT_GAP_PX) * texel);
        int rateX = Math.round(left + (center + RATE_COLUMN_PX) * texel);

        RangefinderBearingBlockEntity bearing = RangefinderSight.getOperatedBearing();
        Double trueBearing = RangefinderSight.getBearing();
        // Also gate on the local prediction, so readouts blank the instant the markers split.
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

        // Fire control role: DIR, MRG or STBY.
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