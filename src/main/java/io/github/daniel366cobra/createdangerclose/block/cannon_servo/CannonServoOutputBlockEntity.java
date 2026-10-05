package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.world.phys.AABB;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;

public class CannonServoOutputBlockEntity extends GeneratingKineticBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    public static final float MIN_OPERATING_RPM = 32.0f;
    private static final float KEEP_ALIVE_RPM = 1.0f;
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 10;

    private float desiredGeneratedSpeed = KEEP_ALIVE_RPM;
    private boolean initialSpeedApplied = false;

    // --- Drive ---
    // CBC turns a mount by 0.0375°/tick per interface RPM on the server
    // (Create's 0.3°/tick per RPM, scaled by 0.125 in CannonMountBlockEntity).
    private static final float MOUNT_DEG_PER_TICK_PER_RPM = 0.0375f;
    // Which way CBC's raw angles turn for positive interface rotation, the same for
    // every mount orientation. For the barrel's elevation, the pitch sign is
    // multiplied by the cannon's elevationSign.
    private static final float PITCH_AXIS_SIGN = 1f;
    private static final float YAW_AXIS_SIGN = -1f;
    private static final float MAX_DRIVE_RPM = 32f;
    // Fraction of the remaining error closed per tick, on top of feed-forward.
    private static final float CORRECTION_GAIN = 0.25f;
    // Below this commanded rate the mount is locked once on target.
    private static final float HOLD_RATE_DEG_PER_TICK = 0.01f;

    // Interface RPM per generated RPM, measured from Create's propagated
    // speeds. Its sign covers gearboxes and gearshifts, its magnitude any
    // gearing. NaN until first measured.
    private float transmissionRatio = Float.NaN;

    public static final class SlavedMount {
        public final BlockPos mountPos;
        public final @Nullable CannonMountBlockEntity mount;
        public final @Nullable CannonMountInterfaceBlockEntity iface;
        public final ServoAxis axis;

        // Pitch axis: the barrel's elevation, positive up (CBC's raw pitch times
        // elevationSign); yaw axis: CBC's yaw.
        public float currentAngle;
        // The mount's yaw (CBC's), whichever axis this servo drives.
        public float mountYaw;
        public float elevationSign = 1;
        public boolean hasShell;
        public @Nullable ResourceLocation shellType;
        public ItemStack fuzeStack = ItemStack.EMPTY;
        public int fuzeTimer = 0;
        public boolean isTimedFuze = false;
        // A delayed impact or inertia fuze: its timer is the delay after impact.
        public boolean isDelayedImpactFuze = false;
        public double gravity;
        public double drag;
        public boolean isQuadraticDrag;
        public float addedChargePower;
        public float minimumChargePower;
        public float totalChargePower;
        // Where CBC launches the shell from: the cannon's rotation centre (level
        // coordinates) and how far along the barrel from it the shell appears.
        public @Nullable Vec3 pivot;
        public double muzzleDistance;
        // Where the barrel points, a unit vector in the frame the cannon stands in; null if unassembled.
        public @Nullable Vec3 barrel;
        // The way the cannon faced when assembled (CBC yaw), where the yaw dial reads 0.
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
    // The swivel bearing this servo turns (SWIVEL), and its cog on our network.
    @Nullable private SwivelBearingBlockEntity swivel;
    @Nullable private KineticBlockEntity swivelCog;
    // The turret's angles for the goggles (synced): where it is and where it is turning to.
    private float shownSwivelAngle, shownSwivelTarget;
    // Whether a swivel is on the network at all (synced), for the goggles' invalid-setup message.
    private boolean swivelSeen;
    // How far a swivel's target angle turns per tick per cog RPM (Create's convertToAngular).
    private static final float SWIVEL_DEG_PER_TICK_PER_RPM = 0.3f;
    // A turret turns no faster than a cannon mount at the servo's full drive (1.2°/tick).
    private static final float MAX_TRAVERSE_DEG_PER_TICK = MOUNT_DEG_PER_TICK_PER_RPM * MAX_DRIVE_RPM;
    // The limits set on the dial (synced), or null for none: see getLimits.
    @Nullable private ServoLimits limits;
    // The swivel lies on its side, tilting a cradle (synced): its dial reads elevation.
    private boolean swivelTilts;
    // Which way the swivel's angle raises the barrels, as the computer last found (saved, synced).
    private int swivelSense = 1;
    // Where what this servo drives stands on its dial now (synced), for the dial's needle.
    private float dialAngle;
    private EquipmentHealth health;
    private ServoAxis detectedAxis = ServoAxis.NONE;
    private boolean isPowered = false;

    public CannonServoOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // Its gears span both halves, the input and this output.
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.COG, false)
                .lubricated(() -> Math.abs(getInputSpeed()), () -> isPowered)
                .withBox(() -> new AABB(worldPosition).minmax(
                        new AABB(worldPosition.relative(getBlockState().getValue(FACING).getOpposite())))));
    }

    @Override
    public boolean isSource() {
        return true;
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;

        if (!initialSpeedApplied) {
            initialSpeedApplied = true;
            updateGeneratedRotation();
        }

        float inputSpeed = Math.abs(getInputSpeed());
        isPowered = inputSpeed >= MIN_OPERATING_RPM;

        pollCannonNetwork();
        measureTransmission();
        applyControl();
        swivelSeen = swivel != null;
        swivelTilts = swivel != null && swivel.getBlockState().getValue(SwivelBearingBlock.FACING).getAxis() != Direction.Axis.Y;
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
     * This block is the only source on its cannon network and never fully
     * stops while powered (keep-alive), so Create has always propagated a
     * signed speed to the mount interface. Their ratio is the transmission.
     */
    private void measureTransmission() {
        KineticBlockEntity driven = swivelCog != null ? swivelCog
                : !slavedMounts.isEmpty() ? slavedMounts.get(0).iface : null;
        if (driven == null) return;
        float own = getSpeed();
        float atMount = driven.getSpeed();
        if (Math.abs(own) > 1e-3f && Math.abs(atMount) > 1e-3f) {
            transmissionRatio = atMount / own;
        }
    }

    private float getInputSpeed() {
        Direction inputDir = getBlockState().getValue(FACING).getOpposite();
        BlockPos inputPos = worldPosition.relative(inputDir);
        if (!(level.getBlockEntity(inputPos) instanceof CannonServoInputBlockEntity input)) {
            return 0f;
        }
        return input.getSpeed();
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

        // If any slaved mount isn't currently assembled, idle instead of driving.
        for (SlavedMount sm : slavedMounts) {
            if (sm.mount == null || sm.mount.getContraption() == null) {
                applyIdleControl();
                return;
            }
        }

        long commandAge = Math.max(0, level.getGameTime() - input.getCommandTick());
        driveToTarget(command, commandAge);
    }

    // ------------------------------------------------------------------
    // Idle control
    // ------------------------------------------------------------------

    private void applyIdleControl() {
        for (SlavedMount sm : slavedMounts) {
            if (sm.iface != null) {
                sm.iface.setSequencedAngleLimit(0);
            }
        }
        // A swivel has no lock: any rotation winds it round, so it is held by standing still.
        applyGeneratedSpeed(isPowered && swivel == null ? KEEP_ALIVE_RPM : 0f);
    }

    // ------------------------------------------------------------------
    // Swivel bearing (turret yaw)
    // ------------------------------------------------------------------

    /**
     * Turns the swivel to the commanded angle (the yaw of the command): winding
     * its cog changes its target angle at 0.3° per tick per RPM, the other way
     * round for a bearing facing down, and the turret follows the target angle.
     */
    private void driveSwivel(FireSolution command, long commandAge) {
        float rate = (float) command.yawRateDegPerTick();
        float target = (float) command.yawDeg() + rate * commandAge;
        // Within the dial's limits, in dial degrees (dialSign times the swivel's angle).
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
            return;
        }
        float facingSign = swivel.getBlockState().getValue(SwivelBearingBlock.FACING).getAxisDirection()
                == Direction.AxisDirection.POSITIVE ? 1 : -1;
        // No faster than a cannon mount traverses at full drive.
        float angleRate = Mth.clamp(rate + CORRECTION_GAIN * error, -MAX_TRAVERSE_DEG_PER_TICK, MAX_TRAVERSE_DEG_PER_TICK);
        float cogRpm = angleRate / (SWIVEL_DEG_PER_TICK_PER_RPM * facingSign);
        // Until the cog has turned once the transmission is unknown: start as if direct, then correct.
        float ratio = Float.isNaN(transmissionRatio) || Math.abs(transmissionRatio) < 1e-3f ? 1 : transmissionRatio;
        applyGeneratedSpeed(Mth.clamp(cogRpm / ratio, -MAX_DRIVE_RPM, MAX_DRIVE_RPM));
    }

    /**
     * The angle the turret is actually at, degrees, worked out from the hull's and
     * the turret's poses as Simulated does; its target angle if that is not possible.
     */
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

    private void applyGeneratedSpeed(float rpm) {
        if (Math.abs(desiredGeneratedSpeed - rpm) < 1e-4f) return;
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

        // Set the loaded fuze: a timed fuze to the time of flight (with correction),
        // a delayed impact or inertia fuze's delay to the correction alone.
        if (detectedAxis == ServoAxis.PITCH) {
            for (SlavedMount sm : slavedMounts) {
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

        // Within the limits set on the dial: the barrel stops at them, and a mount
        // traverses the long way round rather than through the arc left out.
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

        // On target and not tracking: lock the mount.
        if (Math.abs(error) < SNAP_THRESHOLD_DEG && Math.abs(rate) < HOLD_RATE_DEG_PER_TICK) {
            sm.iface.setSequencedAngleLimit(0);
            applyGeneratedSpeed(KEEP_ALIVE_RPM);
            return;
        }

        // Transmission not measured yet (the keep-alive rotation provides
        // the first measurement), or the drivetrain is disengaged.
        if (Float.isNaN(transmissionRatio) || Math.abs(transmissionRatio) < 1e-3f) {
            applyIdleControl();
            return;
        }

        sm.iface.setSequencedAngleLimit(-1);

        // Desired contraption angle rate: follow the command's own motion,
        // plus a proportional pull towards it.
        float angleRate = rate + CORRECTION_GAIN * error;
        float axisSign = pitch ? PITCH_AXIS_SIGN * sm.elevationSign : YAW_AXIS_SIGN;
        float mountRpm = angleRate / (MOUNT_DEG_PER_TICK_PER_RPM * axisSign);
        float generated = Mth.clamp(mountRpm / transmissionRatio, -MAX_DRIVE_RPM, MAX_DRIVE_RPM);
        applyGeneratedSpeed(generated);
    }

    // ------------------------------------------------------------------
    // Network polling (server only)
    // ------------------------------------------------------------------

    private void pollCannonNetwork() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        // Holding a turret still, this servo generates nothing, and Create then takes the
        // swivel's cog off our network: keep the swivel we found until we turn it again.
        if (swivel != null && !swivel.isRemoved() && Math.abs(desiredGeneratedSpeed) < 1e-4f
                && level.getBlockEntity(swivel.getBlockPos()) == swivel) {
            detectedAxis = ServoAxis.SWIVEL;
            slavedMounts.clear();
            return;
        }
        swivel = null;
        swivelCog = null;
        if (network == null) {
            slavedMounts.clear();
            detectedAxis = ServoAxis.NONE;
            return;
        }

        List<SlavedMount> newMounts = new ArrayList<>();
        ServoAxis axisThisPoll = ServoAxis.NONE;
        int swivels = 0;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            // A swivel bearing's cog: this servo turns a turret.
            if (member instanceof SwivelBearingBlockEntity.SwivelBearingCogwheelBlockEntity cog
                    && cog.getParentBlockEntity() instanceof SwivelBearingBlockEntity bearing) {
                swivels++;
                swivel = bearing;
                swivelCog = cog;
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
                detectedAxis = ServoAxis.INVALID;
                slavedMounts.clear();
                return;
            }

            if (!(level.getBlockEntity(iface.getBlockPos()) instanceof CannonMountBlockEntity mount)) {
                continue;
            }

            SlavedMount sm = new SlavedMount(iface.getBlockPos(), mount, iface, axis);
            readMountState(sm);
            newMounts.add(sm);
        }

        if (newMounts.size() > 1) {
            detectedAxis = ServoAxis.INVALID;
            slavedMounts.clear();
            return;
        }

        if (swivels > 0) {
            // A swivel bearing, alone on the network: with a mount interface or another
            // swivel too, one servo would drive two things.
            detectedAxis = swivels == 1 && axisThisPoll == ServoAxis.NONE
                    ? ServoAxis.SWIVEL : ServoAxis.INVALID;
            slavedMounts.clear();
            return;
        }

        this.detectedAxis = axisThisPoll;
        this.slavedMounts.clear();
        this.slavedMounts.addAll(newMounts);
    }

    private void readMountState(SlavedMount sm) {
        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) {
            sm.currentAngle = 0;
            return;
        }
        CannonReading.readCannon(sm, entity, level.registryAccess());
    }

    // ------------------------------------------------------------------
    // SensorReading.Provider
    // ------------------------------------------------------------------

    @Override
    public SensorReading getSensorReading() {
        return new SensorReading.None();
    }

    // ------------------------------------------------------------------
    // Kinetic source API
    // ------------------------------------------------------------------

    @Override
    public float getGeneratedSpeed() {
        return desiredGeneratedSpeed;
    }

    @Override
    public float calculateAddedStressCapacity() {
        return 64.0f;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);

        compound.putBoolean("IsPowered", isPowered);
        compound.putInt("detectedAxis", detectedAxis.ordinal());
        compound.putFloat("SwivelAngle", shownSwivelAngle);
        compound.putFloat("SwivelTarget", shownSwivelTarget);
        compound.putBoolean("SwivelSeen", swivelSeen);
        compound.putFloat("DialAngle", dialAngle);
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
        int ServoAxisOrd = compound.getInt("detectedAxis");
        this.detectedAxis = (ServoAxisOrd >= 0 && ServoAxisOrd < axes.length) ? axes[ServoAxisOrd] : ServoAxis.NONE;
        this.limits = compound.contains("Limits") ? ServoLimits.read(compound.getCompound("Limits"), null) : null;
        this.swivelSense = compound.getInt("SwivelSense") < 0 ? -1 : 1;

        if (!clientPacket) return;

        this.shownSwivelAngle = compound.getFloat("SwivelAngle");
        this.shownSwivelTarget = compound.getFloat("SwivelTarget");
        this.swivelSeen = compound.getBoolean("SwivelSeen");
        this.dialAngle = compound.getFloat("DialAngle");
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
        if (level == null) return null;
        BlockPos inputPos = worldPosition.relative(getBlockState().getValue(FACING).getOpposite());
        return level.getBlockEntity(inputPos) instanceof CannonServoInputBlockEntity in ? in : null;
    }

    public ServoStatus buildServoStatus() {
        List<ServoStatus.MountSnapshot> mounts = new ArrayList<>();
        for (SlavedMount sm : slavedMounts)
            mounts.add(CannonReading.snapshot(sm, sm.mount != null ? sm.mount.getContraption() : null, true));
        ServoStatus.SwivelSnapshot swivelSnapshot = null;
        // Reported whenever a swivel is on the network, even in an invalid setup, so the
        // computer knows the turret is there.
        if (swivel != null) {
            SubLevel hull = Sable.HELPER.getContaining(swivel);
            swivelSnapshot = new ServoStatus.SwivelSnapshot(swivel.getBlockPos(), swivel.getSubLevelID(),
                    hull != null ? hull.getUniqueId() : null, swivel.getBlockState().getValue(SwivelBearingBlock.FACING), swivelSense,
                    swivel.getTargetAngleDegrees(), swivelActualAngle());
        }
        return new ServoStatus(worldPosition, detectedAxis, isPowered, mounts, swivelSnapshot, getLimits());
    }

    private static final float SNAP_THRESHOLD_DEG = 0.2f;

    // ------------------------------------------------------------------
    // Limits (the protractor dial)
    // ------------------------------------------------------------------

    /**
     * The angles this servo may turn what it drives through: as set on its dial,
     * or no limit. For a pitch servo, within -90..90.
     */
    public ServoLimits getLimits() {
        boolean pitch = isElevationDial();
        if (limits == null)
            return pitch ? ServoLimits.FULL_ELEVATION : ServoLimits.FULL_TURN;
        if (!pitch)
            return limits;
        float start = Mth.clamp(limits.start(), -90, 89);
        return new ServoLimits(start, Mth.clamp(limits.end(), start + 1, 90) - start);
    }

    /** Sets the limits from the dial (server). */
    public void setLimits(ServoLimits newLimits) {
        limits = newLimits;
        setChanged();
        notifyUpdate();
    }

    /** Whether the dial reads elevation (a half dial): a mount's pitch, or a swivel tilting a cradle. */
    public boolean isElevationDial() {
        return detectedAxis == PITCH || detectedAxis == ServoAxis.SWIVEL && swivelTilts;
    }

    /**
     * A swivel's dial angle per degree of its angle: a turret's dial reads clockwise
     * seen from the bearing's face (the other way round from its angle); a cradle's
     * reads elevation, up positive.
     */
    private float dialSign() {
        return swivelTilts ? swivelSense : -1;
    }

    /** From the computer: which way this swivel's angle raises the barrels it tilts. */
    public void setSwivelSense(int sense) {
        if (sense == 0 || sense == swivelSense)
            return;
        swivelSense = sense > 0 ? 1 : -1;
        setChanged();
    }

    public ServoAxis getDetectedAxis() {
        return detectedAxis;
    }

    /** Where what this servo drives stands on its dial now (client: as last synced). */
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

    /** Sets the loaded shell's fuze timer on the mount this pitch servo drives (see {@link CannonReading#setLoadedFuzeTime}). */
    public boolean setLoadedFuzeTime(int ticks) {
        if (level == null || level.isClientSide() || slavedMounts.isEmpty()) return false;
        SlavedMount sm = slavedMounts.get(0);
        if (sm.axis != PITCH || sm.mount == null) return false;
        return CannonReading.setLoadedFuzeTime(sm.mount.getContraption(), ticks, level.registryAccess());
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.title"))
                .forGoggles(tooltip);
        health.addToGoggleTooltip(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
        }

        if (detectedAxis == ServoAxis.INVALID) {
            CDCLang.builder()
                    .add(Component.translatable(swivelSeen
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
            Component shellName = Component.translatable(
                    "block." + sm.shellType.getNamespace() + "." + sm.shellType.getPath());

            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.mount_shell",
                            shellName))
                    .style(ChatFormatting.GOLD)
                    .forGoggles(tooltip);

            if (sm.isTimedFuze) {
                int seconds = sm.fuzeTimer / 20;
                int ticks = sm.fuzeTimer % 20;
                CDCLang.builder()
                        .add(Component.translatable(
                                "createdangerclose.cannon_servo_output.mount_fuze_timed",
                                seconds, ticks))
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