package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A computer module's moving parts. Holds no data of its own: on the client it
 * follows the Ballistic Computer of its bank and animates what that computer
 * is doing; the server never ticks it for anything.
 */
public class ComputerModuleBlockEntity extends SmartBlockEntity {

    // How often the module looks its computer up again (the bank may change).
    private static final int COMPUTER_LOOKUP_INTERVAL_TICKS = 10;

    // Differentiator: the differential turns with the target's speed, one turn
    // a second at 10 blocks a second (36° per tick per block per tick), up to a cap.
    private static final float DIFFERENTIAL_DEG_PER_TICK_PER_SPEED = 36;
    private static final float DIFFERENTIAL_MAX_DEG_PER_TICK = 45;
    // ...and spins up and down smoothly, while the tracking flag flips quickly.
    private static final float DIFFERENTIAL_SPIN_EASING = 0.1f;
    private static final float FLAG_EASING = 0.4f;

    @Nullable private BlockPos computerPos;
    private int ticksToLookup;

    private final LerpedFloat differentialRate = LerpedFloat.linear().startWithValue(0);
    private float differentialAngle;
    private float prevDifferentialAngle;
    private final LerpedFloat trackingFlag = LerpedFloat.linear().startWithValue(0);

    public ComputerModuleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    @Override
    public void tick() {
        super.tick();
        if (level == null || !level.isClientSide())
            return;

        if (--ticksToLookup <= 0) {
            ticksToLookup = COMPUTER_LOOKUP_INTERVAL_TICKS;
            BallisticComputerBlockEntity found = ComputerBank.findComputer(level, worldPosition);
            computerPos = found != null ? found.getBlockPos() : null;
        }
        BallisticComputerBlockEntity computer = computerPos != null
                && level.getBlockEntity(computerPos) instanceof BallisticComputerBlockEntity be ? be : null;
        boolean tracking = computer != null && computer.isTrackingTarget();
        double speed = tracking ? computer.getTargetSpeed() : 0;

        float rate = (float) Math.min(speed * DIFFERENTIAL_DEG_PER_TICK_PER_SPEED, DIFFERENTIAL_MAX_DEG_PER_TICK);
        differentialRate.chase(rate, DIFFERENTIAL_SPIN_EASING, LerpedFloat.Chaser.EXP);
        differentialRate.tickChaser();
        prevDifferentialAngle = differentialAngle;
        differentialAngle = (differentialAngle + differentialRate.getValue()) % 360;

        trackingFlag.chase(tracking ? 1 : 0, FLAG_EASING, LerpedFloat.Chaser.EXP);
        trackingFlag.tickChaser();
    }

    /** Differentiator: the differential's turn, degrees (client). */
    public float getDifferentialAngle(float partialTicks) {
        float previous = prevDifferentialAngle;
        // Across the wrap from 359 to 0, interpolate the short way.
        if (differentialAngle < previous)
            previous -= 360;
        return previous + (differentialAngle - previous) * partialTicks;
    }

    /** Differentiator: the tracking flag, 0 (red, not tracking) to 1 (green, tracking) (client). */
    public float getTrackingFlag(float partialTicks) {
        return trackingFlag.getValue(partialTicks);
    }
}
