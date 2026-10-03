package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import java.util.Objects;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The Fire Mission Console: the gunnery officer's station. Shows the fire
 * mission (target, trajectory mode, each gun's solution) and gives the orders:
 * the trajectory switch and the fire button on its top.
 *
 * <p>A Battle Network Interface facing it connects it, like the Navigation
 * Console: each time that interface polls, the console finds the Ballistic
 * Computer on the network. The console holds no fire control state of its
 * own; the mode lives in the computer, so every console shows the same.
 */
public class FireMissionConsoleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // The link lapses this long after the interface's last poll
    // (a powered interface polls at least every 20 ticks).
    private static final int LINK_TIMEOUT_TICKS = 40;

    private boolean linked = false;
    private long lastLinkTick;
    // The Ballistic Computer on the network, if any (synced: clients read the
    // fire mission straight from it).
    @Nullable private BlockPos computerPos;

    public FireMissionConsoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** Called by a powered network interface facing the console, each time it polls. */
    public void pollFrom(NetworkInterfaceBlockEntity networkInterface) {
        if (level == null || level.isClientSide())
            return;
        lastLinkTick = level.getGameTime();
        BallisticComputerBlockEntity computer = networkInterface.findComputer();
        BlockPos found = computer != null ? computer.getBlockPos() : null;
        if (!linked || !Objects.equals(found, computerPos)) {
            linked = true;
            computerPos = found;
            notifyUpdate();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        // The interface stopped polling (unpowered, turned away, removed).
        if (linked && level.getGameTime() - lastLinkTick > LINK_TIMEOUT_TICKS) {
            linked = false;
            computerPos = null;
            notifyUpdate();
        }
    }

    public boolean isLinked() {
        return linked;
    }

    /** The Ballistic Computer this console gives orders to, if connected and loaded. */
    @Nullable
    public BallisticComputerBlockEntity getComputer() {
        if (level == null || !linked || computerPos == null)
            return null;
        return level.getBlockEntity(computerPos) instanceof BallisticComputerBlockEntity computer ? computer : null;
    }

    // --- NBT ---

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        linked = compound.getBoolean("Linked");
        computerPos = compound.contains("Computer") ? NbtUtils.readBlockPos(compound, "Computer").orElse(null) : null;
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        // The link lapses on its own: only clients need it.
        if (!clientPacket)
            return;
        compound.putBoolean("Linked", linked);
        if (computerPos != null)
            compound.put("Computer", NbtUtils.writeBlockPos(computerPos));
    }

    // --- Tooltip ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console.title"))
                .forGoggles(tooltip);

        if (!linked) {
            line(tooltip, "not_connected", ChatFormatting.RED);
            return true;
        }
        if (computerPos == null) {
            line(tooltip, "no_computer", ChatFormatting.RED);
            return true;
        }
        BallisticComputerBlockEntity computer = getComputer();
        if (computer == null) {
            line(tooltip, "computer_unloaded", ChatFormatting.DARK_GRAY);
            return true;
        }
        computer.addFireMissionTooltip(tooltip);
        return true;
    }

    private static void line(List<Component> tooltip, String key, ChatFormatting style) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console." + key))
                .style(style)
                .forGoggles(tooltip);
    }
}
