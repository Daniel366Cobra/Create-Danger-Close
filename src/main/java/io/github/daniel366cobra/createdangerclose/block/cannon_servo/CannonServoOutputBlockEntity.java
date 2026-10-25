package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.DriveTrain;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.mixin.SwivelBearingAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;
import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.YAW;

public class CannonServoOutputBlockEntity extends PairOutputBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    // The least drive worth having: on a share below it the servo is starved, and crawls.
    private static final float CRAWL_RPM = 1.0f;
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 10;

    // Idle, it stands still: a mount is locked by its interface, a swivel by not turning.
    private float desiredGeneratedSpeed = 0;
    private boolean initialSpeedApplied = false;

    // --- Drive ---
    // CBC: Create's 0.3°/tick per RPM, scaled by 0.125 in CannonMountBlockEntity.
    private static final float MOUNT_DEG_PER_TICK_PER_RPM = 0.0375f;
    // CBC's raw angle direction for positive rotation; pitch is also multiplied by elevationSign.
    private static final float PITCH_AXIS_SIGN = 1f;
    private static final float YAW_AXIS_SIGN = -1f;
    // Fraction of the remaining error closed per tick, on top of feed-forward.
    private static final float CORRECTION_GAIN = 0.25f;
    // Below this commanded rate the mount is locked once on target.
    private static final float HOLD_RATE_DEG_PER_TICK = 0.01f;

    // Interface (or swivel cog) RPM per generated RPM (sign: gearboxes, magnitude: gearing). NaN with nothing driven.
    private float transmissionRatio = Float.NaN;

    public static final class SlavedMount {
        public final BlockPos mountPos;
        public final @Nullable CannonMountBlockEntity mount;
        public final @Nullable CannonMountInterfaceBlockEntity iface;
        public final ServoAxis axis;

        // Pitch: elevation, positive up (CBC raw pitch times elevationSign). Yaw: CBC's yaw.
        public float currentAngle;
        // CBC's yaw, whichever axis this servo drives.
        public float mountYaw;
        public float elevationSign = 1;
        public boolean hasShell;
        public @Nullable ResourceLocation shellType;
        public ItemStack fuzeStack = ItemStack.EMPTY;
        public int fuzeTimer = 0;
        public boolean isTimedFuze = false;
        // Its timer is the delay after impact.
        public boolean isDelayedImpactFuze = false;
        public double gravity;
        public double drag;
        public boolean isQuadraticDrag;
        public float addedChargePower;
        public float minimumChargePower;
        public float totalChargePower;
        // The cannon's rotation center (level coordinates) and the shell's spawn distance along the barrel.
        public @Nullable Vec3 pivot;
        public double muzzleDistance;
        // Unit vector in the cannon's own frame; null if unassembled.
        public @Nullable Vec3 barrel;
        // Autocannon rounds expire after this many ticks (0: no limit).
        public boolean autocannon;
        public int maxFlightTicks;
        // CBC yaw when assembled: the yaw dial's zero.
        public float facingYaw;

        public SlavedMount(BlockPos mountPos,
                           @Nullable CannonMountBlockEntity mount,
                           @Nullable CannonMountInterfaceBlockEntity iface,
                           ServoAxis axis) {
            this.mountPos = mountPos;
            this.mount = mount;
            this.iface = iface;
            this.axis = axis;
        }
    }

    private final List<SlavedMount> slavedMounts = new ArrayList<>();
    @Nullable private SwivelBearingBlockEntity swivel;
    // For the goggles (synced).
    private float shownSwivelAngle, shownSwivelTarget;
    // For the goggles' invalid-setup message (synced).
    private boolean swivelSeen, governedAxis;
    // Create's convertToAngular.
    private static final float SWIVEL_DEG_PER_TICK_PER_RPM = 0.3f;
    // Null for none.
    @Nullable private ServoLimits limits;
    // Tilting a cradle: its dial reads elevation (synced).
    private boolean swivelTilts;
    // Which way the swivel's angle raises the barrels, from the computer.
    private int swivelSense = 1;
    // For the dial's needle (synced).
    private float dialAngle;
    private EquipmentHealth health;
    // --- Power (see updatePower) ---
    private static final int POWER_INTERVAL_TICKS = 5;
    // su per RPM of drive (see DriveTrain.perRpm).
    private float mountImpact;
    // su whenever it turns: machinery tapped off past speed controllers (see DriveTrain.governedLoad; synced).
    private float tapLoad;
    // RPM, and the share it comes from (su).
    private float rpmLimit = Float.MAX_VALUE;
    private float powerShare;
    // Held back by the power share this tick; the goggles show it with the draw (su).
    private boolean drivingLimited;
    private boolean shownLimited;
    // Too little power even to crawl.
    private boolean overstressHold, shownStarved;
    private float requestedRpm;
    private float shownDraw, shownShare;
    private ServoAxis detectedAxis = ServoAxis.NONE;
    private boolean isPowered = false;
    // See dropUnloadedStress.
    private boolean unloadedDropped;

    public CannonServoOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.COG, false)
                .lubricated(() -> Math.abs(getInputSpeed()), () -> isPowered)
                .withBox(() -> new AABB(worldPosition).minmax(
                        new AABB(worldPosition.relative(getBlockState().getValue(FACING).getOpposite())))));
    }

    @Override
    public boolean isSource() {
        return true;
    }

    // --- Tick ---

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;

        if (!initialSpeedApplied) {
            initialSpeedApplied = true;
            updateGeneratedRotation();
        }

        float inputSpeed = Math.abs(getInputSpeed());
        isPowered = inputSpeed >= CDCConfig.server().fireControl.minOperatingRpm.get();

        pollCannonNetwork();
        dropUnloadedStress();
        if (level.getGameTime() % POWER_INTERVAL_TICKS == 0)
            updatePower();
        drivingLimited = false;
        applyControl();
        commitDraw();
        shownLimited = drivingLimited;
        shownStarved = overstressHold;
        shownShare = powerShare;
        dialAngle = readDialAngle();
        if (detectedAxis == ServoAxis.SWIVEL && swivel != null) {
            shownSwivelAngle = Mth.wrapDegrees((float) swivelActualAngle());
            shownSwivelTarget = Mth.wrapDegrees((float) swivel.getTargetAngleDegrees());
        }

        // Clients only use this block's data for goggles.
        if (level.getGameTime() % CLIENT_SYNC_INTERVAL_TICKS == 0)
            notifyUpdate();
    }

    /**
     * Once after loading: CBC's mount interfaces never remove their saved share of the network
     * stress, which would stay on as phantom stress. This servo is the only source, so drop it.
     */
    private void dropUnloadedStress() {
        if (unloadedDropped || !hasNetwork()
                || detectedAxis == ServoAxis.NONE || detectedAxis == ServoAxis.INVALID)
            return;
        unloadedDropped = true;
        getOrCreateNetwork().initFromTE(0, 0, 0);
    }

    private float getInputSpeed() {
        CannonServoInputBlockEntity input = getAdjacentInput();
        return input != null ? input.getSpeed() : 0;
    }

    private void applyControl() {
        // A broken cog: the servo drives nothing, and the mount stays locked where it is.
        if (health.isOutOfOrder()) {
            for (SlavedMount sm : slavedMounts)
                if (sm.iface != null)
                    sm.iface.setSequencedAngleLimit(0);
            applyGeneratedSpeed(0);
            return;
        }
        CannonServoInputBlockEntity input = getAdjacentInput();
        FireSolution command = (input != null) ? input.getCurrentCommand() : null;

        if (command == null || !command.valid() || !isPowered || detectedAxis == ServoAxis.NONE || detectedAxis == ServoAxis.INVALID) {
            applyIdleControl();
            return;
        }

        if (detectedAxis == ServoAxis.SWIVEL) {
            driveSwivel(command, Math.max(0, level.getGameTime() - input.getCommandTick()));
            return;
        }

        // A slaved mount not assembled: idle.
        for (SlavedMount sm : slavedMounts) {
            if (sm.mount == null || sm.mount.getContraption() == null) {
                applyIdleControl();
                return;
            }
        }

        long commandAge = Math.max(0, level.getGameTime() - input.getCommandTick());
        driveToTarget(command, commandAge);
    }

    // --- Idle control ---

    private void applyIdleControl() {
        for (SlavedMount sm : slavedMounts) {
            if (sm.iface != null) {
                sm.iface.setSequencedAngleLimit(0);
            }
        }
        applyGeneratedSpeed(idleRpm());
    }

    /** Machinery tapped off past a speed controller keeps running while a mount idles: a crawl, the mount locked. */
    private float idleRpm() {
        return isPowered && tapLoad > 0 && (detectedAxis == PITCH || detectedAxis == YAW) ? CRAWL_RPM : 0;
    }

    // --- Swivel bearing (turret yaw) ---

    /** Winding the cog turns the swivel's target angle 0.3°/tick per RPM (reversed facing down); the turret follows. */
    private void driveSwivel(FireSolution command, long commandAge) {
        float rate = (float) command.yawRateDegPerTick();
        float target = (float) command.yawDeg() + rate * commandAge;
        ServoLimits arc = getLimits();
        float sign = dialSign();
        float current = (float) swivel.getTargetAngleDegrees();
        float error;
        if (swivelTilts) {
            // A cradle: stops at its elevation limits.
            float dialTarget = Mth.wrapDegrees(sign * target);
            if (!arc.containsElevation(dialTarget)) {
                target = sign * Mth.clamp(dialTarget, arc.start(), arc.end());
                rate = 0;
            }
            error = Mth.wrapDegrees(target - current);
        } else {
            // A turret: the long way round rather than through the arc left out.
            if (!arc.containsTurn(sign * target))
                rate = 0;
            error = sign * arc.turnWithin(sign * current, sign * target);
        }
        if (Math.abs(error) < SNAP_THRESHOLD_DEG && Math.abs(rate) < HOLD_RATE_DEG_PER_TICK) {
            applyGeneratedSpeed(0);
            // As a mount: the exact angle, not wherever the deadband caught it. The turret follows its set-point.
            if (Math.abs(error) > SNAP_EPSILON_DEG) {
                ((SwivelBearingAccessor) swivel).createdangerclose$setTargetAngleDegrees(current + error);
                swivel.sendData();
            }
            return;
        }
        // Disengaged (a clutch): nothing to turn.
        if (Float.isNaN(transmissionRatio) || Math.abs(transmissionRatio) < 1e-3f) {
            applyGeneratedSpeed(0);
            return;
        }
        float facingSign = swivel.getBlockState().getValue(SwivelBearingBlock.FACING).getAxisDirection()
                == Direction.AxisDirection.POSITIVE ? 1 : -1;
        // No faster than a cannon mount traverses at full drive.
        float maxTraverse = MOUNT_DEG_PER_TICK_PER_RPM * maxDriveRpm();
        float angleRate = Mth.clamp(rate + CORRECTION_GAIN * error, -maxTraverse, maxTraverse);
        float cogRpm = angleRate / (SWIVEL_DEG_PER_TICK_PER_RPM * facingSign);
        applyGeneratedSpeed(Mth.clamp(cogRpm / transmissionRatio, -maxDriveRpm(), maxDriveRpm()));
    }

    /** The turret's actual angle from the hull and turret poses, as Simulated does; else its target angle. */
    private double swivelActualAngle() {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        SubLevel turret = container != null && swivel.getSubLevelID() != null
                ? container.getSubLevel(swivel.getSubLevelID()) : null;
        BlockPos platePos = swivel.getPlatePos();
        BlockState plate = platePos != null ? level.getBlockState(platePos) : null;
        if (turret == null || plate == null || !plate.hasProperty(SwivelBearingPlateBlock.FACING))
            return swivel.getTargetAngleDegrees();
        SubLevel hull = Sable.HELPER.getContaining(swivel);
        Quaterniond hullOrientation = hull != null ? new Quaterniond(hull.logicalPose().orientation()) : new Quaterniond();
        Quaterniond bearingFacing = new Quaterniond(swivel.getBlockState().getValue(SwivelBearingBlock.FACING).getRotation());
        Quaterniond plateFacing = new Quaterniond(plate.getValue(SwivelBearingPlateBlock.FACING).getRotation());
        Quaterniond relative = hullOrientation.mul(bearingFacing).conjugate()
                .mul(new Quaterniond(turret.logicalPose().orientation()).mul(plateFacing));
        return Math.toDegrees(2 * Math.atan2(relative.y(), relative.w()));
    }

    /** Config; a turret traverses no faster than a mount at this speed. */
    private static float maxDriveRpm() {
        return CDCConfig.server().fireControl.maxServoRpm.get();
    }

    // --- Power: the battle network pays for what the cannon side draws ---

    /**
     * How fast it may drive on its share of the battle network's power ({@link PairOutputBlockEntity#fairShare}).
     * The limit drops at once and rises gently.
     */
    private void updatePower() {
        CannonServoInputBlockEntity input = getAdjacentInput();
        if (input == null || !input.hasNetwork() || mountImpact <= 0) {
            rpmLimit = Float.MAX_VALUE;
            powerShare = 0;
            overstressHold = false;
            return;
        }
        powerShare = fairShare(input);
        // The tapped machinery comes first: it runs at its own speed whenever the output turns.
        float shareRpm = Math.max(0, powerShare - tapLoad) / mountImpact;
        // Too little even to crawl: overstressed until the share covers it with a margin.
        overstressHold = shareRpm < (overstressHold ? CRAWL_RPM * RESUME_FACTOR : CRAWL_RPM);
        rpmLimit = rampedLimit(rpmLimit, shareRpm);
    }

    private void commitDraw() {
        // Every tick: the speed may just have changed, and the capacity must follow at once.
        float speed = Math.abs(desiredGeneratedSpeed);
        boolean overstressed = overstressHold && speed > 1e-3f;
        float load = speed > 1e-3f ? mountImpact * speed + tapLoad : 0;
        // Overstressed, the cannon side does no work: the battle network pays nothing.
        float draw = overstressed ? 0 : load;
        CannonServoInputBlockEntity input = getAdjacentInput();
        if (input != null)
            input.setDrawImpact(Math.abs(input.getSpeed()) > 1e-3f ? draw / Math.abs(input.getSpeed()) : 0);
        // Starved, short of the load so Create shows overstress.
        offerCapacity(overstressed ? Math.min(powerShare, load * UNDER_OFFER) / speed : capacityAt(speed));
        shownDraw = requestedRpm > 0 ? mountImpact * requestedRpm + tapLoad : 0;
        requestedRpm = 0;
    }

    /**
     * Per RPM, slightly over the load so rounding never overstresses. The tap runs at its own speed,
     * so it takes more per RPM the slower the output turns.
     */
    private float capacityAt(float speed) {
        return mountImpact * CAPACITY_HEADROOM + MIN_CAPACITY + (speed > 1e-3f ? tapLoad * CAPACITY_HEADROOM / speed : 0);
    }

    /** Driving faster than a crawl (for sharing the battle network's power). */
    @Override
    public boolean isPassingPower() {
        return Math.abs(desiredGeneratedSpeed) > CRAWL_RPM + 1e-3f;
    }

    private void applyGeneratedSpeed(float rpm) {
        // Capped by the power share; too little even for that: a crawl, overstressed (see commitDraw).
        requestedRpm = Math.max(requestedRpm, Math.abs(rpm));
        if (overstressHold && rpm != 0) {
            drivingLimited = true;
            rpm = Math.copySign(Math.min(Math.abs(rpm), CRAWL_RPM), rpm);
        } else if (Math.abs(rpm) > rpmLimit) {
            drivingLimited = true;
            rpm = Math.copySign(rpmLimit, rpm);
        }
        if (Math.abs(desiredGeneratedSpeed - rpm) < 1e-4f) return;
        // Starting or slowing down raises the tap's capacity per RPM: offer it before the speed changes.
        float old = Math.abs(desiredGeneratedSpeed), speed = Math.abs(rpm);
        if (tapLoad > 0 && !overstressHold && speed > 1e-3f && (old < 1e-3f || speed < old))
            offerCapacity(capacityAt(speed));
        desiredGeneratedSpeed = rpm;
        if (level != null && !level.isClientSide) {
            updateGeneratedRotation();
        }
    }

    private void driveToTarget(FireSolution command, long commandAge) {
        boolean pitch = detectedAxis == ServoAxis.PITCH;
        float rate = (float) (pitch ? command.pitchRateDegPerTick() : command.yawRateDegPerTick());
        // Extrapolate the command to now: the computer only updates every poll.
        float target = (float) (pitch ? command.pitchDeg() : command.yawDeg()) + rate * commandAge;

        // Timed fuzes: time of flight plus correction. Delayed impact or inertia: the correction alone.
        if (detectedAxis == ServoAxis.PITCH) {
            for (SlavedMount sm : slavedMounts) {
                // Autocannon breeches set each round as they fire it.
                if (sm.autocannon && sm.mount != null)
                    CannonReading.orderAutocannonFuzes(sm.mount.getContraption(),
                            command.fuzeTicks(), command.impactDelayTicks(), level.getGameTime());
                int wanted = sm.isTimedFuze ? command.fuzeTicks()
                        : sm.isDelayedImpactFuze ? command.impactDelayTicks() : -1;
                boolean leaveAsLoaded = sm.isTimedFuze ? wanted <= 0 : wanted < 0;
                if (!leaveAsLoaded && sm.fuzeTimer != wanted)
                    setLoadedFuzeTime(wanted);
            }
        }

        SlavedMount sm = slavedMounts.isEmpty() ? null : slavedMounts.get(0);
        if (sm == null || sm.iface == null) {
            applyIdleControl();
            return;
        }

        // Stop at the dial's limits; traverse the long way round rather than through the excluded arc.
        ServoLimits arc = getLimits();
        float error;
        if (pitch) {
            if (!arc.containsElevation(target)) {
                target = Mth.clamp(target, arc.start(), arc.end());
                rate = 0;
            }
            error = Mth.wrapDegrees(target - sm.currentAngle);
        } else {
            if (!arc.containsTurn(target - sm.facingYaw))
                rate = 0;
            error = arc.turnWithin(sm.currentAngle - sm.facingYaw, target - sm.facingYaw);
        }

        // On target and not tracking: set the true angle and lock the mount.
        if (Math.abs(error) < SNAP_THRESHOLD_DEG && Math.abs(rate) < HOLD_RATE_DEG_PER_TICK) {
            if (Math.abs(error) > SNAP_EPSILON_DEG && sm.mount != null)
                snapMount(sm, sm.currentAngle + error, pitch);
            sm.iface.setSequencedAngleLimit(0);
            applyGeneratedSpeed(idleRpm());
            return;
        }

        // Disengaged (a clutch): nothing to turn.
        if (Float.isNaN(transmissionRatio) || Math.abs(transmissionRatio) < 1e-3f) {
            applyIdleControl();
            return;
        }

        sm.iface.setSequencedAngleLimit(-1);

        // Feed-forward plus a proportional pull.
        float angleRate = rate + CORRECTION_GAIN * error;
        float axisSign = pitch ? PITCH_AXIS_SIGN * sm.elevationSign : YAW_AXIS_SIGN;
        float mountRpm = angleRate / (MOUNT_DEG_PER_TICK_PER_RPM * axisSign);
        float generated = Mth.clamp(mountRpm / transmissionRatio, -maxDriveRpm(), maxDriveRpm());
        applyGeneratedSpeed(generated);
    }

    /** Our elevation is CBC's raw pitch (raw times elevationSign, twice), and yaw is CBC's own. */
    private static void snapMount(SlavedMount sm, float angle, boolean pitch) {
        if (pitch)
            sm.mount.setPitch(angle);
        else
            sm.mount.setYaw(angle);
        sm.currentAngle = angle;
        sm.mount.sendData();
    }

    // --- Drive train polling (server only) ---

    /** What the output side drives, traced whether or not it turns. One mount axis or one swivel, else invalid. */
    private void pollCannonNetwork() {
        List<DriveTrain.Link> train = DriveTrain.trace(this);
        mountImpact = DriveTrain.perRpm(train);
        tapLoad = DriveTrain.governedLoad(train);
        swivel = null;
        transmissionRatio = Float.NaN;

        List<SlavedMount> newMounts = new ArrayList<>();
        float mountRatio = Float.NaN, swivelRatio = Float.NaN;
        ServoAxis axisThisPoll = ServoAxis.NONE;
        boolean mixedAxes = false;
        governedAxis = false;
        int swivels = 0;

        for (DriveTrain.Link link : train) {
            KineticBlockEntity member = link.be();
            if (member == this) continue;
            // Past a speed controller it turns at the controller's speed, not ours: no axis to lay.
            if (link.governed()) {
                if (member instanceof SwivelBearingBlockEntity.SwivelBearingCogwheelBlockEntity
                        || member instanceof CannonMountInterfaceBlockEntity)
                    governedAxis = true;
                continue;
            }
            // A swivel bearing's cog: this servo turns a turret. The first is reported even when invalid.
            if (member instanceof SwivelBearingBlockEntity.SwivelBearingCogwheelBlockEntity cog
                    && cog.getParentBlockEntity() instanceof SwivelBearingBlockEntity bearing) {
                if (swivels++ == 0) {
                    swivel = bearing;
                    swivelRatio = link.ratio();
                }
                continue;
            }
            if (!(member instanceof CannonMountInterfaceBlockEntity iface)) continue;

            ServoAxis axis;
            if (iface instanceof CannonMountInterfaceBlockEntity.PitchInterface) {
                axis = PITCH;
            } else if (iface instanceof CannonMountInterfaceBlockEntity.YawInterface) {
                axis = YAW;
            } else {
                continue;
            }

            if (axisThisPoll == ServoAxis.NONE) {
                axisThisPoll = axis;
            } else if (axisThisPoll != axis) {
                mixedAxes = true;
            }

            if (!(level.getBlockEntity(iface.getBlockPos()) instanceof CannonMountBlockEntity mount)) {
                continue;
            }

            SlavedMount sm = new SlavedMount(iface.getBlockPos(), mount, iface, axis);
            readMountState(sm);
            newMounts.add(sm);
            mountRatio = link.ratio();
        }

        slavedMounts.clear();
        swivelSeen = swivel != null;
        swivelTilts = swivel != null && swivel.getBlockState().getValue(SwivelBearingBlock.FACING).getAxis() != Direction.Axis.Y;
        if (governedAxis) {
            detectedAxis = ServoAxis.INVALID;
            return;
        }
        if (swivels > 0) {
            // A swivel alone; with anything else one servo would drive two things.
            detectedAxis = swivels == 1 && axisThisPoll == ServoAxis.NONE ? ServoAxis.SWIVEL : ServoAxis.INVALID;
            if (detectedAxis == ServoAxis.SWIVEL)
                transmissionRatio = swivelRatio;
            return;
        }
        if (mixedAxes || newMounts.size() > 1) {
            detectedAxis = ServoAxis.INVALID;
            return;
        }
        detectedAxis = axisThisPoll;
        slavedMounts.addAll(newMounts);
        if (!newMounts.isEmpty())
            transmissionRatio = mountRatio;
    }

    private void readMountState(SlavedMount sm) {
        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) {
            sm.currentAngle = 0;
            sm.autocannon = CannonReading.isAutocannonAt(sm.mount);
            return;
        }
        CannonReading.readCannon(sm, entity, level.registryAccess());
    }

    // --- SensorReading.Provider ---

    @Override
    public SensorReading getSensorReading() {
        return new SensorReading.None();
    }

    // --- Kinetic source API ---

    @Override
    public float getGeneratedSpeed() {
        return desiredGeneratedSpeed;
    }

    // --- NBT ---

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putBoolean("IsPowered", isPowered);
        compound.putInt("detectedAxis", detectedAxis.ordinal());
        compound.putFloat("MountImpact", mountImpact);
        compound.putFloat("SwivelAngle", shownSwivelAngle);
        compound.putFloat("SwivelTarget", shownSwivelTarget);
        compound.putBoolean("SwivelSeen", swivelSeen);
        compound.putBoolean("GovernedAxis", governedAxis);
        compound.putFloat("DialAngle", dialAngle);
        compound.putBoolean("PowerLimited", shownLimited);
        compound.putBoolean("PowerStarved", shownStarved);
        compound.putFloat("PowerDraw", shownDraw);
        compound.putFloat("PowerShare", shownShare);
        compound.putFloat("TapLoad", tapLoad);
        compound.putBoolean("SwivelTilts", swivelTilts);
        compound.putInt("SwivelSense", swivelSense);
        if (limits != null)
            compound.put("Limits", limits.write());

        ListTag slaves = new ListTag();
        for (SlavedMount sm : slavedMounts) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("MountPos", sm.mountPos.asLong());
            tag.putInt("ServoAxis", sm.axis.ordinal());
            tag.putFloat("CurrentAngle", sm.currentAngle);
            tag.putBoolean("HasShell", sm.hasShell);
            if (sm.shellType != null) {
                tag.putString("ShellType", sm.shellType.toString());
            }
            if (!sm.fuzeStack.isEmpty()) {
                tag.put("FuzeStack", sm.fuzeStack.saveOptional(registries));
            }

            tag.putInt("FuzeTimer", sm.fuzeTimer);
            tag.putBoolean("IsTimedFuze", sm.isTimedFuze);
            tag.putBoolean("IsDelayedImpactFuze", sm.isDelayedImpactFuze);
            tag.putDouble("Gravity", sm.gravity);
            tag.putDouble("Drag", sm.drag);
            tag.putBoolean("QuadraticDrag", sm.isQuadraticDrag);
            tag.putFloat("AddedChargePower", sm.addedChargePower);
            tag.putFloat("MinimumChargePower", sm.minimumChargePower);
            tag.putFloat("TotalChargePower", sm.totalChargePower);
            slaves.add(tag);
        }
        compound.put("Slaves", slaves);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        this.isPowered = compound.getBoolean("IsPowered");

        ServoAxis[] axes = ServoAxis.values();
        int axisOrdinal = compound.getInt("detectedAxis");
        this.detectedAxis = axisOrdinal >= 0 && axisOrdinal < axes.length ? axes[axisOrdinal] : ServoAxis.NONE;
        mountImpact = compound.getFloat("MountImpact");
        this.limits = compound.contains("Limits") ? ServoLimits.read(compound.getCompound("Limits"), null) : null;
        this.swivelSense = compound.getInt("SwivelSense") < 0 ? -1 : 1;

        if (!clientPacket) return;

        this.shownSwivelAngle = compound.getFloat("SwivelAngle");
        this.shownSwivelTarget = compound.getFloat("SwivelTarget");
        this.swivelSeen = compound.getBoolean("SwivelSeen");
        this.governedAxis = compound.getBoolean("GovernedAxis");
        this.dialAngle = compound.getFloat("DialAngle");
        this.shownLimited = compound.getBoolean("PowerLimited");
        this.shownStarved = compound.getBoolean("PowerStarved");
        this.shownDraw = compound.getFloat("PowerDraw");
        this.shownShare = compound.getFloat("PowerShare");
        this.tapLoad = compound.getFloat("TapLoad");
        this.swivelTilts = compound.getBoolean("SwivelTilts");

        this.slavedMounts.clear();
        if (!compound.contains("Slaves")) return;

        ListTag slaves = compound.getList("Slaves", Tag.TAG_COMPOUND);
        for (int i = 0; i < slaves.size(); i++) {
            CompoundTag tag = slaves.getCompound(i);
            BlockPos mountPos = BlockPos.of(tag.getLong("MountPos"));

            int slaveServoAxisOrd = tag.getInt("ServoAxis");
            ServoAxis slaveServoAxis = (slaveServoAxisOrd >= 0 && slaveServoAxisOrd < axes.length)
                    ? axes[slaveServoAxisOrd] : ServoAxis.NONE;

            SlavedMount sm = new SlavedMount(mountPos, null, null, slaveServoAxis);
            sm.currentAngle = tag.getFloat("CurrentAngle");
            sm.hasShell = tag.getBoolean("HasShell");
            if (tag.contains("ShellType")) {
                sm.shellType = ResourceLocation.tryParse(tag.getString("ShellType"));
            }
            sm.fuzeStack = ItemStack.parseOptional(registries, tag.getCompound("FuzeStack"));
            sm.fuzeTimer = tag.getInt("FuzeTimer");
            sm.isTimedFuze = tag.getBoolean("IsTimedFuze");
            sm.isDelayedImpactFuze = tag.getBoolean("IsDelayedImpactFuze");
            sm.gravity = tag.getDouble("Gravity");
            sm.drag = tag.getDouble("Drag");
            sm.isQuadraticDrag = tag.getBoolean("QuadraticDrag");
            sm.addedChargePower = tag.getFloat("AddedChargePower");
            sm.minimumChargePower = tag.getFloat("MinimumChargePower");
            sm.totalChargePower = tag.getFloat("TotalChargePower");

            this.slavedMounts.add(sm);
        }
    }

    @Nullable
    private CannonServoInputBlockEntity getAdjacentInput() {
        return getPairedInput() instanceof CannonServoInputBlockEntity input ? input : null;
    }

    public ServoStatus buildServoStatus() {
        List<ServoStatus.MountSnapshot> mounts = new ArrayList<>();
        for (SlavedMount sm : slavedMounts)
            mounts.add(CannonReading.snapshot(sm, sm.mount != null ? sm.mount.getContraption() : null, true));
        ServoStatus.SwivelSnapshot swivelSnapshot = null;
        // Reported even in an invalid setup, so the computer knows the turret is there.
        if (swivel != null) {
            SubLevel hull = Sable.HELPER.getContaining(swivel);
            swivelSnapshot = new ServoStatus.SwivelSnapshot(swivel.getBlockPos(), swivel.getSubLevelID(),
                    hull != null ? hull.getUniqueId() : null, swivel.getBlockState().getValue(SwivelBearingBlock.FACING), swivelSense,
                    swivel.getTargetAngleDegrees(), swivelActualAngle());
        }
        return new ServoStatus(worldPosition, detectedAxis, isPowered, mounts, swivelSnapshot, getLimits());
    }

    private static final float SNAP_THRESHOLD_DEG = 0.2f;
    // Closer than this is left as is, so a held mount is not resynced every tick.
    private static final float SNAP_EPSILON_DEG = 1e-4f;

    // --- Limits (the protractor dial) ---

    /** As set on the dial, or none. A pitch servo is within -90..90. */
    public ServoLimits getLimits() {
        boolean pitch = isElevationDial();
        if (limits == null)
            return pitch ? ServoLimits.FULL_ELEVATION : ServoLimits.FULL_TURN;
        if (!pitch)
            return limits;
        float start = Mth.clamp(limits.start(), -90, 89);
        return new ServoLimits(start, Mth.clamp(limits.end(), start + 1, 90) - start);
    }

    public void setLimits(ServoLimits newLimits) {
        limits = newLimits;
        setChanged();
        notifyUpdate();
    }

    /** A half dial: a mount's pitch, or a swivel tilting a cradle. */
    public boolean isElevationDial() {
        return detectedAxis == PITCH || detectedAxis == ServoAxis.SWIVEL && swivelTilts;
    }

    /** Dial degrees per swivel degree: a turret's reads clockwise from its face, a cradle's elevation. */
    private float dialSign() {
        return swivelTilts ? swivelSense : -1;
    }

    public void setSwivelSense(int sense) {
        if (sense == 0 || sense == swivelSense)
            return;
        swivelSense = sense > 0 ? 1 : -1;
        setChanged();
    }

    public ServoAxis getDetectedAxis() {
        return detectedAxis;
    }

    /** Client: as last synced. */
    public float getDialAngle() {
        return dialAngle;
    }

    private float readDialAngle() {
        if (detectedAxis == ServoAxis.SWIVEL && swivel != null)
            return Mth.wrapDegrees(dialSign() * (float) swivelActualAngle());
        if (slavedMounts.isEmpty())
            return 0;
        SlavedMount sm = slavedMounts.get(0);
        return sm.axis == PITCH ? sm.currentAngle : Mth.wrapDegrees(sm.currentAngle - sm.facingYaw);
    }

    /** See {@link CannonReading#setLoadedFuzeTime}. */
    public boolean setLoadedFuzeTime(int ticks) {
        if (level == null || level.isClientSide() || slavedMounts.isEmpty()) return false;
        SlavedMount sm = slavedMounts.get(0);
        if (sm.axis != PITCH || sm.mount == null) return false;
        return CannonReading.setLoadedFuzeTime(sm.mount.getContraption(), ticks, level.registryAccess());
    }

    // --- Tooltip ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.title"))
                .forGoggles(tooltip);
        CannonServoInputBlockEntity input = getAdjacentInput();
        if (input != null && !input.getServoName().isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo.name"))
                    .add(Component.literal(" " + input.getServoName()).withStyle(ChatFormatting.GOLD))
                    .forGoggles(tooltip);
            if (!input.getShownAs().isEmpty())
                CDCLang.builder()
                        .add(Component.translatable("createdangerclose.cannon_servo.shown_as", input.getShownAs()))
                        .style(ChatFormatting.GRAY)
                        .forGoggles(tooltip);
        }
        health.addToGoggleTooltip(tooltip);
        if (shownLimited) {
            CDCLang.builder()
                    .add(Component.translatable(shownStarved
                            ? "createdangerclose.cannon_servo_output.power_starved"
                            : "createdangerclose.cannon_servo_output.power_limited"))
                    .style(shownStarved ? ChatFormatting.RED : ChatFormatting.GOLD)
                    .forGoggles(tooltip);
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.equipment.power_balance",
                            Math.round(shownDraw), Math.round(shownShare)))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
        }

        if (detectedAxis == ServoAxis.INVALID) {
            CDCLang.builder()
                    .add(Component.translatable(governedAxis
                            ? "createdangerclose.cannon_servo_output.governed_axis"
                            : swivelSeen
                            ? "createdangerclose.cannon_servo_output.invalid_swivel"
                            : "createdangerclose.cannon_servo_output.too_many_mounts"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        if (detectedAxis == ServoAxis.NONE) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.no_mount"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.axis",
                        Component.translatable("createdangerclose.cannon_servo_output.axis."
                                + detectedAxis.name().toLowerCase())))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        if (tapLoad > 0) {
            CDCLang.builder()
                    .add(Component.translatable(Math.abs(getSpeed()) > 1e-3f
                            ? "createdangerclose.cannon_servo_output.auxiliary"
                            : "createdangerclose.cannon_servo_output.auxiliary_stopped", Math.round(tapLoad)))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }

        if (detectedAxis == ServoAxis.SWIVEL) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.swivel_angle",
                            String.format("%.1f", shownSwivelAngle), String.format("%.1f", shownSwivelTarget)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip);
            return true;
        }

        if (slavedMounts.isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.no_slaves"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
            return true;
        }

        int index = 1;
        for (SlavedMount sm : slavedMounts) {
            renderMount(tooltip, sm, index++);
        }

        return true;
    }

    private void renderMount(List<Component> tooltip, SlavedMount sm, int index) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.mount_header",
                        index, sm.mountPos.toShortString()))
                .style(ChatFormatting.YELLOW)
                .forGoggles(tooltip);

        String angleKey = (sm.axis == PITCH)
                ? "createdangerclose.cannon_servo_output.mount_angle_pitch"
                : "createdangerclose.cannon_servo_output.mount_angle_yaw";
        CDCLang.builder()
                .add(Component.translatable(angleKey, String.format("%.1f", sm.currentAngle)))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);

        if (sm.axis != PITCH) return;

        if (sm.hasShell && sm.shellType != null) {
            // A shell is a block; an autocannon round is named by what it fires.
            Component shellName = BuiltInRegistries.BLOCK.containsKey(sm.shellType)
                    ? BuiltInRegistries.BLOCK.get(sm.shellType).getName()
                    : BuiltInRegistries.ENTITY_TYPE.containsKey(sm.shellType)
                    ? BuiltInRegistries.ENTITY_TYPE.get(sm.shellType).getDescription()
                    : Component.literal(sm.shellType.toString());

            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.mount_shell",
                            shellName))
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip);

            if (sm.isTimedFuze) {
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_timed",
                                Component.literal(String.valueOf(sm.fuzeTimer))
                                        .append(Component.translatable("createdangerclose.unit.ticks"))))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            } else if (sm.fuzeStack.isEmpty()) {
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_none"))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            } else {
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_other",
                                sm.fuzeStack.getHoverName()))
                        .style(ChatFormatting.LIGHT_PURPLE)
                        .forGoggles(tooltip);
            }

            CDCLang.builder()
                    .add(Component.translatable(
                            "createdangerclose.cannon_servo_output.mount_charge",
                            String.format("%.1f", sm.totalChargePower + sm.addedChargePower)))
                    .style(ChatFormatting.DARK_AQUA)
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable(
                            "createdangerclose.cannon_servo_output.mount_shell_empty"))
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }
    }
}