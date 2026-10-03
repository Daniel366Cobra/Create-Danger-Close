package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Ballistic Computer's front panel: the bearing needle, the range counter
 * and the ballistic cam with its follower, behind the windows of the face.
 * Positions are model pixels with the face towards north, as in the model.
 */
public class BallisticComputerRenderer extends SafeBlockEntityRenderer<BallisticComputerBlockEntity> {

    private static final float PX = 1 / 16f;

    // Bearing needle: turns about the dial's axis (out of the face) through this point.
    private static final float NEEDLE_PIVOT_X = 10.5f, NEEDLE_PIVOT_Y = 10.5f, NEEDLE_PIVOT_Z = 0.5f;
    // Ballistic cam: turns about its east-west axis through this point.
    private static final float CAM_PIVOT_Y = 5.5f, CAM_PIVOT_Z = 4.5f;
    // Cam follower: slides west along the cam, from rest at zero range to the gun's reach.
    private static final float FOLLOWER_TRAVEL = 8;

    // Range window: x 3..7, y 9..12, 1 px deep; the digits stand 0.25 px off its back wall.
    private static final float RANGE_WINDOW_EAST = 7, RANGE_WINDOW_WEST = 3;
    private static final float RANGE_WINDOW_BOTTOM = 9, RANGE_WINDOW_TOP = 12;
    private static final float RANGE_TEXT_DEPTH = 0.75f;
    // Share of the window's width the four digits fill.
    private static final float RANGE_TEXT_FILL = 0.9f;
    private static final int RANGE_DIGITS = 4;
    private static final int RANGE_MAX = 9999;
    private static final int RANGE_TEXT_COLOR = 0xFF000000;

    private final Font font;

    public BallisticComputerRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    protected void renderSafe(BallisticComputerBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(BallisticComputerBlock.FACING);
        // The block itself is solid: light the panel by the space in front of it.
        int panelLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(front));
        // The model faces north; the blockstate turns it like this.
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        SuperByteBuffer needle = CachedBuffers.partial(CDCPartialModels.COMPUTER_BEARING_NEEDLE, state);
        needle.rotateCentered(faceTurn, Direction.UP)
                .translate(NEEDLE_PIVOT_X * PX, NEEDLE_PIVOT_Y * PX, NEEDLE_PIVOT_Z * PX)
                // About +Z, i.e. clockwise as seen from in front of the face.
                .rotate((float) Math.toRadians(be.getBearingNeedle(partialTicks)), Direction.SOUTH)
                .translate(-NEEDLE_PIVOT_X * PX, -NEEDLE_PIVOT_Y * PX, -NEEDLE_PIVOT_Z * PX)
                .light(panelLight)
                .renderInto(ms, vb);

        SuperByteBuffer cam = CachedBuffers.partial(CDCPartialModels.COMPUTER_CAM_BODY, state);
        cam.rotateCentered(faceTurn, Direction.UP)
                .translate(0, CAM_PIVOT_Y * PX, CAM_PIVOT_Z * PX)
                .rotate((float) Math.toRadians(be.getCamAngle(partialTicks)), Direction.EAST)
                .translate(0, -CAM_PIVOT_Y * PX, -CAM_PIVOT_Z * PX)
                .light(panelLight)
                .renderInto(ms, vb);

        SuperByteBuffer follower = CachedBuffers.partial(CDCPartialModels.COMPUTER_CAM_FOLLOWER, state);
        follower.rotateCentered(faceTurn, Direction.UP)
                .translate(-FOLLOWER_TRAVEL * PX * Mth.clamp(be.getCamFollower(partialTicks), 0, 1), 0, 0)
                .light(panelLight)
                .renderInto(ms, vb);

        renderRange(be, partialTicks, ms, buffer, front, panelLight);
    }

    /** The range counter: four digits printed in the range window, until it gets drums. */
    private void renderRange(BallisticComputerBlockEntity be, float partialTicks, PoseStack ms,
                             MultiBufferSource buffer, Direction front, int light) {
        int range = Mth.clamp(Math.round(be.getRangeCounter(partialTicks)), 0, RANGE_MAX);
        String digits = String.format("%0" + RANGE_DIGITS + "d", range);

        float windowWidth = RANGE_WINDOW_EAST - RANGE_WINDOW_WEST;
        float windowHeight = RANGE_WINDOW_TOP - RANGE_WINDOW_BOTTOM;
        // Font pixels to model pixels; a glyph's ink is one pixel narrower than its advance.
        float scale = windowWidth * RANGE_TEXT_FILL / (font.width(digits) - 1);
        float textHeight = 7 * scale;

        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(-(front.toYRot() + 180)));
        ms.translate(-0.5, -0.5, -0.5);
        // The window's top left as seen from in front: its east, top edge.
        ms.translate(RANGE_WINDOW_EAST * PX, RANGE_WINDOW_TOP * PX, RANGE_TEXT_DEPTH * PX);
        // Text runs along +x, down +y, and faces +z: turn it to face north, the right way up.
        ms.mulPose(Axis.YP.rotationDegrees(180));
        ms.scale(scale * PX, -scale * PX, scale * PX);
        float x = (windowWidth / scale - (font.width(digits) - 1)) / 2;
        float y = (windowHeight - textHeight) / 2 / scale;
        font.drawInBatch(digits, x, y, RANGE_TEXT_COLOR, false, ms.last().pose(), buffer,
                Font.DisplayMode.NORMAL, 0, light);
        ms.popPose();
    }
}
