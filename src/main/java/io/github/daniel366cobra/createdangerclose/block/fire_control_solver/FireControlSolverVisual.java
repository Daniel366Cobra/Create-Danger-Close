package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Consumer;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;

public class FireControlSolverVisual extends KineticBlockEntityVisual<FireControlSolverBlockEntity> {
    protected final RotatingInstance shaft;
    final Direction direction;
    private final Direction opposite;

    public FireControlSolverVisual(VisualizationContext context, FireControlSolverBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick);

        direction = blockState.getValue(FACING);

        opposite = direction.getOpposite();
        shaft = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                .createInstance();

        shaft.setup(blockEntity)
                .setPosition(getVisualPosition())
                .rotateToFace(Direction.SOUTH, opposite)
                .setChanged();
    }

    @Override
    public void update(float pt) {
        shaft.setup(blockEntity)
                .setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        BlockPos behind = pos.relative(opposite);
        relight(behind, shaft);
    }

    @Override
    protected void _delete() {
        shaft.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(shaft);
    }
}