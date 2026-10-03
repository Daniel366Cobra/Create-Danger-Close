package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;

/**
 * A readout window on a computer cabinet's front, with text printed in it in
 * Minecraft's font (until the windows get drum art). Positions are model
 * pixels with the face towards north, as in the models.
 *
 * @param west   the window's west edge (x)
 * @param east   its east edge (x)
 * @param bottom its bottom edge (y)
 * @param top    its top edge (y)
 * @param depth  how far behind the face the text stands (z)
 * @param fill   share of the window's width the text fills
 */
record PanelText(float west, float east, float bottom, float top, float depth, float fill) {

    private static final float PX = 1 / 16f;
    private static final int COLOR = 0xFF000000;
    // Height of a digit's ink, font pixels.
    private static final int GLYPH_HEIGHT = 7;

    /**
     * Prints the text centred in the window, scaled to fill its width. Keep
     * the text a fixed length (pad it) so the size does not jump about.
     */
    void draw(Font font, PoseStack ms, MultiBufferSource buffer, Direction front, String text, int light) {
        float width = east - west;
        float height = top - bottom;
        // Font pixels to model pixels; a glyph's ink is one pixel narrower than its advance.
        int inkWidth = font.width(text) - 1;
        float scale = width * fill / inkWidth;

        ms.pushPose();
        // The model faces north; the blockstate turns it like this.
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(-(front.toYRot() + 180)));
        ms.translate(-0.5, -0.5, -0.5);
        // The window's top left as seen from in front: its east, top edge.
        ms.translate(east * PX, top * PX, depth * PX);
        // Text runs along +x, down +y, and faces +z: turn it to face north, the right way up.
        ms.mulPose(Axis.YP.rotationDegrees(180));
        ms.scale(scale * PX, -scale * PX, scale * PX);
        float x = (width / scale - inkWidth) / 2;
        float y = (height / scale - GLYPH_HEIGHT) / 2;
        font.drawInBatch(text, x, y, COLOR, false, ms.last().pose(), buffer, Font.DisplayMode.NORMAL, 0, light);
        ms.popPose();
    }
}
