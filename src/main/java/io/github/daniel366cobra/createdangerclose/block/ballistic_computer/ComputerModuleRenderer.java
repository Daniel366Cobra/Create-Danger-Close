package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The moving parts of the computer modules. Positions are model pixels with
 * the face towards north, as in the models.
 */
public class ComputerModuleRenderer extends SafeBlockEntityRenderer<ComputerModuleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Differentiator: the differential turns about its east-west axis through (8, 8, 5)...
    private static final float DIFFERENTIAL_PIVOT_Y = 8, DIFFERENTIAL_PIVOT_Z = 5;
    // ...and the tracking flag tilts about the axis out of the face through (6, 10, 0.75),
    // a quarter turn clockwise (seen from in front) from red to green.
    private static final float FLAG_PIVOT_X = 6, FLAG_PIVOT_Y = 10, FLAG_PIVOT_Z = 0.75f;
    private static final float FLAG_GREEN_DEG = 90;

    public ComputerModuleRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    protected void renderSafe(ComputerModuleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof ComputerModuleBlock block))
            return;
        Direction front = state.getValue(ComputerModuleBlock.FACING);
        // The block itself is solid: light the parts by the space in front of it.
        int partLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(front));
        // The models face north; the blockstate turns them like this.
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        if (block.getModule() == ComputerModule.DIFFERENTIATOR) {
            SuperByteBuffer differential = CachedBuffers.partial(CDCPartialModels.COMPUTER_DIFFERENTIAL, state);
            differential.rotateCentered(faceTurn, Direction.UP)
                    .translate(0, DIFFERENTIAL_PIVOT_Y * PX, DIFFERENTIAL_PIVOT_Z * PX)
                    .rotate((float) Math.toRadians(be.getDifferentialAngle(partialTicks)), Direction.EAST)
                    .translate(0, -DIFFERENTIAL_PIVOT_Y * PX, -DIFFERENTIAL_PIVOT_Z * PX)
                    .light(partLight)
                    .renderInto(ms, vb);

            SuperByteBuffer flag = CachedBuffers.partial(CDCPartialModels.COMPUTER_TRACKING_FLAG, state);
            flag.rotateCentered(faceTurn, Direction.UP)
                    .translate(FLAG_PIVOT_X * PX, FLAG_PIVOT_Y * PX, FLAG_PIVOT_Z * PX)
                    // About +Z: positive is clockwise as seen from in front.
                    .rotate((float) Math.toRadians(FLAG_GREEN_DEG * be.getTrackingFlag(partialTicks)), Direction.SOUTH)
                    .translate(-FLAG_PIVOT_X * PX, -FLAG_PIVOT_Y * PX, -FLAG_PIVOT_Z * PX)
                    .light(partLight)
                    .renderInto(ms, vb);
        }
    }
}
