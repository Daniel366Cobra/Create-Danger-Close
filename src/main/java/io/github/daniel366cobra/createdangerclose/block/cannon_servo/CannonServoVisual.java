package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visual.TickableVisual;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleTickableVisual;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoVisual extends KineticBlockEntityVisual<KineticBlockEntity> implements SimpleTickableVisual {

    // Planetary set between the two networks: the input drives the sun, the
    // output the ring, and the planet carrier runs at the speed in between,
    // weighted by size (sun radius 2.5 px, ring 5 px in the model).
    private static final float SUN_WEIGHT = 1, RING_WEIGHT = 2;

    protected final RotatingInstance shaft;
    protected final RotatingInstance gizmo;
    // Output only: the planet carrier.
    @Nullable protected final RotatingInstance carrier;
    private float carrierSpeed;
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

        // The coupler partials are modelled in the servo item's frame: the input
        // block at z 0..16, the output block at z -16..0, the input shaft to the
        // south. All are drawn in that frame, anchored at the input block, so
        // the output's partials are placed one block back towards the input.
        Direction towardsInputShaft = blockState.getValue(FACING).getOpposite();
        BlockPos inputBlock = isOutput ? getVisualPosition().relative(towardsInputShaft) : getVisualPosition();
        gizmo.setup(blockEntity)
                .setPosition(inputBlock)
                .rotateToFace(Direction.SOUTH, towardsInputShaft)
                .setChanged();

        if (isOutput) {
            carrierSpeed = computeCarrierSpeed();
            carrier = instancerProvider()
                    .instancer(AllInstanceTypes.ROTATING, Models.partial(CDCPartialModels.SERVO_TRANSFER_COUPLER))
                    .createInstance();
            carrier.setup(blockEntity, carrierSpeed)
                    .setPosition(inputBlock)
                    .rotateToFace(Direction.SOUTH, towardsInputShaft)
                    .setChanged();
        } else {
            carrier = null;
        }
    }

    /** Output only: the carrier's speed from the sun (input network) and the ring (output network). */
    private float computeCarrierSpeed() {
        float sun = level.getBlockEntity(pos.relative(direction)) instanceof KineticBlockEntity input
                ? input.getSpeed() : 0;
        float ring = blockEntity.getSpeed();
        return (SUN_WEIGHT * sun + RING_WEIGHT * ring) / (SUN_WEIGHT + RING_WEIGHT);
    }

    /**
     * The input's speed can change without this (the output's) visual being
     * updated, so the carrier's speed is checked every tick.
     */
    @Override
    public void tick(TickableVisual.Context context) {
        if (carrier == null)
            return;
        float speed = computeCarrierSpeed();
        if (speed != carrierSpeed) {
            carrierSpeed = speed;
            carrier.setup(blockEntity, carrierSpeed).setChanged();
        }
    }

    @Override
    public void update(float pt) {
        shaft.setup(blockEntity).setChanged();
        gizmo.setup(blockEntity).setChanged();
        if (carrier != null) {
            carrierSpeed = computeCarrierSpeed();
            carrier.setup(blockEntity, carrierSpeed).setChanged();
        }
    }

    @Override
    public void updateLight(float partialTick) {
        BlockPos behind = pos.relative(opposite);
        relight(behind, shaft);

        BlockPos inFront = pos.relative(direction);
        relight(inFront, gizmo);
        if (carrier != null)
            relight(inFront, carrier);
    }

    @Override
    protected void _delete() {
        shaft.delete();
        gizmo.delete();
        if (carrier != null)
            carrier.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(shaft);
        consumer.accept(gizmo);
        if (carrier != null)
            consumer.accept(carrier);
    }
}
