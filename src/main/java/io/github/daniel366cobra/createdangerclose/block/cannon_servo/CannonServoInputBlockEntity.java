package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.api.sensor.ComputerServo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

public class CannonServoInputBlockEntity extends KineticBlockEntity implements ComputerServo {
    private static final long COMMAND_TIMEOUT = 40;
    @Nullable private ServoStatus cachedStatus;
    @Nullable private FireSolution currentCommand;
    private long lastCommandTick = 0;
    // What it charges the battle network, per RPM: set by the output for what the cannon side
    // draws (see CannonServoOutputBlockEntity.updatePower).
    private float drawImpact;

    public CannonServoInputBlockEntity(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;

        CannonServoOutputBlockEntity output = getAdjacentOutput();
        if (output == null) {
            cachedStatus = null;
            currentCommand = null;
            return;
        }

        cachedStatus = output.buildServoStatus();

        if (currentCommand != null && level.getGameTime() - lastCommandTick > COMMAND_TIMEOUT) {
            currentCommand = null;
        }
    }

    /** Charges the battle network this much per RPM from now on (server). */
    void setDrawImpact(float impact) {
        if (Math.abs(impact - drawImpact) <= Math.max(0.01f, drawImpact * 0.02f) && (impact == 0) == (drawImpact == 0))
            return;
        drawImpact = impact;
        // Create subtracts this, as saved, from the network's saved stress on reload: stale, it would
        // leave the difference behind as phantom stress for good.
        lastStressApplied = impact;
        if (hasNetwork())
            getOrCreateNetwork().updateStressFor(this, impact);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = drawImpact;
        return drawImpact;
    }

    /** What the battle network can carry in all, and carries now (su). */
    float getNetworkCapacity() {
        return capacity;
    }

    float getNetworkStress() {
        return stress;
    }

    /** What this servo draws from the battle network now (su). */
    float getDraw() {
        return drawImpact * Math.abs(getSpeed());
    }

    /** Whether this servo is driving its gun (not just holding it), for sharing the network's power. */
    boolean isDriving() {
        CannonServoOutputBlockEntity output = getAdjacentOutput();
        return output != null && output.isDriving();
    }

    @Override
    public ServoStatus getServoStatus() {
        return cachedStatus;
    }

    @Override
    public void receiveFiringSolution(@Nullable FireSolution solution) {
        this.currentCommand = solution;
        this.lastCommandTick = level != null ? level.getGameTime() : 0;
    }

    @Override
    public void receiveElevationSense(int sense) {
        CannonServoOutputBlockEntity output = getAdjacentOutput();
        if (output != null)
            output.setSwivelSense(sense);
    }

    @Nullable
    public FireSolution getCurrentCommand() {
        return currentCommand;
    }

    /** Game time at which the current command was received. */
    public long getCommandTick() {
        return lastCommandTick;
    }

    @Nullable
    public CannonServoOutputBlockEntity getAdjacentOutput() {
        if (level == null) return null;
        BlockPos outputPos = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(outputPos) instanceof CannonServoOutputBlockEntity out ? out : null;
    }
}
