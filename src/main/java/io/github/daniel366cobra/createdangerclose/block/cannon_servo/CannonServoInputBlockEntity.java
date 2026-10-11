package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StringUtil;
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
    public static final int MAX_NAME_LENGTH = 20;
    // Shown for the guns it lays ("Turret A"), unless one closer to them is named too.
    private String name = "";
    // Server: as the computer last said; client: synced, for the goggle tooltip.
    private String shownAs = "";

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

    // --- Name ---

    @Override
    public String getServoName() {
        return name;
    }

    /** Server: trimmed, control characters dropped, cut to {@link #MAX_NAME_LENGTH}. */
    public void setServoName(String name) {
        String clean = StringUtil.filterText(name).strip();
        clean = clean.length() > MAX_NAME_LENGTH ? clean.substring(0, MAX_NAME_LENGTH).strip() : clean;
        if (clean.equals(this.name))
            return;
        this.name = clean;
        setChanged();
        sendData();
    }

    @Override
    public void receiveShownName(@Nullable String shownAs) {
        String shown = shownAs != null ? shownAs : "";
        if (shown.equals(this.shownAs))
            return;
        this.shownAs = shown;
        sendData();
    }

    /** Client too: what its guns show instead, empty if its own. */
    public String getShownAs() {
        return shownAs;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        if (!name.isEmpty())
            compound.putString("Name", name);
        if (clientPacket && !shownAs.isEmpty())
            compound.putString("ShownAs", shownAs);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        name = compound.getString("Name");
        if (clientPacket)
            shownAs = compound.getString("ShownAs");
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