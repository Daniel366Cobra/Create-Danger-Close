package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.daniel366cobra.createdangerclose.client.RenderLight;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoRenderer extends KineticBlockEntityRenderer<KineticBlockEntity> {

    private final boolean isOutput;
    private final PartialModel inputPart, outputPart, transferPart;

    private CannonServoRenderer(BlockEntityRendererProvider.Context context, boolean isOutput,
                                PartialModel inputPart, PartialModel outputPart, PartialModel transferPart) {
        super(context);
        this.isOutput = isOutput;
        this.inputPart = inputPart;
        this.outputPart = outputPart;
        this.transferPart = transferPart;
    }

    private CannonServoRenderer(BlockEntityRendererProvider.Context context, boolean isOutput) {
        this(context, isOutput, CDCPartialModels.SERVO_INPUT_COUPLER, CDCPartialModels.SERVO_OUTPUT_COUPLER,
                CDCPartialModels.SERVO_TRANSFER_COUPLER);
    }

    public static CannonServoRenderer input(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, false);
    }

    public static CannonServoRenderer output(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, true);
    }

    /** The Kinetic Network Coupler: the same transmission, its own parts. */
    public static CannonServoRenderer couplerInput(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, false, CDCPartialModels.COUPLER_INPUT_COUPLER,
                CDCPartialModels.COUPLER_OUTPUT_COUPLER, CDCPartialModels.COUPLER_TRANSFER_COUPLER);
    }

    public static CannonServoRenderer couplerOutput(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, true, CDCPartialModels.COUPLER_INPUT_COUPLER,
                CDCPartialModels.COUPLER_OUTPUT_COUPLER, CDCPartialModels.COUPLER_TRANSFER_COUPLER);
    }

    @Override
    protected void renderSafe(KineticBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) return;

        Direction direction = be.getBlockState().getValue(FACING);
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        int lightBehind = RenderLight.at(be.getLevel(), be.getBlockPos().relative(direction.getOpposite()));
        int lightInFront = RenderLight.at(be.getLevel(), be.getBlockPos().relative(direction));

        SuperByteBuffer shaftHalf = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, be.getBlockState(),
                isOutput ? direction : direction.getOpposite());
        // Coupler partials are modelled in the servo item's frame (input block at
        // z 0..16, output block at z -16..0), anchored at the input block: same
        // orientation for both, the output's drawn one block back towards the input.
        SuperByteBuffer gizmo = CachedBuffers.partialFacing(
                isOutput ? outputPart : inputPart,
                be.getBlockState(),
                direction.getOpposite());

        standardKineticRotationTransform(shaftHalf, be, lightBehind).renderInto(ms, vb);
        ms.pushPose();
        if (isOutput)
            ms.translate(-direction.getStepX(), -direction.getStepY(), -direction.getStepZ());
        standardKineticRotationTransform(gizmo, be, lightInFront).renderInto(ms, vb);

        // Output only: the planet carrier, between the sun (input) and ring (output) speeds.
        if (isOutput) {
            BlockPos inputPos = be.getBlockPos().relative(direction.getOpposite());
            float sun = be.getLevel().getBlockEntity(inputPos) instanceof KineticBlockEntity input ? input.getSpeed() : 0;
            float speed = (sun + 2 * be.getSpeed()) / 3;
            Direction.Axis axis = direction.getAxis();
            float time = AnimationTickHolder.getRenderTime(be.getLevel());
            float offset = getRotationOffsetForPosition(be, be.getBlockPos(), axis);
            float angle = ((time * speed * 3f / 10 + offset) % 360) / 180 * (float) Math.PI;
            SuperByteBuffer carrier = CachedBuffers.partialFacing(
                    transferPart, be.getBlockState(), direction.getOpposite());
            kineticRotationTransform(carrier, be, axis, angle, lightBehind).renderInto(ms, vb);
        }
        ms.popPose();
    }
}