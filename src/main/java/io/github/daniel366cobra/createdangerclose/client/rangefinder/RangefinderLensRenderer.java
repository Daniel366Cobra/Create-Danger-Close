package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.lib.transform.Rotate;
import dev.engine_room.flywheel.lib.transform.Translate;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensBlock;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/** A tube's lens without Flywheel: the block model, pitched about the baseline through its center. */
public final class RangefinderLensRenderer {

    private RangefinderLensRenderer() {}

    public static void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
            ContraptionMatrices matrices, MultiBufferSource buffer) {
        BlockState state = currentState(context);
        // The model matrix already stands at the lens.
        pitch(CachedBuffers.block(state).transform(matrices.getModel()), state,
                lensPitch(context, AnimationTickHolder.getPartialTicks()))
                .light(LevelRenderer.getLightColor(renderWorld, context.localPos))
                .useLevelLight(context.world, matrices.getWorld())
                .renderInto(matrices.getViewProjection(), buffer.getBuffer(RenderType.cutoutMipped()));
    }

    /** Cracking swaps the block in the tube but not the actor's copy. */
    static BlockState currentState(MovementContext context) {
        StructureBlockInfo info = context.contraption.getBlocks().get(context.localPos);
        return info != null ? info.state() : context.state;
    }

    static float lensPitch(MovementContext context, float partialTicks) {
        return context.contraption instanceof RangefinderContraption tube ? tube.getLensPitch(partialTicks) : 0;
    }

    /** Front up for positive degrees: right-handed about the lens's right side. */
    static <T extends Translate<T> & Rotate<T>> T pitch(T transform, BlockState state, float degrees) {
        return transform.center()
                .rotate(degrees * Mth.DEG_TO_RAD, state.getValue(RangefinderLensBlock.FACING).getClockWise())
                .uncenter();
    }
}