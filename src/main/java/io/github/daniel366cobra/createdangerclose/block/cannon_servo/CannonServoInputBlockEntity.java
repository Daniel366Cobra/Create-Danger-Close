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
    private CannonServoOutputBlockEntity getAdjacentOutput() {
        if (level == null) return null;
        BlockPos outputPos = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(outputPos) instanceof CannonServoOutputBlockEntity out ? out : null;
    }
}
