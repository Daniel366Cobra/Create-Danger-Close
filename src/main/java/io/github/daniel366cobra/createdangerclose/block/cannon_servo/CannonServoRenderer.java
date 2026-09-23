package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonCommonShellPropertiesHandler;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoRenderer extends KineticBlockEntityRenderer<KineticBlockEntity> {

    private final boolean isOutput;

    private CannonServoRenderer(BlockEntityRendererProvider.Context context, boolean isOutput) {
        super(context);
        this.isOutput = isOutput;
    }

    public static CannonServoRenderer input(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, false);
    }

    public static CannonServoRenderer output(BlockEntityRendererProvider.Context ctx) {
        return new CannonServoRenderer(ctx, true);
    }

    @Override
    protected void renderSafe(KineticBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        if (VisualizationManager.supportsVisualization(be.getLevel())) return;

        Direction direction = be.getBlockState().getValue(FACING);
        VertexConsumer vb = buffer.getBuffer(RenderType.cutoutMipped());

        int lightBehind = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(direction.getOpposite()));
        int lightInFront = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(direction));

        SuperByteBuffer shaftHalf = CachedBuffers.partialFacing(
                AllPartialModels.SHAFT_HALF, be.getBlockState(),
                isOutput ? direction : direction.getOpposite());
        SuperByteBuffer gizmo = CachedBuffers.partialFacing(
                isOutput ? CDCPartialModels.SERVO_OUTPUT_COUPLER : CDCPartialModels.SERVO_INPUT_COUPLER,
                be.getBlockState(),
                isOutput ? direction : direction.getOpposite());

        standardKineticRotationTransform(shaftHalf, be, lightBehind).renderInto(ms, vb);
        standardKineticRotationTransform(gizmo, be, lightInFront).renderInto(ms, vb);
    }
}