package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Consumer;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoVisual extends KineticBlockEntityVisual<KineticBlockEntity> {

    protected final RotatingInstance shaft;
    protected final RotatingInstance gizmo;
    final Direction direction;
    private final Direction opposite;
    private final boolean isOutput;

    public CannonServoVisual(VisualizationContext context, KineticBlockEntity blockEntity, float partialTick, boolean isOutput) {
        super(context, blockEntity, partialTick);
        this.isOutput = isOutput;

        Direction facing = blockState.getValue(FACING);
        // Input: shaft comes from behind (opposite to FACING), gizmo on the face toward FACING
        // Output: shaft goes out front (along FACING), gizmo on the face away from FACING
        // In both cases, the visual's "direction" is the direction the shaft points toward.
        if (isOutput) {
            facing = facing.getOpposite();
        }

        direction = facing;
        opposite = direction.getOpposite();

        shaft = instancerProvider()
                .instancer(AllInstanceTypes.ROTATING, Models.partial(AllPartialModels.SHAFT_HALF))
                .createInstance();
        gizmo = instancerProvider()
                .instancer(AllInstanceTypes.ROTATING,
                        Models.partial(isOutput ? CDCPartialModels.SERVO_OUTPUT_COUPLER : CDCPartialModels.SERVO_INPUT_COUPLER))
                .createInstance();

        shaft.setup(blockEntity)
                .setPosition(getVisualPosition())
                .rotateToFace(Direction.SOUTH, opposite)
                .setChanged();

        gizmo.setup(blockEntity)
                .setPosition(getVisualPosition())
                .rotateToFace(Direction.SOUTH, opposite)
                .setChanged();
    }

    @Override
    public void update(float pt) {
        shaft.setup(blockEntity).setChanged();
        gizmo.setup(blockEntity).setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        BlockPos behind = pos.relative(opposite);
        relight(behind, shaft);

        BlockPos inFront = pos.relative(direction);
        relight(inFront, gizmo);
    }

    @Override
    protected void _delete() {
        shaft.delete();
        gizmo.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(shaft);
        consumer.accept(gizmo);
    }
}