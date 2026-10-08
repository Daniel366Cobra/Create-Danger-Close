package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** Model pixels, face toward north. */
public class ComputerModuleRenderer extends SafeBlockEntityRenderer<ComputerModuleBlockEntity> {

    private static final float PX = 1 / 16f;

    // Differentiator: the differential turns about its east-west axis through (8, 8, 5)...
    private static final float DIFFERENTIAL_PIVOT_Y = 8, DIFFERENTIAL_PIVOT_Z = 5;
    // ...and the flag tilts about the face normal through (6, 10, 0.75), a quarter turn clockwise to green.
    private static final float FLAG_PIVOT_X = 6, FLAG_PIVOT_Y = 10, FLAG_PIVOT_Z = 0.75f;
    private static final float FLAG_GREEN_DEG = 90;

    // Integrator pivots: disk vertical through (8, 4.5, 5); ball east-west through (8, 6, 5), riding the
    // carriage; output shaft east-west through (8, 8, 5).
    private static final float DISK_PIVOT_X = 8, DISK_PIVOT_Z = 5;
    private static final float BALL_PIVOT_Y = 6, BALL_PIVOT_Z = 5;
    private static final float OUTPUT_SHAFT_PIVOT_Y = 8, OUTPUT_SHAFT_PIVOT_Z = 5;
    // x = 11 (expired) to x = 4 (full).
    private static final float HOLD_NEEDLE_FULL_TRAVEL_PX = 4 - 11;

    // Correlator: needles swing apart about the face normal through (8, 5.5); the second copy sits
    // nearer the glass (z 0.25) so they never share a plane.
    private static final float COINCIDENCE_PIVOT_X = 8, COINCIDENCE_PIVOT_Y = 5.5f, COINCIDENCE_PIVOT_Z = 0.75f;
    private static final float COINCIDENCE_MAX_DEG = 50;
    private static final float COINCIDENCE_FRONT_NEEDLE_Z = 0.25f - 0.75f;

    // Fuze Computer: ring about the face normal through (8, 7, 0.75), clockwise as time grows. Readout
    // window x 6..10, y 4..7.
    private static final float RING_PIVOT_X = 8, RING_PIVOT_Y = 7, RING_PIVOT_Z = 0.75f;
    private static final PanelText FUZE_WINDOW = PanelText.front(6, 10, 4, 7, 0.74f - 0.25f, 0.9f);
    // Four digits (the longest flight solved is 1200).
    private static final int FUZE_MAX_TICKS = 9999;

    private final Font font;

    public ComputerModuleRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
    }

    @Override
    protected void renderSafe(ComputerModuleBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof ComputerModuleBlock block))
            return;
        Direction front = state.getValue(ComputerModuleBlock.FACING);
        // Solid block: light by the space in front.
        int partLight = RenderLight.at(be.getLevel(), be.getBlockPos().relative(front));
        float faceTurn = (float) Math.toRadians(-(front.toYRot() + 180));
        Parts parts = new Parts(state, faceTurn, partLight, ms, buffer.getBuffer(RenderType.cutoutMipped()));

        switch (block.getModule()) {
            case DIFFERENTIATOR -> renderDifferentiator(be, partialTicks, parts);
            case INTEGRATOR -> renderIntegrator(be, partialTicks, parts);
            case CORRELATOR -> renderCorrelator(be, partialTicks, parts);
            case FUZE_COMPUTER -> renderFuzeComputer(be, partialTicks, parts, ms, buffer, front, partLight);
            default -> { }
        }
    }

    private static void renderDifferentiator(ComputerModuleBlockEntity be, float partialTicks, Parts parts) {
        parts.start(CDCPartialModels.COMPUTER_DIFFERENTIAL)
                .translate(0, DIFFERENTIAL_PIVOT_Y * PX, DIFFERENTIAL_PIVOT_Z * PX)
                .rotate(rad(be.getDifferentialAngle(partialTicks)), Direction.EAST)
                .translate(0, -DIFFERENTIAL_PIVOT_Y * PX, -DIFFERENTIAL_PIVOT_Z * PX);
        parts.finish();

        parts.start(CDCPartialModels.COMPUTER_TRACKING_FLAG)
                .translate(FLAG_PIVOT_X * PX, FLAG_PIVOT_Y * PX, FLAG_PIVOT_Z * PX)
                // Clockwise from in front.
                .rotate(rad(FLAG_GREEN_DEG * be.getTrackingFlag(partialTicks)), Direction.SOUTH)
                .translate(-FLAG_PIVOT_X * PX, -FLAG_PIVOT_Y * PX, -FLAG_PIVOT_Z * PX);
        parts.finish();
    }

    private static void renderIntegrator(ComputerModuleBlockEntity be, float partialTicks, Parts parts) {
        parts.start(CDCPartialModels.COMPUTER_INTEGRATOR_DISK)
                .translate(DISK_PIVOT_X * PX, 0, DISK_PIVOT_Z * PX)
                .rotate(rad(be.getDiskAngle(partialTicks)), Direction.UP)
                .translate(-DISK_PIVOT_X * PX, 0, -DISK_PIVOT_Z * PX);
        parts.finish();

        float carriageOffset = be.getCarriageOffset(partialTicks) * PX;
        parts.start(CDCPartialModels.COMPUTER_INTEGRATOR_CARRIAGE)
                .translate(carriageOffset, 0, 0);
        parts.finish();

        parts.start(CDCPartialModels.COMPUTER_INTEGRATOR_BALL)
                .translate(carriageOffset, BALL_PIVOT_Y * PX, BALL_PIVOT_Z * PX)
                .rotate(rad(be.getBallAngle(partialTicks)), Direction.EAST)
                .translate(0, -BALL_PIVOT_Y * PX, -BALL_PIVOT_Z * PX);
        parts.finish();

        parts.start(CDCPartialModels.COMPUTER_INTEGRATOR_OUTPUT_SHAFT)
                .translate(0, OUTPUT_SHAFT_PIVOT_Y * PX, OUTPUT_SHAFT_PIVOT_Z * PX)
                .rotate(rad(be.getOutputShaftAngle(partialTicks)), Direction.EAST)
                .translate(0, -OUTPUT_SHAFT_PIVOT_Y * PX, -OUTPUT_SHAFT_PIVOT_Z * PX);
        parts.finish();

        parts.start(CDCPartialModels.COMPUTER_INTEGRATOR_HOLD_NEEDLE)
                .translate(HOLD_NEEDLE_FULL_TRAVEL_PX * PX * be.getHoldNeedle(partialTicks), 0, 0);
        parts.finish();
    }

    private static void renderCorrelator(ComputerModuleBlockEntity be, float partialTicks, Parts parts) {
        float swing = COINCIDENCE_MAX_DEG * be.getCoincidenceSpread(partialTicks);
        for (int side : new int[] {1, -1}) {
            parts.start(CDCPartialModels.COMPUTER_COINCIDENCE_NEEDLE)
                    .translate(0, 0, side < 0 ? COINCIDENCE_FRONT_NEEDLE_Z * PX : 0)
                    .translate(COINCIDENCE_PIVOT_X * PX, COINCIDENCE_PIVOT_Y * PX, COINCIDENCE_PIVOT_Z * PX)
                    .rotate(rad(side * swing), Direction.SOUTH)
                    .translate(-COINCIDENCE_PIVOT_X * PX, -COINCIDENCE_PIVOT_Y * PX, -COINCIDENCE_PIVOT_Z * PX);
            parts.finish();
        }
    }

    private void renderFuzeComputer(ComputerModuleBlockEntity be, float partialTicks, Parts parts,
                                    PoseStack ms, MultiBufferSource buffer, Direction front, int light) {
        parts.start(CDCPartialModels.COMPUTER_FUZE_SETTING_RING)
                .translate(RING_PIVOT_X * PX, RING_PIVOT_Y * PX, RING_PIVOT_Z * PX)
                .rotate(rad(be.getSettingRingAngle(partialTicks)), Direction.SOUTH)
                .translate(-RING_PIVOT_X * PX, -RING_PIVOT_Y * PX, -RING_PIVOT_Z * PX);
        parts.finish();

        int ticks = Math.clamp(Math.round(be.getFuzeTicks(partialTicks)), 0, FUZE_MAX_TICKS);
        FUZE_WINDOW.draw(font, ms, buffer, front, String.format(Locale.ROOT, "%04d", ticks), light);
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** Turned with the block, lit by the space in front. */
    private static final class Parts {
        private final BlockState state;
        private final float faceTurn;
        private final int light;
        private final PoseStack ms;
        private final VertexConsumer vb;
        private SuperByteBuffer current;

        Parts(BlockState state, float faceTurn, int light, PoseStack ms, VertexConsumer vb) {
            this.state = state;
            this.faceTurn = faceTurn;
            this.light = light;
            this.ms = ms;
            this.vb = vb;
        }

    /** Transforms after this are in model space. */
        SuperByteBuffer start(PartialModel partial) {
            current = CachedBuffers.partial(partial, state).rotateCentered(faceTurn, Direction.UP);
            return current;
        }

        void finish() {
            current.light(light).renderInto(ms, vb);
        }
    }
}