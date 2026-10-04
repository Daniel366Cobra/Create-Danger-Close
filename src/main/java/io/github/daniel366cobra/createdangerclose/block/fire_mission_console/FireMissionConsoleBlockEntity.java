package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Fire Mission Console: the gunnery officer's station. Shows the fire
 * mission (target, trajectory mode, each gun's solution) and gives the orders:
 * the fuze correction knob, the trajectory lever and the fire button on its top.
 *
 * <p>A Battle Network Interface facing it connects it, like the Navigation
 * Console: each time that interface polls, the console finds the Ballistic
 * Computer on the network. The trajectory mode lives in the computer, so every
 * console shows the same; the fuze correction is the console's own, and the
 * computer takes it from whichever console set it last.
 */
public class FireMissionConsoleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // The link lapses this long after the interface's last poll
    // (a powered interface polls at least every 20 ticks).
    private static final int LINK_TIMEOUT_TICKS = 40;

    /** The fuze correction knob's range either way, ticks (2 s). */
    public static final int MAX_FUZE_OFFSET_TICKS = 40;

    /** Block event: the fire button was pressed (animates it on clients). */
    static final int BUTTON_PRESS_EVENT = 0;

    // Client: the controls ease to their positions; the button springs back slowly.
    private static final float LEVER_EASING = 0.35f;
    private static final float KNOB_EASING = 0.4f;
    private static final float BUTTON_RETURN_EASING = 0.15f;

    private boolean linked = false;
    private long lastLinkTick;
    // The Ballistic Computer on the network, if any.
    @Nullable private BlockPos computerPos;

    // The fuze correction this console sets, ticks, and when it was last set
    // (the most recently set console wins).
    private int fuzeOffsetTicks;
    private long fuzeOffsetSetTick = Long.MIN_VALUE;

    // From the computer, for the readouts and the lever (synced).
    private boolean plunging;
    private int gunsReady, gunsTotal;

    // Ponder scenes only: the readouts show a mission set by the scene, with no computer.
    private boolean ponderMission;

    private final LerpedFloat lever = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat knob = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat button = LerpedFloat.linear().startWithValue(0);

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
        boolean changed = !linked || !Objects.equals(found, computerPos);
        linked = true;
        computerPos = found;
        if (computer != null)
            computer.offerFuzeOffset(fuzeOffsetTicks, fuzeOffsetSetTick);
        if (readComputer(computer) || changed)
            sendData();
    }

    /** Takes the mode and the guns' readiness from the computer; whether they changed. */
    private boolean readComputer(@Nullable BallisticComputerBlockEntity computer) {
        boolean newPlunging = computer != null && computer.isPlungingFire();
        BallisticComputerBlockEntity.SalvoResult guns = computer != null
                ? computer.gunsOnTarget() : new BallisticComputerBlockEntity.SalvoResult(0, 0);
        int newReady = guns.fired(), newTotal = guns.fired() + guns.held();
        boolean changed = newPlunging != plunging || newReady != gunsReady || newTotal != gunsTotal;
        plunging = newPlunging;
        gunsReady = newReady;
        gunsTotal = newTotal;
        return changed;
    }

    /** After an order at the console: show its effect at once rather than at the next poll (server). */
    void refreshFromComputer() {
        if (readComputer(getComputer()))
            sendData();
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null)
            return;
        if (level.isClientSide()) {
            tickControls();
            return;
        }
        // The interface stopped polling (unpowered, turned away, removed).
        if (linked && level.getGameTime() - lastLinkTick > LINK_TIMEOUT_TICKS) {
            linked = false;
            computerPos = null;
            readComputer(null);
            sendData();
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

    // --- Fuze correction ---

    public int getFuzeOffsetTicks() {
        return fuzeOffsetTicks;
    }

    /** Turns the knob by this many ticks (server); the computer takes it at once if connected. */
    public void adjustFuzeOffset(int deltaTicks) {
        setFuzeOffset(fuzeOffsetTicks + deltaTicks);
    }

    void setFuzeOffset(int ticks) {
        if (level == null)
            return;
        int clamped = Mth.clamp(ticks, -MAX_FUZE_OFFSET_TICKS, MAX_FUZE_OFFSET_TICKS);
        fuzeOffsetSetTick = level.getGameTime();
        if (clamped != fuzeOffsetTicks) {
            fuzeOffsetTicks = clamped;
            setChanged();
            sendData();
        }
        BallisticComputerBlockEntity computer = getComputer();
        if (computer != null)
            computer.offerFuzeOffset(fuzeOffsetTicks, fuzeOffsetSetTick);
    }

    /** A fuze correction as the console prints it: signed seconds, one decimal. */
    public static String formatFuzeOffset(int ticks) {
        return String.format(Locale.ROOT, "%+.1f", ticks / 20f);
    }

    // --- Readouts and controls (client) ---

    public boolean isPlunging() {
        return plunging;
    }

    public int getGunsReady() {
        return gunsReady;
    }

    public int getGunsTotal() {
        return gunsTotal;
    }

    private void tickControls() {
        lever.chase(plunging ? 1 : 0, LEVER_EASING, LerpedFloat.Chaser.EXP);
        lever.tickChaser();
        knob.chase(fuzeOffsetTicks, KNOB_EASING, LerpedFloat.Chaser.EXP);
        knob.tickChaser();
        button.chase(0, BUTTON_RETURN_EASING, LerpedFloat.Chaser.EXP);
        button.tickChaser();
    }

    /** The trajectory lever, 0 (up: direct fire) to 1 (down: plunging fire). */
    public float getLeverThrow(float partialTicks) {
        return lever.getValue(partialTicks);
    }

    /** The fuze knob's turn, -1 (earliest) to 1 (latest). */
    public float getKnobTurn(float partialTicks) {
        return knob.getValue(partialTicks) / MAX_FUZE_OFFSET_TICKS;
    }

    /** The fire button, 0 (out) to 1 (pressed in). */
    public float getButtonPress(float partialTicks) {
        return button.getValue(partialTicks);
    }

    /** Pushes the fire button in; it springs back by itself (client). */
    public void animateButtonPress() {
        button.setValue(1);
    }

    /** Whether the readouts show a fire mission: connected to a computer (or shown in a Ponder scene). */
    public boolean showsMission() {
        return ponderMission || getComputer() != null;
    }

    /** Ponder scenes only: sets the trajectory the lever and readout show, as a computer would. */
    public void showPlunging(boolean plunging) {
        ponderMission = true;
        this.plunging = plunging;
    }

    /** Ponder scenes only: sets how many guns the readout shows on target, as a computer would. */
    public void showGunsReady(int ready, int total) {
        ponderMission = true;
        gunsReady = ready;
        gunsTotal = total;
    }

    /** Ponder scenes only: turns the knob, as scrolling would. */
    public void showFuzeOffset(int ticks) {
        fuzeOffsetTicks = Mth.clamp(ticks, -MAX_FUZE_OFFSET_TICKS, MAX_FUZE_OFFSET_TICKS);
    }

    @Override
    public boolean triggerEvent(int id, int param) {
        if (id != BUTTON_PRESS_EVENT)
            return super.triggerEvent(id, param);
        if (level != null && level.isClientSide())
            animateButtonPress();
        return true;
    }

    // --- NBT ---

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        fuzeOffsetTicks = Mth.clamp(compound.getInt("FuzeOffset"), -MAX_FUZE_OFFSET_TICKS, MAX_FUZE_OFFSET_TICKS);
        if (!clientPacket) {
            fuzeOffsetSetTick = compound.contains("FuzeOffsetSet") ? compound.getLong("FuzeOffsetSet") : Long.MIN_VALUE;
            return;
        }
        linked = compound.getBoolean("Linked");
        computerPos = compound.contains("Computer") ? NbtUtils.readBlockPos(compound, "Computer").orElse(null) : null;
        plunging = compound.getBoolean("Plunging");
        gunsReady = compound.getInt("GunsReady");
        gunsTotal = compound.getInt("GunsTotal");
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putInt("FuzeOffset", fuzeOffsetTicks);
        if (!clientPacket) {
            compound.putLong("FuzeOffsetSet", fuzeOffsetSetTick);
            return;
        }
        // The link and the readouts lapse or refresh on their own: only clients need them.
        compound.putBoolean("Linked", linked);
        if (computerPos != null)
            compound.put("Computer", NbtUtils.writeBlockPos(computerPos));
        compound.putBoolean("Plunging", plunging);
        compound.putInt("GunsReady", gunsReady);
        compound.putInt("GunsTotal", gunsTotal);
    }

    // --- Tooltip ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console.title"))
                .forGoggles(tooltip);
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console.fuze_offset",
                        formatFuzeOffset(fuzeOffsetTicks)))
                .style(ChatFormatting.GRAY)
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
