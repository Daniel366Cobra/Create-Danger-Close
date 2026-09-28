package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountInterfaceBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBehavior;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.index.CBCDataComponents;
import rbasamoyai.createbigcannons.index.CBCMunitionPropertiesHandlers;
import rbasamoyai.createbigcannons.munitions.big_cannon.BigCannonMunitionBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.config.BigCannonProjectilePropertiesComponent;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;
import rbasamoyai.createbigcannons.munitions.fuzes.TimedFuzeItem;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;

public class CannonServoOutputBlockEntity extends GeneratingKineticBlockEntity
        implements IHaveGoggleInformation, SensorReading.Provider {

    public static final float MIN_OPERATING_RPM = 32.0f;
    private static final float KEEP_ALIVE_RPM = 1.0f;

    private float desiredGeneratedSpeed = KEEP_ALIVE_RPM;
    private boolean initialSpeedApplied = false;

    // --- Direction calibration ---
    private static final float CALIBRATION_TEST_RPM = 4.0f;
    private static final int CALIBRATION_TEST_TICKS = 5;
    private static final int CALIBRATION_MAX_RETRIES = 3;

    private int driveSign = 0;                    // +1, -1, or 0 = uncalibrated
    private boolean calibrating = false;
    private int calibrationTicksRemaining = 0;
    private float calibrationStartAngle = 0;
    private int calibrationRetries = 0;
    private long lastMemberHash = 0;
    private float lastDriveError = Float.NaN;

    public static final class SlavedMount {
        public final BlockPos mountPos;
        public final @Nullable CannonMountBlockEntity mount;
        public final @Nullable CannonMountInterfaceBlockEntity iface;
        public final ServoAxis ServoAxis;

        public float currentAngle;
        public boolean hasShell;
        public @Nullable ResourceLocation shellType;
        public ItemStack fuzeStack = ItemStack.EMPTY;
        public int fuzeTimer = 0;
        public boolean isTimedFuze = false;
        public double gravity;
        public double drag;
        public boolean isQuadraticDrag;
        public float addedChargePower;
        public float minimumChargePower;
        public float totalChargePower;

        public SlavedMount(BlockPos mountPos,
                           @Nullable CannonMountBlockEntity mount,
                           @Nullable CannonMountInterfaceBlockEntity iface,
                           ServoAxis ServoAxis) {
            this.mountPos = mountPos;
            this.mount = mount;
            this.iface = iface;
            this.ServoAxis = ServoAxis;
        }
    }

    private final List<SlavedMount> slavedMounts = new ArrayList<>();
    private ServoAxis detectedAxis = ServoAxis.NONE;
    private boolean isPowered = false;

    private WeakReference<SubLevel> subLevelReference;

    public CannonServoOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.subLevelReference = new WeakReference<>(null);
    }

    @Override
    public void initialize() {
        super.initialize();
        this.subLevelReference = new WeakReference<>(
                Sable.HELPER.getContaining(this.getLevel(), this.worldPosition));
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
        applyControl();

        notifyUpdate();
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
        CannonServoInputBlockEntity input = getAdjacentInput();
        FireSolution command = (input != null) ? input.getCurrentCommand() : null;

        if (command == null || !command.valid() || !isPowered || detectedAxis == ServoAxis.NONE || detectedAxis == ServoAxis.INVALID) {
            applyIdleControl();
            return;
        }

        applyCommandedControl(command);
    }

    private void applyCommandedControl(FireSolution command) {
        // If any slaved mount isn't currently assembled, idle instead of
        // trying to drive or calibrate.
        for (SlavedMount sm : slavedMounts) {
            if (sm.mount == null || sm.mount.getContraption() == null) {
                applyIdleControl();
                return;
            }
        }

        // Detect network changes and invalidate the cached sign
        long memberHash = computeMemberHash();
        if (memberHash != lastMemberHash) {
            lastMemberHash = memberHash;
            driveSign = 0;
        }

        if (calibrating) {
            tickCalibration();
            return;
        }

        if (driveSign == 0) {
            beginCalibration();
            return;
        }

        driveToTarget(command);
    }

    // ------------------------------------------------------------------
    // Idle control
    // ------------------------------------------------------------------

    private void applyIdleControl() {
        lastDriveError = Float.NaN;
        for (SlavedMount sm : slavedMounts) {
            if (sm.iface != null) {
                sm.iface.setSequencedAngleLimit(0);
            }
        }
        applyGeneratedSpeed(isPowered ? KEEP_ALIVE_RPM : 0f);
    }

    private void applyGeneratedSpeed(float rpm) {
        if (Math.abs(desiredGeneratedSpeed - rpm) < 1e-4f) return;
        desiredGeneratedSpeed = rpm;
        if (level != null && !level.isClientSide) {
            updateGeneratedRotation();
        }
    }

    private void beginCalibration() {
        if (slavedMounts.isEmpty()) return;

        SlavedMount sm = slavedMounts.get(0);
        if (sm.iface == null) return;

        calibrationStartAngle = sm.currentAngle;
        calibrationTicksRemaining = CALIBRATION_TEST_TICKS;
        calibrating = true;

        // Permit a small amount of movement during the test.
        for (SlavedMount mount : slavedMounts) {
            if (mount.iface != null) {
                mount.iface.setSequencedAngleLimit(-1);
            }
        }
        applyGeneratedSpeed(CALIBRATION_TEST_RPM);
    }

    private void tickCalibration() {
        if (slavedMounts.isEmpty() || slavedMounts.get(0).iface == null) {
            calibrating = false;
            return;
        }

        calibrationTicksRemaining--;
        if (calibrationTicksRemaining > 0) {
            applyGeneratedSpeed(CALIBRATION_TEST_RPM);
            return;
        }

        // Stop driving and measure
        applyGeneratedSpeed(KEEP_ALIVE_RPM);
        for (SlavedMount mount : slavedMounts) {
            if (mount.iface != null) {
                mount.iface.setSequencedAngleLimit(0);
            }
        }

        SlavedMount sm = slavedMounts.get(0);
        float delta = sm.currentAngle - calibrationStartAngle;
        calibrating = false;

        if (Math.abs(delta) < 0.05f) {
            calibrationRetries++;
            if (calibrationRetries >= CALIBRATION_MAX_RETRIES) {
                calibrationRetries = 0;
                return;
            }
            // Try again next tick
            return;
        }

        driveSign = delta > 0 ? 1 : -1;
        calibrationRetries = 0;
        lastDriveError = Float.NaN;
    }

    private void driveToTarget(FireSolution command) {
        float target = (detectedAxis == ServoAxis.PITCH)
                ? (float) command.pitchDeg()
                : (float) command.yawDeg();

        if (detectedAxis == ServoAxis.PITCH && command.fuzeTicks() > 0) {
            for (SlavedMount sm : slavedMounts) {
                if (sm.isTimedFuze && sm.fuzeTimer != command.fuzeTicks()) {
                    setLoadedFuzeTime(command.fuzeTicks());
                }
            }
        }

        float signedError = 0;
        float maxAbsError = 0;

        for (SlavedMount sm : slavedMounts) {
            if (sm.iface == null) continue;
            float error = wrapAngle(target - sm.currentAngle);
            float abs = Math.abs(error);
            if (abs > maxAbsError) {
                maxAbsError = abs;
                signedError = error;
            }
            sm.iface.setSequencedAngleLimit(-1);
        }

        float prevError = lastDriveError;
        lastDriveError = maxAbsError;

        if (!Float.isNaN(prevError)
                && maxAbsError > prevError + 0.5f
                && maxAbsError > 3.0f) {
            driveSign = 0;
            lastDriveError = Float.NaN;
            return;
        }

        if (maxAbsError < SNAP_THRESHOLD_DEG) {
            for (SlavedMount sm : slavedMounts) {
                if (sm.iface != null) {
                    sm.iface.setSequencedAngleLimit(0);
                }
            }
            applyGeneratedSpeed(KEEP_ALIVE_RPM);
            lastDriveError = Float.NaN;
            return;
        }

        float speed = speedForError(maxAbsError);
        applyGeneratedSpeed(driveSign * Math.signum(signedError) * speed);
    }

    // ------------------------------------------------------------------
    // Network polling (server only)
    // ------------------------------------------------------------------

    private void pollCannonNetwork() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) {
            slavedMounts.clear();
            detectedAxis = ServoAxis.NONE;
            return;
        }

        List<SlavedMount> newMounts = new ArrayList<>();
        ServoAxis axisThisPoll = ServoAxis.NONE;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
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
        sm.currentAngle = (sm.ServoAxis == PITCH) ? entity.pitch : entity.yaw;

        if (sm.ServoAxis == PITCH) {
            readShellData(sm);
        }
    }

    // ------------------------------------------------------------------
    // Shell reading (pitch servo only)
    // ------------------------------------------------------------------

    private void readShellData(SlavedMount sm) {
        sm.hasShell = false;
        sm.shellType = null;
        sm.fuzeStack = ItemStack.EMPTY;
        sm.fuzeTimer = 0;
        sm.isTimedFuze = false;
        sm.totalChargePower = 0f;

        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) return;
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return;

        HolderLookup.Provider registries = level.registryAccess();

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo info = behavior.block();
            if (info.state().isAir()) continue;

            var block = info.state().getBlock();

            if (block instanceof BigCannonPropellantBlock propellant) {
                sm.totalChargePower += Math.max(0f, propellant.getChargePower(info));
                continue;
            }

            if (block instanceof ProjectileBlock<?> projBlock && !sm.hasShell) {
                if (!(block instanceof BigCannonMunitionBlock munition)) continue;

                ItemStack shellStack = munition.getExtractedItem(info, registries);
                ItemStack fuze = FuzedProjectileBlock.getFuzeFromItemStack(shellStack);

                sm.hasShell = true;
                sm.shellType = BuiltInRegistries.BLOCK.getKey(block);
                sm.fuzeStack = fuze;
                sm.isTimedFuze = fuze.getItem() instanceof TimedFuzeItem;
                sm.fuzeTimer = sm.isTimedFuze
                        ? fuze.getOrDefault(CBCDataComponents.FUZE_TIMER, 20)
                        : 0;

                EntityType<?> projectileType = projBlock.getAssociatedEntityType();
                BallisticPropertiesComponent ballistics = resolveBallistics(projectileType);
                BigCannonProjectilePropertiesComponent bigCannon = resolveBigCannonProperties(projectileType);

                sm.gravity = ballistics.gravity();
                sm.drag = ballistics.drag();
                sm.isQuadraticDrag = ballistics.isQuadraticDrag();
                sm.addedChargePower = bigCannon.addedChargePower();
                sm.minimumChargePower = bigCannon.minimumChargePower();
            }
        }
    }

    private static BallisticPropertiesComponent resolveBallistics(EntityType<?> projectileType) {
        // Common shells: HE, fluid, smoke.
        // Sentinel: gravity() != 0 because every shipped shell has a negative
        // gravity value in its JSON. The default is 0.0, which means "no entry
        // for this entity type in this handler."
        BallisticPropertiesComponent common = CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).ballistics();
        if (common.gravity() != 0) return common;

        BallisticPropertiesComponent inert = CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).ballistics();
        if (inert.gravity() != 0) return inert;

        BallisticPropertiesComponent shrapnel = CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (shrapnel.gravity() != 0) return shrapnel;

        BallisticPropertiesComponent fluid = CBCMunitionPropertiesHandlers.FLUID_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (fluid.gravity() != 0) return fluid;

        BallisticPropertiesComponent smoke = CBCMunitionPropertiesHandlers.SMOKE_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (smoke.gravity() != 0) return smoke;

        BallisticPropertiesComponent mortar = CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL
                .getPropertiesOf(projectileType).ballistics();
        if (mortar.gravity() != 0) return mortar;

        BallisticPropertiesComponent stone = CBCMunitionPropertiesHandlers.MORTAR_STONE
                .getPropertiesOf(projectileType).ballistics();
        if (stone.gravity() != 0) return stone;

        // Nothing matched. Return the common default so callers get a consistent
        // zeroed value and the ballistic solver can fall back.
        return common;
    }

    private static BigCannonProjectilePropertiesComponent resolveBigCannonProperties(EntityType<?> projectileType) {
        // Sentinel: minimumChargePower() != 0 because every shipped shell has a
        // minimum charge of at least 1.0 in its JSON. The default is 0.
        BigCannonProjectilePropertiesComponent common = CBCMunitionPropertiesHandlers.COMMON_SHELL_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (common.minimumChargePower() != 0) return common;

        BigCannonProjectilePropertiesComponent inert = CBCMunitionPropertiesHandlers.INERT_BIG_CANNON_PROJECTILE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (inert.minimumChargePower() != 0) return inert;

        BigCannonProjectilePropertiesComponent shrapnel = CBCMunitionPropertiesHandlers.SHRAPNEL_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (shrapnel.minimumChargePower() != 0) return shrapnel;

        BigCannonProjectilePropertiesComponent grapeshot = CBCMunitionPropertiesHandlers.BAG_OF_GRAPESHOT
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (grapeshot.minimumChargePower() != 0) return grapeshot;

        BigCannonProjectilePropertiesComponent fluid = CBCMunitionPropertiesHandlers.FLUID_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (fluid.minimumChargePower() != 0) return fluid;

        BigCannonProjectilePropertiesComponent smoke = CBCMunitionPropertiesHandlers.SMOKE_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (smoke.minimumChargePower() != 0) return smoke;

        BigCannonProjectilePropertiesComponent mortar = CBCMunitionPropertiesHandlers.DROP_MORTAR_SHELL
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (mortar.minimumChargePower() != 0) return mortar;

        BigCannonProjectilePropertiesComponent stone = CBCMunitionPropertiesHandlers.MORTAR_STONE
                .getPropertiesOf(projectileType).bigCannonProperties();
        if (stone.minimumChargePower() != 0) return stone;

        return common;
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

        ListTag slaves = new ListTag();
        for (SlavedMount sm : slavedMounts) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("MountPos", sm.mountPos.asLong());
            tag.putInt("ServoAxis", sm.ServoAxis.ordinal());
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

        if (!clientPacket) return;

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
        for (SlavedMount sm : slavedMounts) {
            float facingYaw = 0f;
            float maxElevation = 90f;
            float maxDepression = -90f;

            if (sm.mount != null) {
                PitchOrientedContraptionEntity contraption = sm.mount.getContraption();
                if (contraption != null) {
                    facingYaw = contraption.getInitialOrientation().toYRot();
                    maxElevation = contraption.maximumElevation();
                    maxDepression = -contraption.maximumDepression();
                }
            }

            mounts.add(new ServoStatus.MountSnapshot(
                    sm.mountPos, facingYaw, sm.currentAngle,
                    maxElevation, maxDepression,
                    sm.hasShell, sm.shellType,
                    sm.fuzeStack, sm.fuzeTimer, sm.isTimedFuze,
                    sm.gravity, sm.drag, sm.isQuadraticDrag,
                    sm.addedChargePower, sm.minimumChargePower, sm.totalChargePower));
        }
        return new ServoStatus(worldPosition, detectedAxis, isPowered, mounts);
    }

    private static final float SNAP_THRESHOLD_DEG = 0.2f;

    private static float wrapAngle(float deg) {
        deg = deg % 360f;
        if (deg > 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static float speedForError(float errorDeg) {
        if (errorDeg <= SNAP_THRESHOLD_DEG) return 0f;

        // 32 RPM at 20°+, ramping linearly to 2 RPM at the snap threshold.
        float t = Mth.clamp((errorDeg - SNAP_THRESHOLD_DEG) / (20f - SNAP_THRESHOLD_DEG), 0f, 1f);
        return Mth.lerp(t, 2f, 32f);
    }

    private long computeMemberHash() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) return 0;
        long h = 0;
        for (KineticBlockEntity member : network.members.keySet()) {
            h ^= member.getBlockPos().asLong();
        }
        return h;
    }

    /**
     * Test helper: sets the loaded shell's timed fuze to the given number of ticks.
     * Returns true if a timed fuze was found and updated.
     */
    public boolean setLoadedFuzeTime(int ticks) {
        if (level == null || level.isClientSide()) return false;
        if (slavedMounts.isEmpty()) return false;

        SlavedMount sm = slavedMounts.get(0);
        if (sm.ServoAxis != PITCH) return false;
        if (sm.mount == null) return false;

        PitchOrientedContraptionEntity entity = sm.mount.getContraption();
        if (entity == null) return false;
        if (!(entity.getContraption() instanceof MountedBigCannonContraption mounted)) return false;

        HolderLookup.Provider registries = level.registryAccess();

        for (var entry : mounted.presentBlockEntities.entrySet()) {
            if (!(entry.getValue() instanceof IBigCannonBlockEntity cbe)) continue;
            BlockPos cannonLocalPos = entry.getKey();  // the cannon block's local position
            BigCannonBehavior behavior = cbe.cannonBehavior();
            StructureBlockInfo loadedInfo = behavior.block();
            if (loadedInfo.state().isAir()) continue;
            if (!(loadedInfo.state().getBlock() instanceof ProjectileBlock<?>)) continue;

            // Modify the loaded shell's NBT
            CompoundTag shellNbt = loadedInfo.nbt() != null ? loadedInfo.nbt().copy() : new CompoundTag();
            ItemStack fuzeStack = ItemStack.parseOptional(registries, shellNbt.getCompound("Fuze"));
            if (!(fuzeStack.getItem() instanceof TimedFuzeItem)) return false;

            fuzeStack.set(CBCDataComponents.FUZE_TIMER, ticks);
            shellNbt.put("Fuze", fuzeStack.saveOptional(registries));

            // Replace the loaded block in the behavior
            behavior.loadBlock(new StructureBlockInfo(
                    loadedInfo.pos(), loadedInfo.state(), shellNbt));
            behavior.blockEntity.setChanged();

            // Update the contraption's blocks map entry for the CANNON BLOCK,
            // using the cannon block's own position and state.
            StructureBlockInfo cannonInfo = mounted.getBlocks().get(cannonLocalPos);
            if (cannonInfo == null) return true;  // behavior updated, but map not synced

            CompoundTag beTag = behavior.blockEntity.saveWithFullMetadata(registries);
            beTag.remove("x"); beTag.remove("y"); beTag.remove("z");
            mounted.getBlocks().put(cannonLocalPos, new StructureBlockInfo(
                    cannonInfo.pos(), cannonInfo.state(), beTag));

            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tooltip
    // ------------------------------------------------------------------

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.cannon_servo_output.title"))
                .forGoggles(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
        }

        if (detectedAxis == ServoAxis.INVALID) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.cannon_servo_output.too_many_mounts"))
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

        String angleKey = (sm.ServoAxis == PITCH)
                ? "createdangerclose.cannon_servo_output.mount_angle_pitch"
                : "createdangerclose.cannon_servo_output.mount_angle_yaw";
        CDCLang.builder()
                .add(Component.translatable(angleKey, String.format("%.1f", sm.currentAngle)))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);

        if (sm.ServoAxis != PITCH) return;

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

    public void logOrientationDebug() {
        if (level == null || level.isClientSide()) return;

        if (slavedMounts.isEmpty()) {
            CreateDangerClose.LOGGER.info("Orientation debug: no slaved mounts");
            return;
        }

        for (SlavedMount sm : slavedMounts) {
            if (sm.mount == null) continue;

            var mountState = sm.mount.getBlockState();
            Direction mountFacing = mountState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? mountState.getValue(BlockStateProperties.HORIZONTAL_FACING) : null;

            PitchOrientedContraptionEntity entity = sm.mount.getContraption();
            if (entity == null) {
                CreateDangerClose.LOGGER.info(
                        "Orientation debug: mount at {} facing={} has no contraption",
                        sm.mountPos, mountFacing);
                continue;
            }

            Direction initialOrientation = entity.getInitialOrientation();
            float entityYaw = entity.yaw;
            float entityPitch = entity.pitch;

            CreateDangerClose.LOGGER.info(
                    "===== Servo orientation debug =====");
            CreateDangerClose.LOGGER.info(
                    "Mount pos (local)={}", sm.mountPos);
            CreateDangerClose.LOGGER.info(
                    "Mount HORIZONTAL_FACING={}", mountFacing);
            CreateDangerClose.LOGGER.info(
                    "Cannon initialOrientation={} (toYRot={})",
                    initialOrientation, initialOrientation.toYRot());
            CreateDangerClose.LOGGER.info(
                    "Contraption entity yaw={} pitch={}", entityYaw, entityPitch);
            CreateDangerClose.LOGGER.info(
                    "Servo detected axis={}", sm.ServoAxis);

            // Iterate the contraption's block entities
            if (entity.getContraption() instanceof MountedBigCannonContraption mounted) {
                CreateDangerClose.LOGGER.info(
                        "Contraption blocks (local pos -> blockstate):");
                for (var entry : mounted.getBlocks().entrySet()) {
                    var pos = entry.getKey();
                    var info = entry.getValue();
                    var state = info.state();
                    var block = state.getBlock();

                    String facingStr = "n/a";
                    if (state.hasProperty(BlockStateProperties.FACING)) {
                        facingStr = state.getValue(BlockStateProperties.FACING).toString();
                    } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        facingStr = state.getValue(BlockStateProperties.HORIZONTAL_FACING).toString();
                    } else if (state.hasProperty(BlockStateProperties.AXIS)) {
                        facingStr = "axis=" + state.getValue(BlockStateProperties.AXIS);
                    }

                    CreateDangerClose.LOGGER.info(
                            "  {} -> {} facing={}",
                            pos, block.getClass().getSimpleName(), facingStr);
                }
            }
        }
    }
}