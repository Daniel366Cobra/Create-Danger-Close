package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/** Front panel: bearing needle, range counter, ballistic cam and follower. Model pixels, face toward north. */
public class BallisticComputerRenderer extends SafeBlockEntityRenderer<BallisticComputerBlockEntity> {

    private static final float PX = 1 / 16f;

    // Pivot of the needle, about the face normal.
    private static final float NEEDLE_PIVOT_X = 10.5f, NEEDLE_PIVOT_Y = 10.5f, NEEDLE_PIVOT_Z = 0.5f;
    // East-west axis through this point.
    private static final float CAM_PIVOT_Y = 5.5f, CAM_PIVOT_Z = 4.5f;
    // Slides west from zero range to the gun's reach.
    private static final float FOLLOWER_TRAVEL = 8;

    // x 3..7, y 9..12, 1 px deep; digits 0.25 px off the back wall.
    private static final PanelText RANGE_WINDOW = PanelText.front(3, 7, 9, 12, 0.75f, 0.9f);
    private static final int RANGE_DIGITS = 4;
    private static final int RANGE_MAX = 9999;

    private final Font font;

    public BallisticComputerRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    protected void renderSafe(BallisticComputerBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction front = state.getValue(BallisticComputerBlock.FACING);
        // Solid block: light by the space in front.
        int panelLight = RenderLight.at(be.getLevel(), be.getBlockPos().relative(front));
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        SuperByteBuffer needle = CachedBuffers.partial(CDCPartialModels.COMPUTER_BEARING_NEEDLE, state);
        needle.rotateCentered(faceTurn, Direction.UP)
                .translate(NEEDLE_PIVOT_X * PX, NEEDLE_PIVOT_Y * PX, NEEDLE_PIVOT_Z * PX)
                // Clockwise from in front.
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

    /** Four digits. */
    private void renderRange(BallisticComputerBlockEntity be, float partialTicks, PoseStack ms,
                             MultiBufferSource buffer, Direction front, int light) {
        int range = Mth.clamp(Math.round(be.getRangeCounter(partialTicks)), 0, RANGE_MAX);
        RANGE_WINDOW.draw(font, ms, buffer, front, String.format("%0" + RANGE_DIGITS + "d", range), light);
    }
}