package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The Cannon Servo's input half, on the battle network: takes the computer's commands for
 * its output half, and charges the battle network what the gun side draws.
 */
public class CannonServoInputBlockEntity extends PairInputBlockEntity implements ComputerServo {
    private static final long COMMAND_TIMEOUT = 40;
    @Nullable private ServoStatus cachedStatus;
    @Nullable private FireSolution currentCommand;
    private long lastCommandTick = 0;

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
        return getPairedOutput() instanceof CannonServoOutputBlockEntity output ? output : null;
    }
}