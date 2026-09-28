package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.api.sensor.SolverServo;
import io.github.daniel366cobra.createdangerclose.ballistics.BallisticSolver;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;

public class FireControlSolverBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation {

    public static final float MIN_OPERATING_RPM = 32.0f;

    private static final double MUZZLE_VELOCITY_PER_CHARGE = 40.0;

    // --- Fused own-ship state (persisted scalars) ---
    private boolean hasOrientation = false;
    private double shipPitchDeg = 0;
    private double shipYawDeg = 0;
    private double shipRollDeg = 0;

    private boolean hasVelocity = false;
    private double shipVelX = 0;
    private double shipVelY = 0;
    private double shipVelZ = 0;

    private boolean hasAltitude = false;
    private double shipAltitude = 0;

    private boolean hasTarget = false;
    private double targetX = 0;
    private double targetY = 0;
    private double targetZ = 0;

    // --- Server-side intermediates for the future ballistic solve ---
    @Nullable private transient Quaterniond shipTiltQuat;
    @Nullable private transient Quaterniond shipHeadingQuat;
    @Nullable private transient Vector3d shipVelocityVec;
    @Nullable private transient Vector3d targetPositionVec;

    // --- Sensor counts for the tooltip ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int navTargetCount = 0;
    private int velocityCount = 0;
    private int altitudeCount = 0;

    private final Map<BlockPos, BallisticSolver.Result> lastPitchResults = new java.util.HashMap<>();

    // Ship-frame solution per mount position, shared by its pitch and yaw servos.
    private final Map<BlockPos, FireSolution> aimCache = new java.util.HashMap<>();

    private boolean isPowered = false;

    private WeakReference<SubLevel> subLevelReference;
    private WeakReference<KineticNetwork> battleNetworkReference;

    public FireControlSolverBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.subLevelReference = new WeakReference<>(null);
        this.battleNetworkReference = new WeakReference<>(null);
    }

    @Override
    public void initialize() {
        super.initialize();
        this.subLevelReference = new WeakReference<>(
                Sable.HELPER.getContaining(this.getLevel(), this.worldPosition));
        this.battleNetworkReference = new WeakReference<>(
                com.simibubi.create.Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this));
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide()) return;

        float speed = Math.abs(getSpeed());
        isPowered = speed >= MIN_OPERATING_RPM;

        if (!isPowered) {
            invalidateData();
            notifyUpdate();
        }

        int pollInterval = (int) Math.max(1, 640f / speed);
        if (level.getGameTime() % pollInterval == 0) {
            pollNetwork();
            pollAndPushServos();
            notifyUpdate();
        }
    }

    private void invalidateData() {
        hasOrientation = false;
        hasVelocity = false;
        hasAltitude = false;
        hasTarget = false;
        shipPitchDeg = shipYawDeg = shipRollDeg = 0;
        shipVelX = shipVelY = shipVelZ = 0;
        shipAltitude = 0;
        targetX = targetY = targetZ = 0;
        shipTiltQuat = null;
        shipHeadingQuat = null;
        shipVelocityVec = null;
        targetPositionVec = null;
        gimbalCount = navHeadingCount = navTargetCount = velocityCount = altitudeCount = 0;
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetworkReference.get();
        if (network == null) return;

        Vector3d velocitySum = new Vector3d();
        int velocityContributions = 0;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            if (!(member instanceof SensorReading.Provider provider)) continue;

            SensorReading reading = provider.getSensorReading();
            switch (reading) {
                case SensorReading.Gimbal g -> {
                    gimbalCount++;
                    if (shipTiltQuat == null) {
                        shipTiltQuat = new Quaterniond(g.tilt());
                    }
                }
                case SensorReading.NavHeading n -> {
                    navHeadingCount++;
                    if (shipHeadingQuat == null) {
                        shipHeadingQuat = new Quaterniond(n.yaw());
                    }
                }
                case SensorReading.NavTarget t -> {
                    navTargetCount++;
                    if (targetPositionVec == null) {
                        targetPositionVec = new Vector3d(
                                t.worldPos().x, t.worldPos().y, t.worldPos().z);
                    }
                }
                case SensorReading.Velocity v -> {
                    velocityCount++;
                    velocitySum.add(v.localVelocity());
                    velocityContributions++;
                }
                case SensorReading.Altitude a -> {
                    altitudeCount++;
                    if (!hasAltitude) {
                        shipAltitude = a.worldHeight();
                        hasAltitude = true;
                    }
                }
                case SensorReading.Rangefinder ignored -> { }
                case SensorReading.None ignored -> { }
            }
        }

        if (velocityContributions > 0) {
            shipVelocityVec = velocitySum;
            shipVelX = velocitySum.x;
            shipVelY = velocitySum.y;
            shipVelZ = velocitySum.z;
            hasVelocity = true;
        }

        // Orientation
        if (shipTiltQuat != null) {
            double[] euler = eulerFromQuat(shipTiltQuat);
            shipPitchDeg = Math.toDegrees(euler[0]);
            shipRollDeg = Math.toDegrees(euler[2]);
            hasOrientation = true;
        }
        if (shipHeadingQuat != null) {
            shipYawDeg = Math.toDegrees(yawFromQuat(shipHeadingQuat));
            hasOrientation = true;
        }
        // If only one of tilt/heading is present, hasOrientation is still true
        // but the tooltip will need to distinguish. We'll use the counts for that.

        // Target
        if (targetPositionVec != null) {
            targetX = targetPositionVec.x;
            targetY = targetPositionVec.y;
            targetZ = targetPositionVec.z;
            hasTarget = true;
        }
    }

    private void pollAndPushServos() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) return;

        lastPitchResults.clear();
        aimCache.clear();

        SubLevel subLevel = subLevelReference.get();

        // Pitch servos run the ballistic solve (they know the shell and charge)
        // and cache the result per mount. Yaw servos run afterwards so both axes
        // of a mount aim along the same 3D direction.
        List<SolverServo> yawServos = new ArrayList<>();

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            if (!(member instanceof SolverServo servo)) continue;

            var status = servo.getServoStatus();
            if (status == null || status.mounts().isEmpty() || !status.powered()) {
                servo.receiveFiringSolution(null);
                continue;
            }

            if (status.axis() == PITCH) {
                servo.receiveFiringSolution(solvePitch(status, subLevel));
            } else if (status.axis() == YAW) {
                yawServos.add(servo);
            } else {
                servo.receiveFiringSolution(null);
            }
        }

        for (SolverServo servo : yawServos) {
            servo.receiveFiringSolution(solveYaw(servo.getServoStatus(), subLevel));
        }
    }

    private FireSolution solveYaw(ServoStatus status, @Nullable SubLevel subLevel) {
        ServoStatus.MountSnapshot mount = status.mounts().get(0);

        FireSolution cached = aimCache.get(mount.mountPos());
        if (cached != null) {
            return new FireSolution(cached.pitchDeg(), cached.yawDeg(), 0, true);
        }

        // No ballistic solution for this mount (no pitch servo, no shell, ...).
        // Keep tracking the target along the line of sight.
        if (!hasTarget || subLevel == null) return null;

        Quaterniondc shipToWorld = shipToWorld(subLevel);
        if (shipToWorld == null) return null;

        Vector3d mountWorld = subLevel.logicalPose()
                .transformPosition(mountCenter(mount), new Vector3d());
        Vector3d toTargetWorld = new Vector3d(targetX, targetY, targetZ).sub(mountWorld);
        if (toTargetWorld.lengthSquared() < 1e-8) return null;

        Vector3d shipDir = shipToWorld.transformInverse(toTargetWorld.normalize(), new Vector3d());
        return new FireSolution(pitchOf(shipDir), yawOf(shipDir), 0, true);
    }

    private FireSolution solvePitch(ServoStatus status, @Nullable SubLevel subLevel) {
        BlockPos servoPos = status.servoPos();

        if (!hasTarget || subLevel == null) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_TARGET));
            return null;
        }

        Quaterniondc shipToWorld = shipToWorld(subLevel);
        if (shipToWorld == null) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_ORIENTATION));
            return null;
        }

        ServoStatus.MountSnapshot mount = status.mounts().get(0);

        if (!mount.hasShell()) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_SHELL));
            return null;
        }

        double charge = mount.totalChargePower() + mount.addedChargePower();
        if (mount.minimumChargePower() > charge) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.INSUFFICIENT_CHARGE));
            return null;
        }

        Vector3d muzzleWorld = subLevel.logicalPose()
                .transformPosition(mountCenter(mount), new Vector3d());

        Vector3d targetWorld = new Vector3d(targetX, targetY, targetZ);
        Vector3d targetVel = new Vector3d();

        double muzzleVelocity = charge;
        double gravity = mount.gravity();
        double drag = mount.drag();
        boolean isQuadraticDrag = mount.isQuadraticDrag();

        // The mount's elevation limits apply in ship frame, so let the solver
        // search the whole world-frame arc and check the limits afterwards.
        BallisticSolver.Result result = BallisticSolver.solve(
                muzzleWorld, targetWorld, targetVel,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                90.0, -90.0);

        if (!result.valid()) {
            lastPitchResults.put(servoPos, result);
            return null;
        }

        // Build the world-frame aim direction from the solve's pitch and yaw.
        double wp = Math.toRadians(result.pitchDeg());
        double wy = Math.toRadians(result.yawDeg());
        Vector3d worldDir = new Vector3d(
                -Math.sin(wy) * Math.cos(wp),
                Math.sin(wp),
                Math.cos(wy) * Math.cos(wp));

        // Rotate into ship frame. This is the inverse of the rotation Sable
        // applies to the shell's velocity when it kicks it out of the plot.
        Vector3d shipDir = shipToWorld.transformInverse(worldDir, new Vector3d());
        double shipPitchDeg = pitchOf(shipDir);
        double shipYawDeg = yawOf(shipDir);

        if (shipPitchDeg > mount.maxElevation() || shipPitchDeg < mount.maxDepression()) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.OUT_OF_ARC));
            return null;
        }

        lastPitchResults.put(servoPos, result);

        FireSolution solution = new FireSolution(shipPitchDeg, shipYawDeg, result.fuzeTicks(), true);
        aimCache.put(mount.mountPos(), solution);
        return solution;
    }

    /**
     * Ship-to-world rotation, or null when the network lacks the orientation
     * sensors. The sensors gate the solve; the rotation itself comes from the
     * sublevel pose, which is what the gimbal derives its reading from anyway.
     */
    @Nullable
    private Quaterniondc shipToWorld(SubLevel subLevel) {
        if (gimbalCount == 0 || navHeadingCount == 0) return null;
        return subLevel.logicalPose().orientation();
    }

    private static Vector3d mountCenter(ServoStatus.MountSnapshot mount) {
        return new Vector3d(
                mount.mountPos().getX() + 0.5,
                mount.mountPos().getY() + 0.5,
                mount.mountPos().getZ() + 0.5);
    }

    private static double pitchOf(Vector3d dir) {
        return Math.toDegrees(Math.asin(Math.clamp(dir.y, -1.0, 1.0)));
    }

    private static double yawOf(Vector3d dir) {
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        return yaw < 0 ? yaw + 360 : yaw;
    }

    // --- Accessors for other blocks (fire control panel, map table, etc.) ---

    public boolean hasOrientation() { return hasOrientation; }
    public boolean hasVelocity() { return hasVelocity; }
    public boolean hasAltitude() { return hasAltitude; }
    public boolean hasTarget() { return hasTarget; }

    public double getShipPitchDeg() { return shipPitchDeg; }
    public double getShipYawDeg() { return shipYawDeg; }
    public double getShipRollDeg() { return shipRollDeg; }

    public double getShipVelX() { return shipVelX; }
    public double getShipVelY() { return shipVelY; }
    public double getShipVelZ() { return shipVelZ; }

    public double getShipAltitude() { return shipAltitude; }

    public double getTargetX() { return targetX; }
    public double getTargetY() { return targetY; }
    public double getTargetZ() { return targetZ; }

    public boolean isPowered() { return isPowered; }

    // --- NBT ---
    // The fused state is re-derived from the network each poll, so we don't
    // need to persist it. We do persist the counters and power flag so the
    // client has something to render before the first poll.

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.isPowered = compound.getBoolean("IsPowered");

        this.hasOrientation = compound.getBoolean("HasOrientation");
        this.shipPitchDeg = compound.getDouble("ShipPitch");
        this.shipYawDeg = compound.getDouble("ShipYaw");
        this.shipRollDeg = compound.getDouble("ShipRoll");

        this.hasVelocity = compound.getBoolean("HasVelocity");
        this.shipVelX = compound.getDouble("VelX");
        this.shipVelY = compound.getDouble("VelY");
        this.shipVelZ = compound.getDouble("VelZ");

        this.hasAltitude = compound.getBoolean("HasAltitude");
        this.shipAltitude = compound.getDouble("Altitude");

        this.hasTarget = compound.getBoolean("HasTarget");
        this.targetX = compound.getDouble("TargetX");
        this.targetY = compound.getDouble("TargetY");
        this.targetZ = compound.getDouble("TargetZ");

        this.gimbalCount = compound.getInt("GimbalCount");
        this.navHeadingCount = compound.getInt("NavHeadingCount");
        this.navTargetCount = compound.getInt("NavTargetCount");
        this.velocityCount = compound.getInt("VelocityCount");
        this.altitudeCount = compound.getInt("AltitudeCount");

        if (clientPacket) {
            this.lastPitchResults.clear();
            ListTag results = compound.getList("PitchResults", Tag.TAG_COMPOUND);
            for (int i = 0; i < results.size(); i++) {
                CompoundTag t = results.getCompound(i);
                BlockPos pos = BlockPos.of(t.getLong("Pos"));
                BallisticSolver.Result.Reason reason =
                        BallisticSolver.Result.Reason.values()[t.getInt("Reason")];
                BallisticSolver.Result r = t.getBoolean("Valid")
                        ? new BallisticSolver.Result(true, reason, t.getDouble("Pitch"), 0,
                        t.getInt("Fuze"), 0, t.getInt("Fuze"))
                        : BallisticSolver.Result.invalid(reason);
                this.lastPitchResults.put(pos, r);
            }
        }

        // Transient quaternions/vectors are not persisted. On the server they'll
        // be recomputed on the next poll; on the client they stay null.
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("IsPowered", isPowered);

        compound.putBoolean("HasOrientation", hasOrientation);
        compound.putDouble("ShipPitch", shipPitchDeg);
        compound.putDouble("ShipYaw", shipYawDeg);
        compound.putDouble("ShipRoll", shipRollDeg);

        compound.putBoolean("HasVelocity", hasVelocity);
        compound.putDouble("VelX", shipVelX);
        compound.putDouble("VelY", shipVelY);
        compound.putDouble("VelZ", shipVelZ);

        compound.putBoolean("HasAltitude", hasAltitude);
        compound.putDouble("Altitude", shipAltitude);

        compound.putBoolean("HasTarget", hasTarget);
        compound.putDouble("TargetX", targetX);
        compound.putDouble("TargetY", targetY);
        compound.putDouble("TargetZ", targetZ);

        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("NavTargetCount", navTargetCount);
        compound.putInt("VelocityCount", velocityCount);
        compound.putInt("AltitudeCount", altitudeCount);

        ListTag results = new ListTag();
        for (Map.Entry<BlockPos, BallisticSolver.Result> e : lastPitchResults.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", e.getKey().asLong());
            BallisticSolver.Result r = e.getValue();
            t.putBoolean("Valid", r.valid());
            t.putInt("Reason", r.reason().ordinal());
            t.putDouble("Pitch", r.pitchDeg());
            t.putInt("Fuze", r.fuzeTicks());
            results.add(t);
        }
        compound.put("PitchResults", results);
    }

    // --- TOOLTIP ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_control_solver.title"))
                .forGoggles(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.error.network_too_slow"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        // --- Orientation ---
        boolean hasGimbal = gimbalCount > 0;
        boolean hasHeading = navHeadingCount > 0;
        if (hasGimbal && hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipYawDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasGimbal) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.gimbal_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipPitchDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.RED))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipRollDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.BLUE))
                    .forGoggles(tooltip);
        } else if (hasHeading) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.heading_angle"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipYawDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_orientation"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Velocity ---
        if (hasVelocity) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_velocity"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelX, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelY, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipVelZ, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_velocity"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Altitude ---
        if (hasAltitude) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_altitude"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(CDCLang.numberWithUnit(shipAltitude, "createdangerclose.unit.distance").withStyle(ChatFormatting.AQUA))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_altitude"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Target ---
        if (hasTarget) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.target"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetX)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetY)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetZ)).withStyle(ChatFormatting.GOLD))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_target"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        if (!lastPitchResults.isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.solutions"))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            for (Map.Entry<BlockPos, BallisticSolver.Result> entry : lastPitchResults.entrySet()) {
                BallisticSolver.Result r = entry.getValue();
                String reasonKey = "createdangerclose.fire_control_solver.reason."
                        + r.reason().name().toLowerCase();

                if (r.valid()) {
                    CDCLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.translatable(
                                    "createdangerclose.fire_control_solver.solution_ok",
                                    Component.translatable(reasonKey)))
                            .style(ChatFormatting.GREEN)
                            .forGoggles(tooltip);

                    CDCLang.builder()
                            .add(Component.literal("    pitch="))
                            .add(CDCLang.numberWithUnit(r.pitchDeg(), "createdangerclose.unit.angle"))
                            .add(Component.literal("  tof="))
                            .add(Component.literal(r.fuzeTicks() + " t"))
                            .style(ChatFormatting.DARK_GREEN)
                            .forGoggles(tooltip);
                } else {
                    CDCLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.translatable(
                                    "createdangerclose.fire_control_solver.solution_failed",
                                    Component.translatable(reasonKey)))
                            .style(ChatFormatting.RED)
                            .forGoggles(tooltip);
                }
            }
        }

        // Debug counts
        CDCLang.builder()
                .add(Component.literal(String.format(
                        "  [G:%d H:%d T:%d V:%d A:%d]",
                        gimbalCount, navHeadingCount, navTargetCount, velocityCount, altitudeCount)))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        return true;
    }

    // --- Quaternion helpers ---

    private static double[] eulerFromQuat(Quaterniond q) {
        double sinr_cosp = 2 * (q.w * q.x + q.y * q.z);
        double cosr_cosp = 1 - 2 * (q.x * q.x + q.y * q.y);
        double x = Math.atan2(sinr_cosp, cosr_cosp);

        double sinp = 2 * (q.w * q.y - q.z * q.x);
        double y = Math.abs(sinp) >= 1 ? Math.copySign(Math.PI / 2, sinp) : Math.asin(sinp);

        double siny_cosp = 2 * (q.w * q.z + q.x * q.y);
        double cosy_cosp = 1 - 2 * (q.y * q.y + q.z * q.z);
        double z = Math.atan2(siny_cosp, cosy_cosp);

        return new double[]{x, y, z};
    }

    private static double yawFromQuat(Quaterniond q) {
        return Math.atan2(2 * (q.w * q.y + q.x * q.z),
                1 - 2 * (q.y * q.y + q.x * q.x));
    }
}
