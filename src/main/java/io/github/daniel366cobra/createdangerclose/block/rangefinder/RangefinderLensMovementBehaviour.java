package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.daniel366cobra.createdangerclose.client.rangefinder.RangefinderLensActorVisual;
import io.github.daniel366cobra.createdangerclose.client.rangefinder.RangefinderLensRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/** Only draws the lens on a tube, pitched to the bearing's lens elevation; the tube leaves it out of its mesh. */
public class RangefinderLensMovementBehaviour implements MovementBehaviour {

    @Override
    @OnlyIn(Dist.CLIENT)
    public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
            ContraptionMatrices matrices, MultiBufferSource buffer) {
        if (!VisualizationManager.supportsVisualization(context.world))
            RangefinderLensRenderer.renderInContraption(context, renderWorld, matrices, buffer);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    @Nullable
    public ActorVisual createVisual(VisualizationContext visualizationContext, VirtualRenderWorld simulationWorld,
            MovementContext movementContext) {
        return new RangefinderLensActorVisual(visualizationContext, simulationWorld, movementContext);
    }
}