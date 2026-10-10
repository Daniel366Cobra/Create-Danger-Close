package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
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
 * The gunnery officer's station: shows the fire mission; fuze knob, trajectory lever and fire button.
 * Connected by a facing Battle Network Interface. The trajectory lives in the computer; the fuze
 * correction is per console, and the most recently set wins.
 */
public class FireMissionConsoleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // A powered interface polls at least every 20 ticks.
    private static final int LINK_TIMEOUT_TICKS = 40;

    /** Either way, ticks (2 s). */
    public static final int MAX_FUZE_OFFSET_TICKS = 40;

    /** Animates the fire button on clients. */
    static final int BUTTON_PRESS_EVENT = 0;

    // Client: the button springs back slowly.
    private static final float LEVER_EASING = 0.35f;
    private static final float KNOB_EASING = 0.4f;
    private static final float BUTTON_PULL_EASING = 0.6f;
    private static final float BUTTON_RETURN_EASING = 0.15f;

    private boolean linked = false;
    private long lastLinkTick;
    @Nullable private BlockPos computerPos;

    // The most recently set console wins.
    private int fuzeOffsetTicks;
    private long fuzeOffsetSetTick = Long.MIN_VALUE;

    // Synced.
    private boolean plunging;
    private int gunsReady, gunsTotal;

    // Ponder only: a mission set by the scene, with no computer.
    private boolean ponderMission;

    private final LerpedFloat lever = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat knob = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat button = LerpedFloat.linear().startWithValue(0);
    // Client.
    private int buttonHeldTicks;

    public FireMissionConsoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** Called by a powered facing interface each time it polls. */
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
            computer.offerFuzeOffset(fuzeOffsetTicks, fuzeOffsetSetTick, ordersAutocannons());
        if (readComputer(computer) || changed)
            sendData();
    }

    /** Returns whether they changed. */
    protected boolean readComputer(@Nullable BallisticComputerBlockEntity computer) {
        boolean newPlunging = computer != null && computer.isPlungingFire();
        BallisticComputerBlockEntity.SalvoResult guns = computer != null
                ? gunsOnTarget(computer) : new BallisticComputerBlockEntity.SalvoResult(0, 0);
        int newReady = guns.fired(), newTotal = guns.fired() + guns.held();
        boolean changed = newPlunging != plunging || newReady != gunsReady || newTotal != gunsTotal;
        plunging = newPlunging;
        gunsReady = newReady;
        gunsTotal = newTotal;
        return changed;
    }

    /** The big cannons. */
    protected BallisticComputerBlockEntity.SalvoResult gunsOnTarget(BallisticComputerBlockEntity computer) {
        return computer.gunsOnTarget();
    }

    /** Down for plunging fire. */
    protected boolean isLeverDown() {
        return plunging;
    }

    /** Else the big cannons. */
    protected boolean ordersAutocannons() {
        return false;
    }

    /** None here: the computer shows the trajectory. */
    protected void addModeTooltip(List<Component> tooltip) {}

    protected String titleKey() {
        return "createdangerclose.fire_mission_console.title";
    }

    /** After an order: show its effect now rather than at the next poll (server). */
    protected void refreshFromComputer() {
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

    /** Connected and loaded. */
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

    /** Server; the computer takes it at once if connected. */
    public void adjustFuzeOffset(int deltaTicks) {
        setFuzeOffset(fuzeOffsetTicks + deltaTicks);
    }

    void setFuzeOffset(int ticks) {
        if (level == null)
            return;
        int clamped = Mth.clamp(ticks, -maxFuzeOffsetTicks(), maxFuzeOffsetTicks());
        fuzeOffsetSetTick = level.getGameTime();
        if (clamped != fuzeOffsetTicks) {
            fuzeOffsetTicks = clamped;
            setChanged();
            sendData();
        }
        BallisticComputerBlockEntity computer = getComputer();
        if (computer != null)
            computer.offerFuzeOffset(fuzeOffsetTicks, fuzeOffsetSetTick, ordersAutocannons());
    }

    /** Ticks either way. */
    public int maxFuzeOffsetTicks() {
        return MAX_FUZE_OFFSET_TICKS;
    }

    /** Per scroll notch, or with Shift held. */
    public int fuzeStepTicks(boolean fine) {
        return fine ? 1 : 5;
    }

    /** Signed ticks. */
    public String fuzeText() {
        return String.format(Locale.ROOT, "%+d", fuzeOffsetTicks) + tickSuffix();
    }

    private static String tickSuffix() {
        return Component.translatable("createdangerclose.fire_mission_console.readout.ticks").getString();
    }

    protected Component fuzeTooltipText() {
        return Component.literal(String.format(Locale.ROOT, "%+d", fuzeOffsetTicks))
                .append(Component.translatable("createdangerclose.unit.ticks"));
    }

    /** For layout. */
    public String widestFuzeText() {
        return "+" + maxFuzeOffsetTicks() + tickSuffix();
    }

    // --- Readouts and controls (client) ---

    public String modeText() {
        return modeText(isLeverDown());
    }

    /** Either setting, so the renderer can size the readout for the wider. */
    public String modeText(boolean leverDown) {
        return Component.translatable(modeReadoutKey(leverDown)).getString();
    }

    protected String modeReadoutKey(boolean leverDown) {
        return "createdangerclose.fire_mission_console.readout." + (leverDown ? "plunging" : "direct");
    }

    public int getGunsReady() {
        return gunsReady;
    }

    public int getGunsTotal() {
        return gunsTotal;
    }

    private void tickControls() {
        lever.chase(isLeverDown() ? 1 : 0, LEVER_EASING, LerpedFloat.Chaser.EXP);
        lever.tickChaser();
        knob.chase(fuzeOffsetTicks, KNOB_EASING, LerpedFloat.Chaser.EXP);
        knob.tickChaser();
        if (buttonHeldTicks > 0) {
            buttonHeldTicks--;
            button.chase(1, BUTTON_PULL_EASING, LerpedFloat.Chaser.EXP);
        } else
            button.chase(0, BUTTON_RETURN_EASING, LerpedFloat.Chaser.EXP);
        button.tickChaser();
    }

    /** 0 (up: direct) to 1 (down: plunging). */
    public float getLeverThrow(float partialTicks) {
        return lever.getValue(partialTicks);
    }

    /** -1 (earliest) to 1 (latest). */
    public float getKnobTurn(float partialTicks) {
        return knob.getValue(partialTicks) / maxFuzeOffsetTicks();
    }

    /** 0 (out) to 1 (pressed in). */
    public float getButtonPress(float partialTicks) {
        return button.getValue(partialTicks);
    }

    /** Springs back by itself (client). */
    public void animateButtonPress() {
        int hold = fireControlHoldTicks();
        if (hold > 0)
            buttonHeldTicks = hold;
        else
            button.setValue(1);
    }

    /** Ticks; 0 snaps in and straight back (the salvo button). */
    protected int fireControlHoldTicks() {
        return 0;
    }

    /** Connected to a computer, or in a Ponder scene. */
    public boolean showsMission() {
        return ponderMission || getComputer() != null;
    }

    /** Ponder only. */
    public void showPlunging(boolean plunging) {
        ponderMission = true;
        this.plunging = plunging;
    }

    /** Ponder only. */
    public void showGunsReady(int ready, int total) {
        ponderMission = true;
        gunsReady = ready;
        gunsTotal = total;
    }

    /** Ponder only: as scrolling would. */
    public void showFuzeOffset(int ticks) {
        fuzeOffsetTicks = Mth.clamp(ticks, -maxFuzeOffsetTicks(), maxFuzeOffsetTicks());
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
        fuzeOffsetTicks = Mth.clamp(compound.getInt("FuzeOffset"), -maxFuzeOffsetTicks(), maxFuzeOffsetTicks());
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
        // Only clients need these: the link and readouts lapse on their own.
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
                .add(Component.translatable(titleKey()))
                .forGoggles(tooltip);
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console.fuze_offset",
                        fuzeTooltipText()))
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
        addModeTooltip(tooltip);
        computer.addFireMissionTooltip(tooltip, ordersAutocannons());
        return true;
    }

    private static void line(List<Component> tooltip, String key, ChatFormatting style) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_mission_console." + key))
                .style(style)
                .forGoggles(tooltip);
    }
}