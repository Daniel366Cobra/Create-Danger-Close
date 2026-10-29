package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ActorVisual;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.level.block.state.BlockState;

/** A tube's lens under Flywheel: the block model, pitched about the baseline through its center. */
public class RangefinderLensActorVisual extends ActorVisual {

    private BlockState state;
    private TransformedInstance lens;

    public RangefinderLensActorVisual(VisualizationContext visualizationContext, VirtualRenderWorld simulationWorld,
            MovementContext context) {
        super(visualizationContext, simulationWorld, context);
        state = RangefinderLensRenderer.currentState(context);
        lens = createLens();
    }

    private TransformedInstance createLens() {
        return instancerProvider.instancer(InstanceTypes.TRANSFORMED, Models.block(state)).createInstance();
    }

    @Override
    public void beginFrame() {
        BlockState current = RangefinderLensRenderer.currentState(context);
        if (current != state) {
            lens.delete();
            state = current;
            lens = createLens();
        }
        RangefinderLensRenderer.pitch(lens.setIdentityTransform().translate(context.localPos), state,
                        RangefinderLensRenderer.lensPitch(context, AnimationTickHolder.getPartialTicks()))
                .light(localBlockLight(), 0)
                .setChanged();
    }

    @Override
    protected void _delete() {
        lens.delete();
    }
}