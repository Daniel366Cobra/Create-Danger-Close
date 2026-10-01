package io.github.daniel366cobra.createdangerclose.block.fire_control_solver;

import com.simibubi.create.Create;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.api.sensor.SolverServo;
import io.github.daniel366cobra.createdangerclose.ballistics.BallisticSolver;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonFiring;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import net.minecraft.server.level.ServerLevel;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import net.minecraft.world.phys.Vec3;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity.FireControlStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
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
import rbasamoyai.createbigcannons.config.CBCConfigs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;

/**
 * The Ballistic Computer: the core of the fire control computer, which works
 * out the firing solutions.
 *
 * <p>It is not a kinetic block. A Battle Network Interface facing it connects
 * it to the battle network: each time that interface polls, it drives one
 * poll of the computer ({@link #pollFrom}), which reads the sensors and
 * servos on the interface's network. When the interface stops, the link
 * lapses and the computer goes idle.
 *
 * <p>Sensors on the battle network act as <em>gates</em>: they decide which
 * capabilities the solver has, but their readings are not fed into the math.
 * The actual orientation, position and velocity come straight from the Sable
 * sublevel pose of each mount, which is what the sensors derive their own
 * readings from anyway. The only sensor value that is used is the target
 * position from a navigation table, which the pose cannot provide.
 */
public class FireControlSolverBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation {

    // The link to the interface lapses this long after its last poll
    // (a powered interface polls at least every 20 ticks).
    private static final int LINK_TIMEOUT_TICKS = 40;

    // The network interface driving this computer (server only), and when it last polled.
    @Nullable private BlockPos linkedInterface;
    private long lastLinkTick;

    // Without an Integrator, a rangefinder solution lapses this soon after its
    // last reading (two polls at the slowest poll rate, so it does not flicker).
    private static final int UNHELD_SOLUTION_LIFETIME_TICKS = 40;

    // --- Module gates: what is in the bank around this computer ---
    private final Set<ComputerModule> modules = EnumSet.noneOf(ComputerModule.class);
    // Another Ballistic Computer touches this one's modules: neither works.
    private boolean bankConflict = false;

    // Trajectory: the flat arc (direct fire, default) or the steep arc
    // (plunging fire). Set from Fire Mission Consoles; the last flip wins.
    private boolean plungingFire = false;

    // Per mount (server only): the barrel's current pitch and yaw in ship
    // frame as its servos read them last poll, for the firing interlock.
    private final Map<BlockPos, Float> mountPitches = new java.util.HashMap<>();
    private final Map<BlockPos, Float> mountYawsNow = new java.util.HashMap<>();
    // A gun is on target when both axes are this close to its solution.
    private static final double ON_TARGET_DEG = 0.5;

    /** What a salvo order did: guns fired, and guns held back (no solution, or not yet on target). */
    public record SalvoResult(int fired, int held) {}

    // --- Sensor gates: what is present on the battle network ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int navTargetCount = 0;
    private int mapTableCount = 0;
    private int altitudeCount = 0;
    // Velocity sensors per ship axis (X, Y, Z). Sensors on an axis that is
    // already covered are redundant: they count, but add no capability.
    private final int[] velocityAxisCounts = new int[3];

    // Whether the computer is on a physics object at all (tooltip warning).
    private boolean hasPose = false;

    // --- Target: rangefinder solution > map table > navigation table ---
    // Saved by ordinal: add new sources at the end.
    public enum TargetSource { NONE, NAV_TABLE, RANGEFINDER, MAP_TABLE }

    private boolean hasTarget = false;
    private TargetSource targetSource = TargetSource.NONE;
    private double targetX = 0;
    private double targetY = 0;
    private double targetZ = 0;
    // Tracked target velocity (rangefinder only), blocks per tick, world space.
    private boolean hasTargetVelocity = false;
    private double targetVelX = 0;
    private double targetVelY = 0;
    private double targetVelZ = 0;
    // Age and 1σ range error of the rangefinder reading, for the tooltip.
    private long targetAgeTicks = 0;
    private double targetSigma = 0;
    private int rangefinderCount = 0;
    // How many rangefinders' readings went into the target (1 = just the directing one).
    private int mergedRangefinders = 0;

    // Rangefinder in control of the guns (bearing position, server only). It
    // keeps control until its solution goes stale, so a second manned
    // rangefinder cannot yank the guns to a different target.
    @Nullable private BlockPos directingRangefinder;
    // Solutions within this many combined standard deviations of the
    // directing one are the same target and get merged...
    private static final double MERGE_SIGMAS = 3;
    // ...with at least this much tolerance, blocks.
    private static final double MIN_MERGE_DISTANCE = 4;

    // For map tables (server only): the rangefinder fixes and gun ranges of the last poll.
    private BattlePlot plot = BattlePlot.EMPTY;
    private List<BattlePlot.RangefinderFix> plotFixes = List.of();

    private final Map<BlockPos, BallisticSolver.Result> lastPitchResults = new java.util.HashMap<>();

    // Ship-frame solution per mount position, shared by its pitch and yaw servos.
    private final Map<BlockPos, FireSolution> aimCache = new java.util.HashMap<>();

    // Last commanded angles per mount, for estimating how fast they change.
    private record AimSample(double pitchDeg, double yawDeg, long tick, double pitchRate, double yawRate) {}
    private final Map<BlockPos, AimSample> aimHistory = new java.util.HashMap<>();
    // Longest poll gap to still estimate a rate over (poll interval is at most 20 ticks).
    private static final long MAX_RATE_SAMPLE_GAP = 40;
    // Faster apparent motion than this is a jump between solutions, not tracking.
    private static final double MAX_RATE_DEG_PER_TICK = 2.0;
    private static final double RATE_SMOOTHING = 0.5;

    private boolean isPowered = false;

    public FireControlSolverBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /**
     * The battle network: that of the interface driving this computer. Looked
     * up fresh on every use: Create replaces the {@link KineticNetwork}
     * instance when a network loses and regains its source, so a cached
     * reference goes stale.
     */
    @Nullable
    private KineticNetwork battleNetwork() {
        if (level == null || linkedInterface == null
                || !(level.getBlockEntity(linkedInterface) instanceof NetworkInterfaceBlockEntity networkInterface))
            return null;
        return Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(networkInterface);
    }

    /**
     * Called by a powered network interface facing this computer, each time
     * it polls: links the computer to that interface's network and runs one
     * poll. Only one interface can drive a computer; a second one is turned
     * away while the first is still polling.
     *
     * @return whether this interface is the one driving the computer
     */
    public boolean pollFrom(NetworkInterfaceBlockEntity networkInterface) {
        if (level == null || level.isClientSide())
            return false;
        BlockPos interfacePos = networkInterface.getBlockPos();
        if (linkedInterface != null && !linkedInterface.equals(interfacePos) && isLinkFresh())
            return false;

        linkedInterface = interfacePos;
        lastLinkTick = level.getGameTime();
        isPowered = true;
        scanBank();
        if (bankConflict) {
            invalidateData();
            clearServos();
        } else {
            pollNetwork();
            pollAndPushServos();
        }
        notifyUpdate();
        return true;
    }

    /** Rescans the bank each poll: it is at most a handful of blocks, and needs no bookkeeping. */
    private void scanBank() {
        ComputerBank.Scan scan = ComputerBank.scan(level, worldPosition);
        modules.clear();
        modules.addAll(scan.modules());
        bankConflict = scan.cores().size() > 1;
    }

    // --- Fire mission (from Fire Mission Consoles) ---

    public boolean isPlungingFire() {
        return plungingFire;
    }

    /** Switches between direct (flat arc) and plunging (steep arc) fire; takes effect from the next poll. */
    public void setPlungingFire(boolean plungingFire) {
        if (this.plungingFire == plungingFire)
            return;
        this.plungingFire = plungingFire;
        aimHistory.clear(); // the guns jump to the other arc: that is not motion to feed forward
        setChanged();
        notifyUpdate();
    }

    /**
     * Fires every gun that is ready: it has a firing solution, and its barrel
     * has settled on it (both axes within {@link #ON_TARGET_DEG}; a mount
     * without a yaw servo is judged on elevation alone). The rest hold fire.
     * Uses the last poll's solutions and barrel angles.
     */
    public SalvoResult fireSalvo() {
        if (!(level instanceof ServerLevel serverLevel) || !isPowered || bankConflict)
            return new SalvoResult(0, 0);
        int fired = 0, held = 0;
        for (Map.Entry<BlockPos, Float> entry : mountPitches.entrySet()) {
            BlockPos mountPos = entry.getKey();
            FireSolution solution = aimCache.get(mountPos);
            Float yaw = mountYawsNow.get(mountPos);
            boolean onTarget = solution != null && solution.valid()
                    && Math.abs(entry.getValue() - solution.pitchDeg()) <= ON_TARGET_DEG
                    && (yaw == null || Math.abs(wrapDeg(yaw - solution.yawDeg())) <= ON_TARGET_DEG);
            if (onTarget && CannonFiring.fire(serverLevel, mountPos))
                fired++;
            else
                held++;
        }
        return new SalvoResult(fired, held);
    }

    /** Whether the bank has this module, i.e. the computer has its capability. */
    public boolean has(ComputerModule module) {
        return modules.contains(module);
    }

    private void clearServos() {
        KineticNetwork network = battleNetwork();
        if (network == null) return;
        for (KineticBlockEntity member : network.members.keySet())
            if (member instanceof SolverServo servo)
                servo.receiveFiringSolution(null);
    }

    private boolean isLinkFresh() {
        return level != null && level.getGameTime() - lastLinkTick <= LINK_TIMEOUT_TICKS;
    }

    @Override
    public void tick() {
        super.tick();

        if (level == null || level.isClientSide()) return;

        // The interface stopped polling (unpowered, turned away, removed): go idle.
        if (isPowered && !isLinkFresh()) {
            isPowered = false;
            linkedInterface = null;
            invalidateData();
            notifyUpdate();
        }
    }

    private void invalidateData() {
        gimbalCount = navHeadingCount = navTargetCount = mapTableCount = altitudeCount = rangefinderCount = 0;
        java.util.Arrays.fill(velocityAxisCounts, 0);

        hasPose = false;

        hasTarget = false;
        targetSource = TargetSource.NONE;
        targetX = targetY = targetZ = 0;
        hasTargetVelocity = false;
        targetVelX = targetVelY = targetVelZ = 0;
        targetAgeTicks = 0;
        targetSigma = 0;
        mergedRangefinders = 0;

        plot = BattlePlot.EMPTY;
        plotFixes = List.of();
    }

    /** The rangefinder fixes and gun ranges of the last poll, for map tables. */
    public BattlePlot getPlot() {
        return plot;
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetwork();
        if (network == null) return;

        Map<BlockPos, SensorReading.Rangefinder> rangefinderReadings = new java.util.HashMap<>();
        List<RangefinderBearingBlockEntity> rangefinders = new ArrayList<>();
        SensorReading.MapTarget mapTarget = null;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof RangefinderBearingBlockEntity rangefinder)
                rangefinders.add(rangefinder);
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
                        targetSource = TargetSource.NAV_TABLE;
                    }
                }
                case SensorReading.Velocity v -> velocityAxisCounts[dominantAxis(v.normal())]++;
                case SensorReading.Altitude ignored -> altitudeCount++;
                case SensorReading.Rangefinder r -> {
                    rangefinderCount++;
                    if (isFresh(r))
                        rangefinderReadings.put(member.getBlockPos(), r);
                }
                case SensorReading.MapTarget m -> {
                    mapTableCount++;
                    // Several tables: the latest designation wins.
                    if (m.worldPos() != null
                            && (mapTarget == null || m.designatedTick() > mapTarget.designatedTick()))
                        mapTarget = m;
                }
                case SensorReading.Computer ignored -> { }
                case SensorReading.NavConsole ignored -> { }
                case SensorReading.FireConsole ignored -> { }
                case SensorReading.None ignored -> { }
            }
        }

        // A map table's target outranks navigation tables; rangefinders outrank both, below.
        if (mapTarget != null) {
            targetX = mapTarget.worldPos().x;
            targetY = mapTarget.worldPos().y;
            targetZ = mapTarget.worldPos().z;
            hasTarget = true;
            targetSource = TargetSource.MAP_TABLE;
        }

        List<BattlePlot.RangefinderFix> fixes = new ArrayList<>();
        for (Map.Entry<BlockPos, SensorReading.Rangefinder> entry : rangefinderReadings.entrySet()) {
            Vector3d position = extrapolate(entry.getValue());
            // The line of sight starts at the rangefinder bearing, wherever its ship is.
            Vec3 origin = RangefinderOptics.toWorld(level, Vec3.atCenterOf(entry.getKey()));
            fixes.add(new BattlePlot.RangefinderFix(position.x, position.y, position.z,
                    entry.getValue().rangeSigma(), origin.x, origin.z));
        }
        plotFixes = fixes;

        Set<BlockPos> merged = useRangefinderTargets(rangefinderReadings);
        for (RangefinderBearingBlockEntity rangefinder : rangefinders) {
            BlockPos pos = rangefinder.getBlockPos();
            rangefinder.setFireControlStatus(directingRangefinder == null ? FireControlStatus.NONE
                    : pos.equals(directingRangefinder) ? FireControlStatus.DIRECTING
                    : merged.contains(pos) ? FireControlStatus.MERGED
                    : FireControlStatus.STANDBY);
        }

        readOwnShipState();
    }

    private boolean isFresh(SensorReading.Rangefinder reading) {
        long age = level.getGameTime() - reading.sampleTick();
        // Without an Integrator nothing holds the solution between readings.
        long lifetime = has(ComputerModule.INTEGRATOR)
                ? RangefinderBearingBlockEntity.SOLUTION_LIFETIME_TICKS : UNHELD_SOLUTION_LIFETIME_TICKS;
        return age >= 0 && age <= lifetime;
    }

    /**
     * A reading carried forward to now on its tracked velocity. That takes
     * both the Differentiator (the velocity) and the Integrator (carrying it
     * forward); otherwise the reading stays where it was measured.
     */
    private Vector3d extrapolate(SensorReading.Rangefinder reading) {
        long age = level.getGameTime() - reading.sampleTick();
        Vector3d position = new Vector3d(reading.hitWorldPos().x, reading.hitWorldPos().y, reading.hitWorldPos().z);
        boolean carryForward = reading.targetVelocity() != null
                && has(ComputerModule.DIFFERENTIATOR) && has(ComputerModule.INTEGRATOR);
        return carryForward ? position.fma(age, reading.targetVelocity()) : position;
    }

    /**
     * Picks the target from the fresh rangefinder solutions; any of them
     * takes over from navigation tables.
     *
     * <p>Control: the directing rangefinder keeps control while its solution
     * is fresh; otherwise the freshest solution takes over. Merging: other
     * solutions on the same target (within {@link #MERGE_SIGMAS} combined
     * standard deviations) are averaged in, weighted by 1/σ², which gives a
     * better fix than any single rangefinder.
     *
     * <p>The target is carried forward to now on its tracked velocity, so the
     * guns keep leading it between readings.
     *
     * @return positions of the rangefinders merged into the directing one's solution
     */
    private Set<BlockPos> useRangefinderTargets(Map<BlockPos, SensorReading.Rangefinder> readings) {
        if (directingRangefinder == null || !readings.containsKey(directingRangefinder)) {
            directingRangefinder = readings.entrySet().stream()
                    .max(java.util.Comparator.comparingLong(e -> e.getValue().sampleTick()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
        if (directingRangefinder == null)
            return Set.of();

        SensorReading.Rangefinder directing = readings.get(directingRangefinder);
        Vector3d directingPosition = extrapolate(directing);

        Vector3d weightedPosition = new Vector3d();
        Vector3d weightedVelocity = new Vector3d();
        double positionWeight = 0;
        double velocityWeight = 0;
        Set<BlockPos> merged = new java.util.HashSet<>();

        for (Map.Entry<BlockPos, SensorReading.Rangefinder> entry : readings.entrySet()) {
            SensorReading.Rangefinder reading = entry.getValue();
            Vector3d position = extrapolate(reading);
            boolean isDirecting = entry.getKey().equals(directingRangefinder);
            if (!isDirecting) {
                if (!has(ComputerModule.CORRELATOR))
                    continue; // nothing to merge readings with: standby
                double tolerance = Math.max(MIN_MERGE_DISTANCE, MERGE_SIGMAS * Math.sqrt(
                        directing.rangeSigma() * directing.rangeSigma() + reading.rangeSigma() * reading.rangeSigma()));
                if (position.distance(directingPosition) > tolerance)
                    continue; // a different target: standby
                merged.add(entry.getKey());
            }
            double weight = 1 / Math.max(1e-4, reading.rangeSigma() * reading.rangeSigma());
            weightedPosition.fma(weight, position);
            positionWeight += weight;
            if (reading.targetVelocity() != null) {
                weightedVelocity.fma(weight, reading.targetVelocity());
                velocityWeight += weight;
            }
        }

        weightedPosition.div(positionWeight);
        // Leading the target takes a Differentiator.
        hasTargetVelocity = velocityWeight > 0 && has(ComputerModule.DIFFERENTIATOR);
        if (hasTargetVelocity)
            weightedVelocity.div(velocityWeight);
        targetVelX = hasTargetVelocity ? weightedVelocity.x : 0;
        targetVelY = hasTargetVelocity ? weightedVelocity.y : 0;
        targetVelZ = hasTargetVelocity ? weightedVelocity.z : 0;

        targetX = weightedPosition.x;
        targetY = weightedPosition.y;
        targetZ = weightedPosition.z;
        hasTarget = true;
        targetSource = TargetSource.RANGEFINDER;
        targetAgeTicks = level.getGameTime() - directing.sampleTick();
        targetSigma = 1 / Math.sqrt(positionWeight);
        mergedRangefinders = 1 + merged.size();
        return merged;
    }

    /** Whether the computer sits on a physics object, for the tooltip warning. */
    private void readOwnShipState() {
        hasPose = level != null && Sable.HELPER.getContaining(level, worldPosition) != null;
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
        KineticNetwork network = battleNetwork();
        if (network == null) return;

        lastPitchResults.clear();
        aimCache.clear();

        // Pitch servos run the ballistic solve (they know the shell and charge)
        // and cache the result per mount. Yaw servos run afterwards so both axes
        // of a mount aim along the same 3D direction.
        List<SolverServo> yawServos = new ArrayList<>();
        // For the map table: the armed mounts, and the barrel yaw its yaw servo reads.
        List<ServoStatus.MountSnapshot> pitchMounts = new ArrayList<>();
        Map<BlockPos, Float> mountYaws = new java.util.HashMap<>();

        for (KineticBlockEntity member : network.members.keySet()) {
            if (!(member instanceof SolverServo servo)) continue;

            var status = servo.getServoStatus();
            if (status == null || status.mounts().isEmpty() || !status.powered()) {
                servo.receiveFiringSolution(null);
                continue;
            }

            if (status.axis() == PITCH) {
                servo.receiveFiringSolution(withRates(status, solvePitch(status)));
                pitchMounts.add(status.mounts().get(0));
            } else if (status.axis() == YAW) {
                yawServos.add(servo);
                ServoStatus.MountSnapshot mount = status.mounts().get(0);
                mountYaws.put(mount.mountPos(), mount.currentAngle());
            } else {
                servo.receiveFiringSolution(null);
            }
        }

        for (SolverServo servo : yawServos) {
            ServoStatus status = servo.getServoStatus();
            servo.receiveFiringSolution(withRates(status, solveYaw(status)));
        }

        // Where each barrel points now, for the firing interlock.
        mountPitches.clear();
        for (ServoStatus.MountSnapshot mount : pitchMounts)
            mountPitches.put(mount.mountPos(), mount.currentAngle());
        mountYawsNow.clear();
        mountYawsNow.putAll(mountYaws);

        List<BattlePlot.GunRange> guns = new ArrayList<>();
        for (ServoStatus.MountSnapshot mount : pitchMounts) {
            BattlePlot.GunRange range = gunRange(mount, mountYaws.get(mount.mountPos()));
            if (range != null)
                guns.add(range);
        }
        plot = plotFixes.isEmpty() && guns.isEmpty() ? BattlePlot.EMPTY : new BattlePlot(plotFixes, guns);
    }

    /**
     * How far a mount can reach with its current shell and charge, for the
     * map table: down to the target's height, or without a target to the
     * ground (or sea level, if unloaded) under the mount. Uses the same top
     * pitch as the solver (45°, or less if the mount cannot elevate that far),
     * so it matches where solutions turn into "out of range". The ship's own
     * motion is left out.
     *
     * @param shipYawDeg the barrel's yaw in ship frame, as the mount's yaw servo
     *                   reads it; null if the mount has no yaw servo
     */
    @Nullable
    private BattlePlot.GunRange gunRange(ServoStatus.MountSnapshot mount, @Nullable Float shipYawDeg) {
        if (level == null || !mount.hasShell())
            return null;
        double charge = mount.totalChargePower() + mount.addedChargePower();
        if (mount.minimumChargePower() > charge)
            return null;

        SubLevel mountSubLevel = subLevelOf(mount);
        Vector3d muzzleWorld = mountSubLevel != null
                ? mountSubLevel.logicalPose().transformPosition(blockCenter(mount.mountPos()), new Vector3d())
                : blockCenter(mount.mountPos());

        double referenceY;
        if (hasTarget) {
            referenceY = targetY;
        } else {
            int x = Mth.floor(muzzleWorld.x), z = Mth.floor(muzzleWorld.z);
            referenceY = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                    : level.getSeaLevel();
        }

        double radius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                Math.min(45, mount.maxElevation()), referenceY - muzzleWorld.y);
        if (radius <= 0)
            return null;

        // The barrel's bearing: its ship-frame yaw (same convention as the
        // solutions) turned into the world, flattened onto the map.
        double aimX = 0, aimZ = 0;
        if (shipYawDeg != null) {
            double yaw = Math.toRadians(shipYawDeg);
            Vector3d barrel = new Vector3d(-Math.sin(yaw), 0, Math.cos(yaw));
            if (mountSubLevel != null)
                mountSubLevel.logicalPose().orientation().transform(barrel);
            double horizontal = Math.sqrt(barrel.x * barrel.x + barrel.z * barrel.z);
            if (horizontal > 1e-6) {
                aimX = barrel.x / horizontal;
                aimZ = barrel.z / horizontal;
            }
        }
        // The dead zone around the gun, for a level ship (as a plotting officer
        // would draw it from the range table). Plunging fire: the steepest shot
        // comes down this far out. Direct fire, only with the target below the
        // gun: the lowest the barrel depresses still lands this far out.
        double minRadius = 0;
        double relativeY = referenceY - muzzleWorld.y;
        if (plungingFire) {
            if (mount.maxElevation() > 45)
                minRadius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                        BallisticSolver.steepestPlungingPitch(mount.maxElevation()), relativeY);
        } else if (relativeY < 0) {
            minRadius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                    mount.maxDepression(), relativeY);
        }
        return new BattlePlot.GunRange(muzzleWorld.x, muzzleWorld.z, radius, aimX, aimZ, minRadius);
    }

    /**
     * Attaches the rate at which this mount's commanded angles are changing,
     * estimated from consecutive polls, so servos can feed it forward instead
     * of lagging behind a moving aim point.
     */
    @Nullable
    private FireSolution withRates(ServoStatus status, @Nullable FireSolution solution) {
        // Rates are the Differentiator's job; without it servos only chase the angles.
        if (solution == null || level == null || !has(ComputerModule.DIFFERENTIATOR)) return solution;

        BlockPos mountPos = status.mounts().get(0).mountPos();
        long now = level.getGameTime();
        AimSample prev = aimHistory.get(mountPos);

        // Pitch and yaw servos of one mount are solved in the same poll;
        // the second one reuses the estimate.
        if (prev != null && prev.tick() == now) {
            return solution.withRates(prev.pitchRate(), prev.yawRate());
        }

        double pitchRate = 0;
        double yawRate = 0;
        if (prev != null) {
            long dt = now - prev.tick();
            if (dt > 0 && dt <= MAX_RATE_SAMPLE_GAP) {
                double rawPitch = (solution.pitchDeg() - prev.pitchDeg()) / dt;
                double rawYaw = wrapDeg(solution.yawDeg() - prev.yawDeg()) / dt;
                // A jump (e.g. rest -> tracking, new target) is not motion.
                if (Math.abs(rawPitch) <= MAX_RATE_DEG_PER_TICK && Math.abs(rawYaw) <= MAX_RATE_DEG_PER_TICK) {
                    // Light smoothing against solver jitter.
                    pitchRate = RATE_SMOOTHING * rawPitch + (1 - RATE_SMOOTHING) * prev.pitchRate();
                    yawRate = RATE_SMOOTHING * rawYaw + (1 - RATE_SMOOTHING) * prev.yawRate();
                }
            }
        }

        aimHistory.put(mountPos, new AimSample(solution.pitchDeg(), solution.yawDeg(), now, pitchRate, yawRate));
        return solution.withRates(pitchRate, yawRate);
    }

    private static double wrapDeg(double deg) {
        deg %= 360.0;
        if (deg > 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    private FireSolution solveYaw(ServoStatus status) {
        ServoStatus.MountSnapshot mount = status.mounts().get(0);

        if (!hasTarget) return restSolution(mount);

        FireSolution cached = aimCache.get(mount.mountPos());
        if (cached != null) {
            logSolve(mount, "YAW", "BALLISTIC", null, cached.pitchDeg(), cached.yawDeg(), null);
            return new FireSolution(cached.pitchDeg(), cached.yawDeg(), 0, true);
        }

        // No ballistic solution for this mount (no pitch servo, no shell, ...).
        // Keep tracking the target along the line of sight.
        if (!hasOrientationSensors()) return null;

        SubLevel mountSubLevel = subLevelOf(mount);
        if (mountSubLevel == null) return null;

        Vector3d mountWorld = mountSubLevel.logicalPose()
                .transformPosition(blockCenter(mount.mountPos()), new Vector3d());
        Vector3d toTargetWorld = new Vector3d(targetX, targetY, targetZ).sub(mountWorld);
        if (toTargetWorld.lengthSquared() < 1e-8) return null;

        Vector3d shipDir = mountSubLevel.logicalPose().orientation()
                .transformInverse(toTargetWorld.normalize(), new Vector3d());
        logSolve(mount, "YAW", "LINE_OF_SIGHT", null, pitchOf(shipDir), yawOf(shipDir), null);
        return new FireSolution(pitchOf(shipDir), yawOf(shipDir), 0, true);
    }

    // --- Debug logging (one line per servo per poll) ---
    private static final boolean DEBUG_SOLVER_LOG = false;

    /**
     * Columns: servo axis; outcome (pitch solve reason, or where the yaw
     * command came from); world pitch/yaw and time of flight from the
     * ballistic solver; ship-frame pitch/yaw sent; inherited launch velocity
     * (blocks/tick, world) and the number of velocity sensor axes.
     */
    private void logSolve(ServoStatus.MountSnapshot mount, String axis, String outcome,
                          @Nullable BallisticSolver.Result result, double shipPitch, double shipYaw,
                          @Nullable Vector3d inherited) {
        if (!DEBUG_SOLVER_LOG || level == null) return;
        CreateDangerClose.LOGGER.info(String.format(
                "[Solver %s] t=%d %-5s %-14s wPitch=%8.3f wYaw=%8.3f tof=%6.1f sPitch=%8.3f sYaw=%8.3f " +
                        "u=(%7.4f %7.4f %7.4f) axes=%d",
                mount.mountPos().toShortString(), level.getGameTime(), axis, outcome,
                result != null ? result.pitchDeg() : Double.NaN,
                result != null ? result.yawDeg() : Double.NaN,
                result != null ? result.timeOfFlightTicks() : Double.NaN,
                shipPitch, shipYaw,
                inherited != null ? inherited.x : Double.NaN,
                inherited != null ? inherited.y : Double.NaN,
                inherited != null ? inherited.z : Double.NaN,
                velocityAxesCovered()));
    }

    private FireSolution solvePitch(ServoStatus status) {
        BlockPos servoPos = status.servoPos();
        ServoStatus.MountSnapshot mount = status.mounts().get(0);
        SubLevel mountSubLevel = subLevelOf(mount);

        if (!hasTarget) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_TARGET));
            return restSolution(mount);
        }

        if (mountSubLevel == null) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.DEGENERATE));
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
        Vector3d targetVel = new Vector3d(targetVelX, targetVelY, targetVelZ);

        double muzzleVelocity = charge;
        double gravity = mount.gravity();
        double drag = mount.drag();
        boolean isQuadraticDrag = mount.isQuadraticDrag();

        Vector3d inheritedVelocity = inheritedLaunchVelocity(mountSubLevel, mount.mountPos());

        // The mount's elevation limits apply in ship frame, so let the solver
        // search the whole world-frame arc and check the limits afterwards.
        BallisticSolver.Result result = BallisticSolver.solve(
                muzzleWorld, targetWorld, targetVel, inheritedVelocity,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                90.0, -90.0, plungingFire);

        if (!result.valid()) {
            lastPitchResults.put(servoPos, result);
            logSolve(mount, "PITCH", result.reason().name(), result, Double.NaN, Double.NaN, inheritedVelocity);
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
            logSolve(mount, "PITCH", "OUT_OF_ARC", result, shipPitchDeg, shipYawDeg, inheritedVelocity);
            return null;
        }

        lastPitchResults.put(servoPos, result);
        logSolve(mount, "PITCH", "OK", result, shipPitchDeg, shipYawDeg, inheritedVelocity);

        // A fuze time of 0 leaves the fuze as loaded; setting it takes a Fuze Computer.
        FireSolution solution = new FireSolution(shipPitchDeg, shipYawDeg,
                has(ComputerModule.FUZE_COMPUTER) ? result.fuzeTicks() : 0, true);
        aimCache.put(mount.mountPos(), solution);
        return solution;
    }

    /**
     * The velocity a shell fired from this mount picks up from the ship,
     * world frame, blocks/tick.
     *
     * <p>When CBC's Sable compat launches a shell out of a sub-level, it adds
     * the sub-level's velocity at the shell's position (see CBC's
     * {@code EntitySubLevelUtilMixin}), unless the server config disables it.
     * The solver only compensates along ship axes it has velocity sensors for.
     */
    private Vector3d inheritedLaunchVelocity(SubLevel mountSubLevel, BlockPos mountPos) {
        // Compensating for own-ship speed takes an Integrator (and velocity sensors).
        if (level == null || velocityAxesCovered() == 0 || !has(ComputerModule.INTEGRATOR)
                || !CBCConfigs.server().compat.sableProjectilesInheritPhysicsObjectVelocity.get()) {
            return new Vector3d();
        }

        Quaterniondc shipToWorld = mountSubLevel.logicalPose().orientation();
        Vector3d worldVel = Sable.HELPER.getVelocity(level, mountSubLevel, blockCenter(mountPos), new Vector3d());

        // Keep only the ship-frame components we have sensors for.
        Vector3d shipVel = shipToWorld.transformInverse(worldVel, new Vector3d()).mul(velocityAxisMask());
        return shipToWorld.transform(shipVel).div(20.0); // m/s -> blocks/tick
    }

    /**
     * Rest position with no target: level barrel, pointing the way the cannon
     * faced when it was assembled. Ship-frame, so it needs no pose or sensors.
     */
    private static FireSolution restSolution(ServoStatus.MountSnapshot mount) {
        return new FireSolution(0, mount.facingYaw(), 0, true);
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
        this.bankConflict = compound.getBoolean("BankConflict");
        this.plungingFire = compound.getBoolean("PlungingFire");
        this.modules.clear();
        int moduleBits = compound.getInt("Modules");
        for (ComputerModule module : ComputerModule.values())
            if ((moduleBits & 1 << module.ordinal()) != 0)
                this.modules.add(module);

        this.gimbalCount = compound.getInt("GimbalCount");
        this.navHeadingCount = compound.getInt("NavHeadingCount");
        this.navTargetCount = compound.getInt("NavTargetCount");
        this.mapTableCount = compound.getInt("MapTableCount");
        this.altitudeCount = compound.getInt("AltitudeCount");
        this.velocityAxisCounts[0] = compound.getInt("VelocityCountX");
        this.velocityAxisCounts[1] = compound.getInt("VelocityCountY");
        this.velocityAxisCounts[2] = compound.getInt("VelocityCountZ");

        this.hasPose = compound.getBoolean("HasPose");

        this.hasTarget = compound.getBoolean("HasTarget");
        this.targetX = compound.getDouble("TargetX");
        this.targetY = compound.getDouble("TargetY");
        this.targetZ = compound.getDouble("TargetZ");
        TargetSource[] sources = TargetSource.values();
        int sourceOrdinal = compound.getInt("TargetSource");
        this.targetSource = sourceOrdinal >= 0 && sourceOrdinal < sources.length ? sources[sourceOrdinal] : TargetSource.NONE;
        this.hasTargetVelocity = compound.getBoolean("HasTargetVelocity");
        this.targetVelX = compound.getDouble("TargetVelX");
        this.targetVelY = compound.getDouble("TargetVelY");
        this.targetVelZ = compound.getDouble("TargetVelZ");
        this.targetAgeTicks = compound.getLong("TargetAge");
        this.targetSigma = compound.getDouble("TargetSigma");
        this.rangefinderCount = compound.getInt("RangefinderCount");
        this.mergedRangefinders = compound.getInt("MergedRangefinders");

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
        compound.putBoolean("BankConflict", bankConflict);
        compound.putBoolean("PlungingFire", plungingFire);
        int moduleBits = 0;
        for (ComputerModule module : modules)
            moduleBits |= 1 << module.ordinal();
        compound.putInt("Modules", moduleBits);

        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("NavTargetCount", navTargetCount);
        compound.putInt("MapTableCount", mapTableCount);
        compound.putInt("AltitudeCount", altitudeCount);
        compound.putInt("VelocityCountX", velocityAxisCounts[0]);
        compound.putInt("VelocityCountY", velocityAxisCounts[1]);
        compound.putInt("VelocityCountZ", velocityAxisCounts[2]);

        compound.putBoolean("HasPose", hasPose);

        compound.putBoolean("HasTarget", hasTarget);
        compound.putDouble("TargetX", targetX);
        compound.putDouble("TargetY", targetY);
        compound.putDouble("TargetZ", targetZ);
        compound.putInt("TargetSource", targetSource.ordinal());
        compound.putBoolean("HasTargetVelocity", hasTargetVelocity);
        compound.putDouble("TargetVelX", targetVelX);
        compound.putDouble("TargetVelY", targetVelY);
        compound.putDouble("TargetVelZ", targetVelZ);
        compound.putLong("TargetAge", targetAgeTicks);
        compound.putDouble("TargetSigma", targetSigma);
        compound.putInt("RangefinderCount", rangefinderCount);
        compound.putInt("MergedRangefinders", mergedRangefinders);

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
                    .add(Component.translatable("createdangerclose.fire_control_solver.not_connected"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        if (bankConflict) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.bank_conflict"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        // --- Modules: installed ones, then what each missing one costs ---
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.fire_control_solver.modules"))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        for (ComputerModule module : ComputerModule.values()) {
            boolean installed = has(module);
            CDCLang.builder()
                    .add(Component.literal(installed ? "  ✔ " : "  ✖ "))
                    .add(Component.translatable("block.createdangerclose." + module.id()))
                    .add(installed ? Component.empty() : Component.literal(": ").append(Component.translatable(
                            "createdangerclose.fire_control_solver.module_missing." + module.id())))
                    .style(installed ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }

        if (!hasPose) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.no_sublevel"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // --- Missing sensors (the ship's own readings are on the Navigation Console) ---
        if (!hasOrientationSensors()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_orientation"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }
        if (!hasFullVelocity()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_velocity"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }
        if (!hasAltitudeSensor()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_altitude"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // Target and firing solutions are shown on the Fire Mission Console.
        if (!hasTarget) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.fire_control_solver.missing_target"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // Debug counts
        int velocityCount = velocityAxisCounts[0] + velocityAxisCounts[1] + velocityAxisCounts[2];
        CDCLang.builder()
                .add(Component.literal(String.format(
                        "  [G:%d H:%d T:%d M:%d V:%d A:%d R:%d]",
                        gimbalCount, navHeadingCount, navTargetCount, mapTableCount, velocityCount, altitudeCount,
                        rangefinderCount)))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        return true;
    }

    /**
     * The fire mission: target, its source and speed, the trajectory mode and
     * each gun's solution. Shown by Fire Mission Consoles, which read it from
     * this computer (client side, from its synced data).
     */
    public void addFireMissionTooltip(List<Component> tooltip) {
        CDCLang.builder()
                .add(Component.translatable(plungingFire
                        ? "createdangerclose.fire_mission_console.mode.plunging"
                        : "createdangerclose.fire_mission_console.mode.direct"))
                .style(ChatFormatting.AQUA)
                .forGoggles(tooltip);

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

            Component source;
            if (targetSource == TargetSource.MAP_TABLE) {
                source = Component.translatable("createdangerclose.fire_control_solver.target_source.map_table");
            } else if (targetSource != TargetSource.RANGEFINDER) {
                source = Component.translatable("createdangerclose.fire_control_solver.target_source.nav_table");
            } else if (mergedRangefinders > 1) {
                source = Component.translatable("createdangerclose.fire_control_solver.target_source.rangefinders_merged",
                        mergedRangefinders, String.format("%.1f", targetAgeTicks / 20.0), String.format("%.0f", targetSigma));
            } else {
                source = Component.translatable("createdangerclose.fire_control_solver.target_source.rangefinder",
                        String.format("%.1f", targetAgeTicks / 20.0), String.format("%.0f", targetSigma));
            }
            CDCLang.builder()
                    .add(source)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            if (hasTargetVelocity) {
                double speed = Math.sqrt(targetVelX * targetVelX + targetVelY * targetVelY + targetVelZ * targetVelZ) * 20;
                CDCLang.builder()
                        .add(Component.translatable("createdangerclose.fire_control_solver.target_speed"))
                        .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                        .add(CDCLang.numberWithUnit(speed, "createdangerclose.unit.velocity").withStyle(ChatFormatting.GOLD))
                        .forGoggles(tooltip);
            }
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
    }
}
