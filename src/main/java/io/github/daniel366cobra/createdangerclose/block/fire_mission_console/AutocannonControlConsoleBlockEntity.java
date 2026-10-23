package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The Fire Mission Console's desk for autocannons: the trigger fires those on target while held; the
 * switch sets weapons free. Danger close takes a release and a second pull within the window.
 */
public class AutocannonControlConsoleBlockEntity extends FireMissionConsoleBlockEntity {

    // Held right-click repeats every 4 ticks.
    private static final int TRIGGER_HOLD_TICKS = 6;
    // Client: stays pulled between repeats.
    private static final int TRIGGER_ANIMATION_HOLD_TICKS = 8;

    public enum Pull { HELD, PRESSED, DANGER_CLOSE_WARNING }

    private boolean weaponsFree;
    private long lastTriggerTick = Long.MIN_VALUE / 2;
    // -1: no warned press.
    private long dangerArmedTick = -1;
    private boolean dangerConfirmed;
    // Server: who pulled the trigger, credited once the guns fire on that pull.
    @Nullable private UUID gunner;
    private boolean creditPending;

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
    protected String modeReadoutKey(boolean leverDown) {
        return "createdangerclose.autocannon_control_console.readout." + (leverDown ? "auto" : "manual");
    }

    @Override
    protected boolean ordersAutocannons() {
        return true;
    }

    /** In place of the trajectory: autocannons always fire direct. */
    @Override
    protected void addModeTooltip(List<Component> tooltip) {
        CDCLang.builder()
                .add(Component.translatable(weaponsFree
                        ? "createdangerclose.autocannon_control_console.fire_mode.auto"
                        : "createdangerclose.autocannon_control_console.fire_mode.manual"))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);
    }

    // Rounds fly a second or two: single ticks, up to a second either way.
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
    protected int fireControlHoldTicks() {
        return TRIGGER_ANIMATION_HOLD_TICKS;
    }

    @Override
    protected String titleKey() {
        return "createdangerclose.autocannon_control_console.title";
    }

    public boolean isWeaponsFree() {
        return weaponsFree;
    }

    /** Ponder only: as right-clicking would. */
    public void showWeaponsFree(boolean free) {
        weaponsFree = free;
    }

    void toggleWeaponsFree() {
        weaponsFree = !weaponsFree;
        setChanged();
        sendData();
    }

    /** Server. */
    Pull pullTrigger(BallisticComputerBlockEntity computer, Player player) {
        long now = level.getGameTime();
        boolean newPress = now - lastTriggerTick > TRIGGER_HOLD_TICKS;
        lastTriggerTick = now;
        if (!newPress)
            return Pull.HELD;
        gunner = player.getUUID();
        creditPending = true;
        CServer.DangerClose config = CDCConfig.server().dangerClose;
        if (config.confirmDangerClose.get() && computer.isAutocannonDangerClose()) {
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
        if (!held) {
            dangerConfirmed = false;
            creditPending = false;
        }
        if (!held && !weaponsFree)
            return;
        BallisticComputerBlockEntity computer = getComputer();
        // Weapons free needs a live target: an Integrator hold drifts. The trigger is the gunner's call.
        if (computer != null && (held || computer.isTargetLive())) {
            Player creditTo = creditPending && gunner != null ? level.getPlayerByUUID(gunner) : null;
            if (computer.fireAutocannons(held && dangerConfirmed, creditTo) > 0)
                creditPending = false;
        }
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