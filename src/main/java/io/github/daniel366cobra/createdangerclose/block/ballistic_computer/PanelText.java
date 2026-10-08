package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;

/** Text in a cabinet's readout window, in Minecraft's font. Model pixels, front toward north. */
public final class PanelText {

    private static final float PX = 1 / 16f;
    private static final int COLOR = 0xFF000000;
    // Font pixels.
    private static final int GLYPH_HEIGHT = 7;

    private final boolean onTop;
    private final float west, east;
    // Front: bottom and top edges (y). Top: front and back edges (z).
    private final float lowEdge, highEdge;
    // Front: depth behind the face (z). Top: height (y).
    private final float plane;
    private final float fill;

    private PanelText(boolean onTop, float west, float east, float lowEdge, float highEdge, float plane, float fill) {
        this.onTop = onTop;
        this.west = west;
        this.east = east;
        this.lowEdge = lowEdge;
        this.highEdge = highEdge;
        this.plane = plane;
        this.fill = fill;
    }

    /**
     * @param depth behind the face (z)
     * @param fill  share of the window's width
     */
    public static PanelText front(float west, float east, float bottom, float top, float depth, float fill) {
        return new PanelText(false, west, east, bottom, top, depth, fill);
    }

    /**
     * A horizontal window read from in front: its front edge (lower z) is the text's bottom.
     *
     * @param height y
     * @param fill   share of the window's width
     */
    public static PanelText top(float west, float east, float frontEdge, float backEdge, float height, float fill) {
        return new PanelText(true, west, east, frontEdge, backEdge, height, fill);
    }

    /** Centered, scaled to fill the width: pad the text to a fixed length so the size holds. */
    public void draw(Font font, PoseStack ms, MultiBufferSource buffer, Direction front, String text, int light) {
        draw(font, ms, buffer, front, text, text, light);
    }

    /** Sized by a template, so different widths print the same size. */
    public void draw(Font font, PoseStack ms, MultiBufferSource buffer, Direction front, String text,
                     String sizeTemplate, int light) {
        float width = east - west;
        float height = highEdge - lowEdge;
        // A glyph's ink is one pixel narrower than its advance.
        float scale = width * fill / (font.width(sizeTemplate) - 1);
        float inkWidth = font.width(text) - 1;

        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(-(front.toYRot() + 180)));
        ms.translate(-0.5, -0.5, -0.5);
        // Text runs +x, down +y, facing +z: start at the reader's top left (east edge) and turn it to face them.
        if (onTop) {
            ms.translate(east * PX, plane * PX, highEdge * PX);
            ms.mulPose(Axis.YP.rotationDegrees(180));
            ms.mulPose(Axis.XP.rotationDegrees(-90));
        } else {
            ms.translate(east * PX, highEdge * PX, plane * PX);
            ms.mulPose(Axis.YP.rotationDegrees(180));
        }
        ms.scale(scale * PX, -scale * PX, scale * PX);
        float x = (width / scale - inkWidth) / 2;
        float y = (height / scale - GLYPH_HEIGHT) / 2;
        font.drawInBatch(text, x, y, COLOR, false, ms.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, light);
        ms.popPose();
    }
}