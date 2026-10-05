package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A computer module's moving parts. Holds no data of its own: on the client it
 * follows the Ballistic Computer of its bank and animates what that computer
 * is doing; the server never ticks it for anything.
 */
public class ComputerModuleBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    // How often the module looks its computer up again (the bank may change).
    private static final int COMPUTER_LOOKUP_INTERVAL_TICKS = 10;

    // Differentiator: the differential turns with the target's speed, one turn
    // a second at 10 blocks a second (36° per tick per block per tick), up to a cap.
    private static final float DIFFERENTIAL_DEG_PER_TICK_PER_SPEED = 36;
    private static final float DIFFERENTIAL_MAX_DEG_PER_TICK = 45;
    // ...and spins up and down smoothly, while the tracking flag flips quickly.
    private static final float DIFFERENTIAL_SPIN_EASING = 0.1f;
    private static final float FLAG_EASING = 0.4f;

    // Integrator: the disk turns at an eighth of the battle network's speed
    // (0.3° per tick per RPM, divided by 8).
    private static final float DISK_DEG_PER_TICK_PER_RPM = 0.3f / 8;
    // The ball carriage stands off the disk's centre by the ship's own speed,
    // easing out towards the rim: tanh(speed / reference) of the full travel.
    private static final float CARRIAGE_REFERENCE_SPEED = 10;
    // Below this the ship counts as stopped: a ship at rest still bobs and settles a little.
    private static final float CARRIAGE_DEADBAND_SPEED = 0.5f;
    // Closer than this to where it is going, the carriage is there (easing never quite arrives).
    private static final float CARRIAGE_SNAP_PX = 0.02f;
    // Model pixels from the disk's centre to where the ball meets the rim; the
    // carriage runs out towards +x (east).
    public static final float CARRIAGE_TRAVEL_PX = 3;
    // The ball rolls on the disk and turns the output shaft; both are 1 px in radius.
    private static final float BALL_RADIUS_PX = 1, OUTPUT_SHAFT_RADIUS_PX = 1;
    private static final float CARRIAGE_EASING = 0.1f;
    private static final float HOLD_NEEDLE_EASING = 0.5f;

    // Correlator: the coincidence needles spread with the fix's uncertainty, on a
    // log scale from together (this good, blocks) to fully apart (this bad, or no target).
    private static final double SPREAD_BEST_SIGMA = 1, SPREAD_WORST_SIGMA = 100;
    private static final float SPREAD_EASING = 0.2f;

    // Fuze Computer: the setting ring winds round with the time of flight, one turn per
    // this many seconds, easing like a hand-cranked fuze setter.
    private static final float RING_SECONDS_PER_TURN = 30;
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
    // Fuze Computer: the time the ring and readout show, seconds (linear, so the ring
    // winds the long way round rather than taking a short cut).
    private final LerpedFloat fuzeTime = LerpedFloat.linear().startWithValue(0);

    public ComputerModuleBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Turned by the bank's computer: worn, and able to seize, as fast as it runs.
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.MECHANISM, true)
                .lubricated(() -> {
                    BallisticComputerBlockEntity computer = ComputerBank.findComputer(level, worldPosition);
                    return computer != null ? computer.getNetworkRpm() : 0f;
                }, () -> {
                    BallisticComputerBlockEntity computer = ComputerBank.findComputer(level, worldPosition);
                    return computer != null && computer.isPowered();
                })
                .sharingLubricationWith(() -> ComputerBank.scan(level, worldPosition).members()));
    }

    /** The module's name and condition. */
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

    /**
     * A ball-and-disk integrator, run as one: the disk turns with the
     * network, the ball rolls on it where the carriage holds it, and turns
     * the output shaft in turn. Off centre, the ball's rim speed is the disk's
     * at that radius; the shaft's matches the ball's, the other way round.
     */
    private void tickIntegrator(@Nullable BallisticComputerBlockEntity computer) {
        float rpm = computer != null ? computer.getNetworkRpm() : 0;
        float diskRate = rpm * DISK_DEG_PER_TICK_PER_RPM;
        disk.turn(diskRate);

        float ownSpeed = computer != null ? computer.getOwnSpeed() : 0;
        // A broken velocity reading (a glitched ship) leaves the carriage centred.
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
        fuzeTime.chase(computer != null ? computer.getFuzeSeconds() : 0, RING_EASING, LerpedFloat.Chaser.EXP);
        fuzeTime.tickChaser();
    }

    /** Differentiator: the differential's turn, degrees (client). */
    public float getDifferentialAngle(float partialTicks) {
        return differential.get(partialTicks);
    }

    /** Differentiator: the tracking flag, 0 (red, not tracking) to 1 (green, tracking) (client). */
    public float getTrackingFlag(float partialTicks) {
        return trackingFlag.getValue(partialTicks);
    }

    /** Integrator: the disk's turn about its vertical axis, degrees (client). */
    public float getDiskAngle(float partialTicks) {
        return disk.get(partialTicks);
    }

    /** Integrator: the carriage's distance off the disk's centre, model pixels (client). */
    public float getCarriageOffset(float partialTicks) {
        return carriage.getValue(partialTicks);
    }

    /** Integrator: the ball's roll about its east-west axis, degrees (client). */
    public float getBallAngle(float partialTicks) {
        return ball.get(partialTicks);
    }

    /** Integrator: the output shaft's turn about its east-west axis, degrees (client). */
    public float getOutputShaftAngle(float partialTicks) {
        return outputShaft.get(partialTicks);
    }

    /** Integrator: the hold countdown, 1 (full) to 0 (expired, or nothing held) (client). */
    public float getHoldNeedle(float partialTicks) {
        return holdNeedle.getValue(partialTicks);
    }

    /** Correlator: how far apart the coincidence needles stand, 0 (together, a good fix) to 1 (apart) (client). */
    public float getCoincidenceSpread(float partialTicks) {
        return coincidenceSpread.getValue(partialTicks);
    }

    /** Fuze Computer: the time of flight being set, seconds (client). */
    public float getFuzeTime(float partialTicks) {
        return fuzeTime.getValue(partialTicks);
    }

    /** Fuze Computer: the setting ring's turn, degrees, increasing with the time (client). */
    public float getSettingRingAngle(float partialTicks) {
        return getFuzeTime(partialTicks) / RING_SECONDS_PER_TURN * 360;
    }

    /** A part that keeps turning: its angle, kept within ±180° and interpolated the short way. */
    /** Holds every moving part where it is (client). */
    private void freeze() {
        for (Spinner spinner : new Spinner[]{differential, disk, ball, outputShaft})
            spinner.turn(0);
        for (LerpedFloat value : new LerpedFloat[]{differentialRate, trackingFlag, carriage, holdNeedle,
                coincidenceSpread, fuzeTime})
            hold(value);
    }

    /** Keeps a chased value where it is: chasing itself, it stops. */
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
