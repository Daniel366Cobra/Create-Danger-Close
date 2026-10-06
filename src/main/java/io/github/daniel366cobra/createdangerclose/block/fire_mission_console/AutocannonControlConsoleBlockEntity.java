package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * The Autocannon Control Console: the Fire Mission Console's desk, giving orders to
 * the autocannons instead of the big guns. Its trigger fires every autocannon on
 * target for as long as it is held; the auto switch sets weapons free, when they fire
 * by themselves whenever they are on target. Danger close is never fired on its own:
 * it takes letting go of the trigger and pressing it again, within the confirmation
 * window, and then holds for as long as the trigger does.
 */
public class AutocannonControlConsoleBlockEntity extends FireMissionConsoleBlockEntity {

    // A held right-click repeats every 4 ticks: the trigger counts as held this long after the last.
    private static final int TRIGGER_HOLD_TICKS = 6;

    /** What pulling the trigger did. */
    public enum Pull { HELD, PRESSED, DANGER_CLOSE_WARNING }

    private boolean weaponsFree;
    private long lastTriggerTick = Long.MIN_VALUE / 2;
    // Danger close: when a warned press was made (-1: none), and whether this hold confirms it.
    private long dangerArmedTick = -1;
    private boolean dangerConfirmed;

    public AutocannonControlConsoleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected BallisticComputerBlockEntity.SalvoResult gunsOnTarget(BallisticComputerBlockEntity computer) {
        return computer.autocannonsOnTarget();
    }

    @Override
    protected boolean isLeverDown() {
        return weaponsFree;
    }

    @Override
    public String modeText() {
        return weaponsFree ? "AUTO" : "MAN";
    }

    @Override
    protected boolean ordersAutocannons() {
        return true;
    }

    /** Its fire mode, in place of the trajectory (autocannons always fire direct). */
    @Override
    protected void addModeTooltip(List<Component> tooltip) {
        CDCLang.builder()
                .add(Component.translatable(weaponsFree
                        ? "createdangerclose.autocannon_control_console.fire_mode.auto"
                        : "createdangerclose.autocannon_control_console.fire_mode.manual"))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);
    }

    // Rounds fly a second or two: the knob counts single ticks, up to a second either way.
    private static final int MAX_ROUND_FUZE_OFFSET_TICKS = 20;

    @Override
    public int maxFuzeOffsetTicks() {
        return MAX_ROUND_FUZE_OFFSET_TICKS;
    }

    @Override
    public int fuzeStepTicks(boolean fine) {
        return 1;
    }

    @Override
    protected String titleKey() {
        return "createdangerclose.autocannon_control_console.title";
    }

    public boolean isWeaponsFree() {
        return weaponsFree;
    }

    void toggleWeaponsFree() {
        weaponsFree = !weaponsFree;
        setChanged();
        sendData();
    }

    /** The trigger, pressed or held (server). */
    Pull pullTrigger(BallisticComputerBlockEntity computer) {
        long now = level.getGameTime();
        boolean newPress = now - lastTriggerTick > TRIGGER_HOLD_TICKS;
        lastTriggerTick = now;
        if (!newPress)
            return Pull.HELD;
        CServer.DangerClose config = CDCConfig.server().dangerClose;
        if (config.allowDangerClose.get() && computer.isAutocannonDangerClose()) {
            if (dangerArmedTick >= 0 && now - dangerArmedTick <= config.confirmSeconds.get() * 20) {
                dangerConfirmed = true;
                dangerArmedTick = -1;
                return Pull.PRESSED;
            }
            dangerArmedTick = now;
            dangerConfirmed = false;
            return Pull.DANGER_CLOSE_WARNING;
        }
        return Pull.PRESSED;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        boolean held = level.getGameTime() - lastTriggerTick <= TRIGGER_HOLD_TICKS;
        if (!held)
            dangerConfirmed = false;
        if (!held && !weaponsFree)
            return;
        BallisticComputerBlockEntity computer = getComputer();
        // Weapons free fires only on a target being ranged now, not on one the Integrator is
        // only holding: that drifts, and the rounds would wander off it. The trigger is the
        // gunner's call either way.
        if (computer != null && (held || computer.isTargetLive()))
            computer.fireAutocannons(held && dangerConfirmed);
    }

    // --- NBT ---

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        weaponsFree = compound.getBoolean("WeaponsFree");
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("WeaponsFree", weaponsFree);
    }
}
