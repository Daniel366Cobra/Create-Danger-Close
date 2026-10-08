package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A module's moving parts: no data of its own; on the client it animates its bank's computer. */
public class ComputerModuleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // The bank may change.
    private static final int COMPUTER_LOOKUP_INTERVAL_TICKS = 10;

    // Differentiator: one turn a second at 10 blocks a second, up to a cap.
    private static final float DIFFERENTIAL_DEG_PER_TICK_PER_SPEED = 36;
    private static final float DIFFERENTIAL_MAX_DEG_PER_TICK = 45;
    private static final float DIFFERENTIAL_SPIN_EASING = 0.1f;
    private static final float FLAG_EASING = 0.4f;

    // Integrator: an eighth of network speed.
    private static final float DISK_DEG_PER_TICK_PER_RPM = 0.3f / 8;
    // Carriage offset: tanh(speed / reference) of the full travel.
    private static final float CARRIAGE_REFERENCE_SPEED = 10;
    // A ship at rest still bobs a little.
    private static final float CARRIAGE_DEADBAND_SPEED = 0.5f;
    // Easing never quite arrives.
    private static final float CARRIAGE_SNAP_PX = 0.02f;
    // Disk center to rim, toward +x (east).
    public static final float CARRIAGE_TRAVEL_PX = 3;
    private static final float BALL_RADIUS_PX = 1, OUTPUT_SHAFT_RADIUS_PX = 1;
    private static final float CARRIAGE_EASING = 0.1f;
    private static final float HOLD_NEEDLE_EASING = 0.5f;

    // Correlator: needle spread on a log scale between these sigmas, blocks.
    private static final double SPREAD_BEST_SIGMA = 1, SPREAD_WORST_SIGMA = 100;
    private static final float SPREAD_EASING = 0.2f;

    // Fuze Computer: one ring turn per 30 s.
    private static final float RING_TICKS_PER_TURN = 600;
    private static final float RING_EASING = 0.2f;

    private EquipmentHealth health;
    @Nullable private BlockPos computerPos;
    private int ticksToLookup;

    // Differentiator.
    private final LerpedFloat differentialRate = LerpedFloat.linear().startWithValue(0);
    private final Spinner differential = new Spinner();
    private final LerpedFloat trackingFlag = LerpedFloat.linear().startWithValue(0);
    // Integrator.
    private final Spinner disk = new Spinner();
    private final LerpedFloat carriage = LerpedFloat.linear().startWithValue(0);
    private final Spinner ball = new Spinner();
    private final Spinner outputShaft = new Spinner();
    private final LerpedFloat holdNeedle = LerpedFloat.linear().startWithValue(0);
    // Correlator.
    private final LerpedFloat coincidenceSpread = LerpedFloat.linear().startWithValue(1);
    // Linear, so the ring winds the long way round.
    private final LerpedFloat fuzeTime = LerpedFloat.linear().startWithValue(0);

    public ComputerModuleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Wears and seizes at the computer's speed.
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.MECHANISM, true)
                .lubricated(() -> {
                    BallisticComputerBlockEntity computer = ComputerBank.findComputer(level, worldPosition);
                    return computer != null ? computer.getNetworkRpm() : 0f;
                }, () -> {
                    BallisticComputerBlockEntity computer = ComputerBank.findComputer(level, worldPosition);
                    return computer != null && computer.isPowered();
                })
                .sharingLubricationWith(() -> ComputerBank.lubricationGroup(level, worldPosition)));
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder().add(getBlockState().getBlock().getName()).forGoggles(tooltip);
        health.addConditionToGoggleTooltip(tooltip);
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || !level.isClientSide() || !(getBlockState().getBlock() instanceof ComputerModuleBlock block))
            return;

        if (--ticksToLookup <= 0) {
            ticksToLookup = COMPUTER_LOOKUP_INTERVAL_TICKS;
            BallisticComputerBlockEntity found = ComputerBank.findComputer(level, worldPosition);
            computerPos = found != null ? found.getBlockPos() : null;
        }
        // Broken or seized: the mechanism stands frozen where it stopped.
        if (health.isOutOfOrder()) {
            freeze();
            return;
        }
        BallisticComputerBlockEntity computer = computerPos != null
                && level.getBlockEntity(computerPos) instanceof BallisticComputerBlockEntity be ? be : null;

        switch (block.getModule()) {
            case DIFFERENTIATOR -> tickDifferentiator(computer);
            case INTEGRATOR -> tickIntegrator(computer);
            case CORRELATOR -> tickCorrelator(computer);
            case FUZE_COMPUTER -> tickFuzeComputer(computer);
            default -> { }
        }
    }

    private void tickDifferentiator(@Nullable BallisticComputerBlockEntity computer) {
        boolean tracking = computer != null && computer.isTrackingTarget();
        double speed = tracking ? computer.getTargetSpeed() : 0;
        float rate = (float) Math.min(speed * DIFFERENTIAL_DEG_PER_TICK_PER_SPEED, DIFFERENTIAL_MAX_DEG_PER_TICK);
        differentialRate.chase(rate, DIFFERENTIAL_SPIN_EASING, LerpedFloat.Chaser.EXP);
        differentialRate.tickChaser();
        differential.turn(differentialRate.getValue());

        trackingFlag.chase(tracking ? 1 : 0, FLAG_EASING, LerpedFloat.Chaser.EXP);
        trackingFlag.tickChaser();
    }

    /** Ball-and-disk: the ball rolls on the disk at the carriage's radius and turns the output shaft the other way. */
    private void tickIntegrator(@Nullable BallisticComputerBlockEntity computer) {
        float rpm = computer != null ? computer.getNetworkRpm() : 0;
        float diskRate = rpm * DISK_DEG_PER_TICK_PER_RPM;
        disk.turn(diskRate);

        float ownSpeed = computer != null ? computer.getOwnSpeed() : 0;
        // A glitched velocity leaves the carriage centered.
        float offset = Float.isFinite(ownSpeed) && ownSpeed > CARRIAGE_DEADBAND_SPEED
                ? (float) Math.tanh((ownSpeed - CARRIAGE_DEADBAND_SPEED) / CARRIAGE_REFERENCE_SPEED) * CARRIAGE_TRAVEL_PX : 0;
        if (Math.abs(carriage.getValue() - offset) < CARRIAGE_SNAP_PX)
            carriage.setValue(offset);
        else {
            carriage.chase(offset, CARRIAGE_EASING, LerpedFloat.Chaser.EXP);
            carriage.tickChaser();
        }

        float ballRate = diskRate * carriage.getValue() / BALL_RADIUS_PX;
        ball.turn(ballRate);
        outputShaft.turn(-ballRate * BALL_RADIUS_PX / OUTPUT_SHAFT_RADIUS_PX);

        holdNeedle.chase(computer != null ? computer.getHoldLeft(0) : 0, HOLD_NEEDLE_EASING, LerpedFloat.Chaser.EXP);
        holdNeedle.tickChaser();
    }

    private void tickCorrelator(@Nullable BallisticComputerBlockEntity computer) {
        double sigma = computer != null ? computer.getFixSigma() : Double.NaN;
        float spread = Double.isFinite(sigma) && sigma > 0
                ? (float) Mth.clamp(Math.log(sigma / SPREAD_BEST_SIGMA) / Math.log(SPREAD_WORST_SIGMA / SPREAD_BEST_SIGMA), 0, 1)
                : 1;
        coincidenceSpread.chase(spread, SPREAD_EASING, LerpedFloat.Chaser.EXP);
        coincidenceSpread.tickChaser();
    }

    private void tickFuzeComputer(@Nullable BallisticComputerBlockEntity computer) {
        fuzeTime.chase(computer != null ? computer.getFuzeTicks() : 0, RING_EASING, LerpedFloat.Chaser.EXP);
        fuzeTime.tickChaser();
    }

    /** Degrees (client). */
    public float getDifferentialAngle(float partialTicks) {
        return differential.get(partialTicks);
    }

    /** 0 (red) to 1 (green, tracking) (client). */
    public float getTrackingFlag(float partialTicks) {
        return trackingFlag.getValue(partialTicks);
    }

    /** Degrees (client). */
    public float getDiskAngle(float partialTicks) {
        return disk.get(partialTicks);
    }

    /** Model pixels (client). */
    public float getCarriageOffset(float partialTicks) {
        return carriage.getValue(partialTicks);
    }

    /** Degrees (client). */
    public float getBallAngle(float partialTicks) {
        return ball.get(partialTicks);
    }

    /** Degrees (client). */
    public float getOutputShaftAngle(float partialTicks) {
        return outputShaft.get(partialTicks);
    }

    /** 1 (full) to 0 (expired) (client). */
    public float getHoldNeedle(float partialTicks) {
        return holdNeedle.getValue(partialTicks);
    }

    /** 0 (together, good fix) to 1 (apart) (client). */
    public float getCoincidenceSpread(float partialTicks) {
        return coincidenceSpread.getValue(partialTicks);
    }

    /** Ticks (client). */
    public float getFuzeTicks(float partialTicks) {
        return fuzeTime.getValue(partialTicks);
    }

    /** Degrees (client). */
    public float getSettingRingAngle(float partialTicks) {
        return getFuzeTicks(partialTicks) / RING_TICKS_PER_TURN * 360;
    }

    /** Client. */
    private void freeze() {
        for (Spinner spinner : new Spinner[]{differential, disk, ball, outputShaft})
            spinner.turn(0);
        for (LerpedFloat value : new LerpedFloat[]{differentialRate, trackingFlag, carriage, holdNeedle,
                coincidenceSpread, fuzeTime})
            hold(value);
    }

    /** Chasing itself, it stops. */
    static void hold(LerpedFloat value) {
        value.chase(value.getValue(), 1, LerpedFloat.Chaser.EXP);
        value.tickChaser();
    }

    private static final class Spinner {
        private float angle, previous;

        void turn(float degrees) {
            previous = angle;
            angle = Mth.wrapDegrees(angle + degrees);
        }

        float get(float partialTicks) {
            return Mth.rotLerp(partialTicks, previous, angle);
        }
    }
}