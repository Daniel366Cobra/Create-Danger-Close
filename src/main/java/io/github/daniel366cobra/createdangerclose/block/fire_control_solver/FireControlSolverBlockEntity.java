package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;

/**
 * Fire control solver.
 *
 * <p>Sensors on the battle network act as <em>gates</em>: they decide which
 * capabilities the solver has, but their readings are not fed into the math.
 * The actual orientation, position and velocity come straight from the Sable
 * sublevel pose of each mount, which is what the sensors derive their own
 * readings from anyway. The only sensor value that is used is the target
 * position from a navigation table, which the pose cannot provide.
 */
public class FireControlSolverBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation {

    public static final float MIN_OPERATING_RPM = 32.0f;

    // --- Sensor gates: what is present on the battle network ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int navTargetCount = 0;
    private int altitudeCount = 0;
    // Velocity sensors per ship axis (X, Y, Z). Sensors on an axis that is
    // already covered are redundant: they count, but add no capability.
    private final int[] velocityAxisCounts = new int[3];

    // --- Own-ship state from the sublevel pose (tooltip only) ---
    private boolean hasPose = false;
    private double shipPitchDeg = 0;
    private double shipHeadingDeg = 0;
    private double shipRollDeg = 0;
    // Ship-frame velocity at the solver, m/s.
    private double shipVelX = 0;
    private double shipVelY = 0;
    private double shipVelZ = 0;
    private double shipAltitude = 0;

    // --- Target, from a navigation table ---
    private boolean hasTarget = false;
    private double targetX = 0;
    private double targetY = 0;
    private double targetZ = 0;

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
        gimbalCount = navHeadingCount = navTargetCount = altitudeCount = 0;
        java.util.Arrays.fill(velocityAxisCounts, 0);

        hasPose = false;
        shipPitchDeg = shipHeadingDeg = shipRollDeg = 0;
        shipVelX = shipVelY = shipVelZ = 0;
        shipAltitude = 0;

        hasTarget = false;
        targetX = targetY = targetZ = 0;
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetworkReference.get();
        if (network == null) return;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member == this) continue;
            if (!(member instanceof SensorReading.Provider provider)) continue;

            switch (provider.getSensorReading()) {
                case SensorReading.Gimbal ignored -> gimbalCount++;
                case SensorReading.NavHeading ignored -> navHeadingCount++;
                case SensorReading.NavTarget t -> {
                    navTargetCount++;
                    if (!hasTarget) {
                        targetX = t.worldPos().x;
                        targetY = t.worldPos().y;
                        targetZ = t.worldPos().z;
                        hasTarget = true;
                    }
                }
                case SensorReading.Velocity v -> velocityAxisCounts[dominantAxis(v.normal())]++;
                case SensorReading.Altitude ignored -> altitudeCount++;
                case SensorReading.Rangefinder ignored -> { }
                case SensorReading.None ignored -> { }
            }
        }

        readOwnShipState();
    }

    /** Samples the solver's own sublevel pose for the tooltip. */
    private void readOwnShipState() {
        SubLevel subLevel = subLevelReference.get();
        if (subLevel == null || level == null) return;

        Quaterniondc q = subLevel.logicalPose().orientation();

        // Heading/pitch of the ship's +Z axis, roll of its +X axis about it.
        Vector3d forward = q.transform(new Vector3d(0, 0, 1));
        Vector3d right = q.transform(new Vector3d(1, 0, 0));
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        shipHeadingDeg = yawOf(forward);
        shipPitchDeg = pitchOf(forward);
        shipRollDeg = Math.toDegrees(Math.atan2(right.y, up.y));

        Vector3d center = blockCenter(worldPosition);
        Vector3d worldVel = Sable.HELPER.getVelocity(level, subLevel, center, new Vector3d());
        Vector3d shipVel = q.transformInverse(worldVel, new Vector3d());
        shipVelX = shipVel.x;
        shipVelY = shipVel.y;
        shipVelZ = shipVel.z;

        shipAltitude = subLevel.logicalPose().transformPosition(center, new Vector3d()).y;
        hasPose = true;
    }

    // --- Gates ---

    /** Gimbal (tilt) and magnet navigation table (heading) are both present. */
    public boolean hasOrientationSensors() {
        return gimbalCount > 0 && navHeadingCount > 0;
    }

    public boolean hasAltitudeSensor() {
        return altitudeCount > 0;
    }

    /** Number of distinct ship axes covered by at least one velocity sensor. */
    public int velocityAxesCovered() {
        int n = 0;
        for (int count : velocityAxisCounts) if (count > 0) n++;
        return n;
    }

    /** Full 3D velocity is known only with sensors on all three ship axes. */
    public boolean hasFullVelocity() {
        return velocityAxesCovered() == 3;
    }

    /**
     * Ship-frame mask for the velocity the solver may compensate for: 1 on
     * axes covered by a velocity sensor, 0 elsewhere.
     */
    public Vector3d velocityAxisMask() {
        return new Vector3d(
                velocityAxisCounts[0] > 0 ? 1 : 0,
                velocityAxisCounts[1] > 0 ? 1 : 0,
                velocityAxisCounts[2] > 0 ? 1 : 0);
    }

    // --- Solving ---

    private void pollAndPushServos() {
        KineticNetwork network = Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(this);
        if (network == null) return;

        lastPitchResults.clear();
        aimCache.clear();

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
                servo.receiveFiringSolution(solvePitch(status));
            } else if (status.axis() == YAW) {
                yawServos.add(servo);
            } else {
                servo.receiveFiringSolution(null);
            }
        }

        for (SolverServo servo : yawServos) {
            servo.receiveFiringSolution(solveYaw(servo.getServoStatus()));
        }
    }

    private FireSolution solveYaw(ServoStatus status) {
        ServoStatus.MountSnapshot mount = status.mounts().get(0);

        FireSolution cached = aimCache.get(mount.mountPos());
        if (cached != null) {
            return new FireSolution(cached.pitchDeg(), cached.yawDeg(), 0, true);
        }

        // No ballistic solution for this mount (no pitch servo, no shell, ...).
        // Keep tracking the target along the line of sight.
        if (!hasTarget || !hasOrientationSensors()) return null;

        SubLevel mountSubLevel = subLevelOf(mount);
        if (mountSubLevel == null) return null;

        Vector3d mountWorld = mountSubLevel.logicalPose()
                .transformPosition(blockCenter(mount.mountPos()), new Vector3d());
        Vector3d toTargetWorld = new Vector3d(targetX, targetY, targetZ).sub(mountWorld);
        if (toTargetWorld.lengthSquared() < 1e-8) return null;

        Vector3d shipDir = mountSubLevel.logicalPose().orientation()
                .transformInverse(toTargetWorld.normalize(), new Vector3d());
        return new FireSolution(pitchOf(shipDir), yawOf(shipDir), 0, true);
    }

    private FireSolution solvePitch(ServoStatus status) {
        BlockPos servoPos = status.servoPos();
        ServoStatus.MountSnapshot mount = status.mounts().get(0);
        SubLevel mountSubLevel = subLevelOf(mount);

        if (!hasTarget || mountSubLevel == null) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_TARGET));
            return null;
        }

        if (!hasOrientationSensors()) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_ORIENTATION));
            return null;
        }

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

        Quaterniondc shipToWorld = mountSubLevel.logicalPose().orientation();
        Vector3d muzzleWorld = mountSubLevel.logicalPose()
                .transformPosition(blockCenter(mount.mountPos()), new Vector3d());

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
     * The sublevel the mount itself sits on. Usually the solver's own, but a
     * mount on a separately simulated body must use that body's pose.
     */
    @Nullable
    private SubLevel subLevelOf(ServoStatus.MountSnapshot mount) {
        if (level == null) return null;
        return Sable.HELPER.getContaining(level, mount.mountPos());
    }

    private static Vector3d blockCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static int dominantAxis(Vector3dc n) {
        double ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
        if (ax >= ay && ax >= az) return 0;
        return ay >= az ? 1 : 2;
    }

    private static double pitchOf(Vector3d dir) {
        return Math.toDegrees(Math.asin(Math.clamp(dir.y, -1.0, 1.0)));
    }

    private static double yawOf(Vector3d dir) {
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        return yaw < 0 ? yaw + 360 : yaw;
    }

    // --- Accessors for other blocks (fire control panel, map table, etc.) ---

    public boolean hasPose() { return hasPose; }
    public boolean hasTarget() { return hasTarget; }

    public double getShipPitchDeg() { return shipPitchDeg; }
    public double getShipHeadingDeg() { return shipHeadingDeg; }
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
    // The state is re-derived from the network and pose each poll, so it is
    // only synced for the client tooltip.

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.isPowered = compound.getBoolean("IsPowered");

        this.gimbalCount = compound.getInt("GimbalCount");
        this.navHeadingCount = compound.getInt("NavHeadingCount");
        this.navTargetCount = compound.getInt("NavTargetCount");
        this.altitudeCount = compound.getInt("AltitudeCount");
        this.velocityAxisCounts[0] = compound.getInt("VelocityCountX");
        this.velocityAxisCounts[1] = compound.getInt("VelocityCountY");
        this.velocityAxisCounts[2] = compound.getInt("VelocityCountZ");

        this.hasPose = compound.getBoolean("HasPose");
        this.shipPitchDeg = compound.getDouble("ShipPitch");
        this.shipHeadingDeg = compound.getDouble("ShipHeading");
        this.shipRollDeg = compound.getDouble("ShipRoll");
        this.shipVelX = compound.getDouble("VelX");
        this.shipVelY = compound.getDouble("VelY");
        this.shipVelZ = compound.getDouble("VelZ");
        this.shipAltitude = compound.getDouble("Altitude");

        this.hasTarget = compound.getBoolean("HasTarget");
        this.targetX = compound.getDouble("TargetX");
        this.targetY = compound.getDouble("TargetY");
        this.targetZ = compound.getDouble("TargetZ");

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
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("IsPowered", isPowered);

        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("NavTargetCount", navTargetCount);
        compound.putInt("AltitudeCount", altitudeCount);
        compound.putInt("VelocityCountX", velocityAxisCounts[0]);
        compound.putInt("VelocityCountY", velocityAxisCounts[1]);
        compound.putInt("VelocityCountZ", velocityAxisCounts[2]);

        compound.putBoolean("HasPose", hasPose);
        compound.putDouble("ShipPitch", shipPitchDeg);
        compound.putDouble("ShipHeading", shipHeadingDeg);
        compound.putDouble("ShipRoll", shipRollDeg);
        compound.putDouble("VelX", shipVelX);
        compound.putDouble("VelY", shipVelY);
        compound.putDouble("VelZ", shipVelZ);
        compound.putDouble("Altitude", shipAltitude);

        compound.putBoolean("HasTarget", hasTarget);
        compound.putDouble("TargetX", targetX);
        compound.putDouble("TargetY", targetY);
        compound.putDouble("TargetZ", targetZ);

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

        if (!hasPose) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.no_sublevel"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
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
                    .add(CDCLang.numberWithUnit(shipHeadingDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
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
                    .add(CDCLang.numberWithUnit(shipHeadingDeg, "createdangerclose.unit.angle").withStyle(ChatFormatting.GREEN))
                    .forGoggles(tooltip);
        }
        if (!hasOrientationSensors()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_orientation"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Velocity ---
        if (velocityAxesCovered() > 0) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.ship_velocity"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(0, shipVelX))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(1, shipVelY))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(velocityComponent(2, shipVelZ))
                    .forGoggles(tooltip);

            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.velocity_axes",
                            velocityAxisCount(0), velocityAxisCount(1), velocityAxisCount(2)))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        }
        if (!hasFullVelocity()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_velocity"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Altitude ---
        if (hasAltitudeSensor()) {
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
        int velocityCount = velocityAxisCounts[0] + velocityAxisCounts[1] + velocityAxisCounts[2];
        CDCLang.builder()
                .add(Component.literal(String.format(
                        "  [G:%d H:%d T:%d V:%d A:%d]",
                        gimbalCount, navHeadingCount, navTargetCount, velocityCount, altitudeCount)))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        return true;
    }

    /** A ship-frame velocity component, or a dash if no sensor covers that axis. */
    private MutableComponent velocityComponent(int axis, double value) {
        if (velocityAxisCounts[axis] == 0) {
            return Component.literal("--").withStyle(ChatFormatting.DARK_GRAY);
        }
        return CDCLang.numberWithUnit(value, "createdangerclose.unit.velocity").withStyle(ChatFormatting.WHITE);
    }

    /** Sensor count for an axis: red if missing, yellow if redundant. */
    private MutableComponent velocityAxisCount(int axis) {
        int count = velocityAxisCounts[axis];
        ChatFormatting color = count == 0 ? ChatFormatting.RED
                : count > 1 ? ChatFormatting.YELLOW
                : ChatFormatting.WHITE;
        return Component.literal(Integer.toString(count)).withStyle(color);
    }
}
