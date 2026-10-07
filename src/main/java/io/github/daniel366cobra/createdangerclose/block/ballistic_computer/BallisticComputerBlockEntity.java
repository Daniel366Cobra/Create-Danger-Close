package io.github.daniel366cobra.createdangerclose.block.ballistic_computer;

import com.simibubi.create.Create;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.api.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.api.sensor.ServoStatus;
import io.github.daniel366cobra.createdangerclose.api.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.api.ballistics.BallisticSolver;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonFiring;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.createmod.catnip.animation.LerpedFloat;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import net.minecraft.world.phys.Vec3;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity.FireControlStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import rbasamoyai.createbigcannons.config.CBCConfigs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.YAW;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.FIXED_GUN;
import static io.github.daniel366cobra.createdangerclose.api.sensor.ServoAxis.SWIVEL;

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
 * capabilities the computer has, but their readings are not fed into the math.
 * The actual orientation, position and velocity come straight from the Sable
 * sublevel pose of each mount, which is what the sensors derive their own
 * readings from anyway. The only sensor value that is used is the target
 * position from a navigation table, which the pose cannot provide.
 */
public class BallisticComputerBlockEntity extends SmartBlockEntity
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
    // Fuze correction added to every timed fuze it sets, ticks (negative bursts
    // early, positive late), and the game time it was set at a Fire Mission
    // Console: of several consoles, the most recently set one wins.
    private int fuzeOffsetTicks = 0;
    private long fuzeOffsetSetTick = Long.MIN_VALUE;
    // The same for the autocannons, set at an Autocannon Control Console: rounds and shells
    // are corrected apart, each by its own console's knob.
    private int autocannonFuzeOffsetTicks = 0;
    private long autocannonFuzeOffsetSetTick = Long.MIN_VALUE;
    // Shortest fuze it sets, ticks: a fuze set to burst at once would go off in the barrel.
    private static final int MIN_FUZE_TICKS = 1;
    // Re-solves from the muzzle where the last solve aimed the barrel: each pass
    // shrinks the launch point's error roughly by the change in aim, so two settle it.
    private static final int MUZZLE_REFINEMENT_PASSES = 2;
    // The frame of a gun ashore: the world itself.
    private static final Quaterniondc WORLD_FRAME = new Quaterniond();

    // Per mount (server only): each gun as its pitch servo read it last poll, for
    // the firing interlock: where its barrel points, in the frame it stands in.
    private final Map<BlockPos, ServoStatus.MountSnapshot> barrels = new HashMap<>();
    // The swivel bearings on the network this poll, by the sub-level their plate carries.
    private final Map<UUID, Joint> jointByPlate = new HashMap<>();
    // Per bearing (by position): the gun it served last poll, which it keeps serving.
    private final Map<BlockPos, BlockPos> directors = new HashMap<>();
    // The guns a bearing could not bring round last poll (past its dial's limits): tried last.
    private final Set<BlockPos> outOfArcLastPoll = new HashSet<>();
    // Bearings carrying bearings, at most this deep (guards against loops).
    private static final int MAX_CHAIN = 8;
    // Two drives turn a gun about the same axis when their axes are this close to parallel.
    private static final double PARALLEL_AXES_COS = 0.98;
    // A barrel this close to a bearing's axis (squared sine) cannot be swung by it.
    private static final double ALONG_AXIS_SQ = 1e-4;
    // The computer's health record, and the bias its jostled cabinets and interfaces add to
    // every aim (degrees; found anew each poll).
    private EquipmentHealth health;
    private float bankBiasYaw, bankBiasPitch;
    // Per gun riding bearings laid for another gun: its range to the target, blocks.
    private final Map<BlockPos, Double> passengerRange = new HashMap<>();

    /** What a salvo order did: guns fired, and guns held back (no solution, or not yet on target). */
    public record SalvoResult(int fired, int held, boolean awaitingConfirmation) {
        public SalvoResult(int fired, int held) {
            this(fired, held, false);
        }
    }

    // Autocannons this computer has set firing, and when a console last ordered them to fire:
    // with no order for a moment (trigger released, weapons free off) they cease fire.
    private final Set<BlockPos> autocannonsFiring = new HashSet<>();
    private long autocannonOrderTick;
    private static final int AUTOCANNON_ORDER_TIMEOUT_TICKS = 2;

    // Danger close (server): the guns whose shells would land near our own side this poll,
    // where our own side stands (world), and when a warned salvo was armed (-1: not armed).
    private final Set<BlockPos> dangerCloseGuns = new HashSet<>();
    private final List<Vec3> friendlies = new ArrayList<>();
    private long dangerArmedTick = -1;

    // --- Sensor gates: what is present on the battle network ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int altitudeCount = 0;
    // Velocity sensors per ship axis (X, Y, Z). Sensors on an axis that is
    // already covered are redundant: they count, but add no capability.
    private final int[] velocityAxisCounts = new int[3];

    // Whether any gun it directs is on a ship (synced): only those need attitude and
    // velocity sensors; guns ashore aim in the world's own frame.
    private boolean directsShipGuns = false;

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
    // When the directing rangefinder took its reading (server), for telling a live target from a held one.
    private long targetSampleTick;
    private double targetSigma = 0;
    // How many rangefinders' readings went into the target (1 = just the directing one).
    private int mergedRangefinders = 0;

    // Rangefinder in control of the guns (bearing position, server only). It
    // keeps control until its solution goes stale, so a second manned
    // rangefinder cannot yank the guns to a different target.
    @Nullable private BlockPos directingRangefinder;
    // Solutions within this many combined standard deviations of the
    // directing one are the same target and get merged...
    private static final double MERGE_SIGMAS = 3;
    // A navigation table's target is a block position: within half a block, a
    // standard deviation of 1 / √12 blocks.
    private static final double NAV_TARGET_SIGMA = 1 / Math.sqrt(12);
    // ...with at least this much tolerance, blocks.
    private static final double MIN_MERGE_DISTANCE = 4;

    // For map tables (server only): the rangefinder fixes and gun ranges of the last poll.
    private BattlePlot plot = BattlePlot.EMPTY;
    private List<BattlePlot.RangefinderFix> plotFixes = List.of();

    private final Map<BlockPos, BallisticSolver.Result> lastPitchResults = new HashMap<>();
    // Which of those lines are autocannons' (synced): each console lists only its own guns.
    private final Set<BlockPos> autocannonReports = new HashSet<>();

    // Ship-frame solution per mount position, shared by its pitch and yaw servos.
    private final Map<BlockPos, FireSolution> aimCache = new HashMap<>();

    // Last commanded angles per mount, for estimating how fast they change.
    private record AimSample(double pitchDeg, double yawDeg, long tick, double pitchRate, double yawRate) {}
    private final Map<BlockPos, AimSample> aimHistory = new HashMap<>();
    // Longest poll gap to still estimate a rate over (poll interval is at most 20 ticks).
    private static final long MAX_RATE_SAMPLE_GAP = 40;
    // Faster apparent motion than this is a jump between solutions, not tracking.
    private static final double MAX_RATE_DEG_PER_TICK = 2.0;
    private static final double RATE_SMOOTHING = 0.5;
    // Last commanded angle per driven swivel bearing, the same way: a turret laid on a moving
    // target would otherwise trail it by its angular rate over the servo's correction gain.
    // Kept per gun it is laid for and per elevation sense, since a change of either jumps the angle.
    private record JointSample(double angleDeg, long tick, double rate, BlockPos director, int sense) {}
    private final Map<BlockPos, JointSample> jointHistory = new HashMap<>();

    private boolean isPowered = false;

    // --- Front panel ---
    // Server-computed readings, synced (all zero without a target): the target's
    // bearing clockwise from the computer's front (degrees); its horizontal range
    // (blocks), and that range as a fraction of the first gun's reach; the first
    // gun's elevation solution (degrees).
    private float panelBearingDeg;
    private float panelRange;
    private float panelRangeFraction;
    private float panelElevationDeg;
    // ...and the fuze time set for it, ticks: time of flight plus the fuze correction.
    private int panelFuzeTicks;
    // Also synced, for the modules: the battle network's speed (RPM, 0 unpowered); the
    // ship's own speed as the velocity sensors see it (blocks per second, 0 without an
    // Integrator); and how long the target is still held (ticks left, out of a lifetime,
    // as of a game time), which the client counts down between syncs if the target ages.
    private float panelNetworkRpm;
    private float panelOwnSpeed;
    private long panelHoldLeftTicks;
    private long panelHoldLifetimeTicks = 1;
    private long panelHoldStampTick;
    private boolean panelHoldAges;
    // Client only: the panel's needle, counter, cam and follower ease towards the readings.
    private final LerpedFloat bearingNeedle = LerpedFloat.angular().startWithValue(0);
    private final LerpedFloat rangeCounter = LerpedFloat.linear().startWithValue(0);
    private final LerpedFloat camAngle = LerpedFloat.angular().startWithValue(0);
    private final LerpedFloat camFollower = LerpedFloat.linear().startWithValue(0);
    private static final float PANEL_CHASE_SPEED = 0.2f;

    public BallisticComputerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.MECHANISM, true)
                .lubricated(() -> panelNetworkRpm, () -> isPowered)
                .sharingLubricationWith(() -> ComputerBank.lubricationGroup(level, worldPosition)));
    }

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
        // A broken or seized computer works out nothing.
        if (bankConflict || health.isOutOfOrder()) {
            invalidateData();
            clearServos();
        } else {
            pollNetwork();
            pollAndPushServos();
        }
        updatePanel();
        notifyUpdate();
        return true;
    }

    /** Rescans the bank each poll: it is at most a handful of blocks, and needs no bookkeeping. */
    private void scanBank() {
        ComputerBank.Scan scan = ComputerBank.scan(level, worldPosition);
        modules.clear();
        modules.addAll(scan.modules());
        bankConflict = scan.cores().size() > 1;
        // Every jostled cabinet throws the computation off a little: their biases add up.
        bankBiasYaw = 0;
        bankBiasPitch = 0;
        for (BlockPos member : scan.members()) {
            EquipmentHealth memberHealth = EquipmentHealth.at(level, member);
            if (memberHealth != null) {
                bankBiasYaw += memberHealth.biasYaw();
                bankBiasPitch += memberHealth.biasPitch();
            }
        }
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
        jointHistory.clear();
        setChanged();
        notifyUpdate();
    }

    /**
     * Fires every gun that is ready: it has a firing solution, and its barrel
     * has settled on it (within the on-target tolerance, whatever turns it; a gun riding
     * bearings laid for another gun, within the salvo spread of the target: config). The rest hold fire.
     * Uses the last poll's solutions and barrel angles.
     */
    public SalvoResult fireSalvo() {
        if (!(level instanceof ServerLevel serverLevel) || !isPowered || bankConflict)
            return new SalvoResult(0, 0);
        // Danger close: a gun about to fire would land near our own side. The first press only
        // warns; a second within the confirmation window fires.
        CServer.DangerClose dangerConfig = CDCConfig.server().dangerClose;
        boolean danger = false;
        for (BlockPos mountPos : dangerCloseGuns)
            danger |= !isAutocannon(mountPos) && isOnTarget(mountPos);
        long now = level.getGameTime();
        if (danger && dangerConfig.allowDangerClose.get() && (dangerArmedTick < 0
                || now - dangerArmedTick > dangerConfig.confirmSeconds.get() * 20)) {
            dangerArmedTick = now;
            return new SalvoResult(0, bigCannonCount(), true);
        }
        dangerArmedTick = -1;
        int fired = 0, held = 0;
        for (BlockPos mountPos : barrels.keySet()) {
            if (isAutocannon(mountPos))
                continue;
            if (isOnTarget(mountPos) && CannonFiring.fire(serverLevel, mountPos))
                fired++;
            else
                held++;
        }
        return new SalvoResult(fired, held);
    }

    /** The guns that would fire right now (as fired) and those that would hold (server). */
    public SalvoResult gunsOnTarget() {
        return onTarget(false);
    }

    /** The autocannons that would fire right now, and those that would hold (server). */
    public SalvoResult autocannonsOnTarget() {
        return onTarget(true);
    }

    private SalvoResult onTarget(boolean autocannons) {
        int ready = 0, total = 0;
        for (BlockPos mountPos : barrels.keySet()) {
            if (isAutocannon(mountPos) != autocannons)
                continue;
            total++;
            if (isPowered && !bankConflict && isOnTarget(mountPos))
                ready++;
        }
        return new SalvoResult(ready, total - ready);
    }

    /**
     * Fires every autocannon on target, one round each as its breech allows (called every
     * tick while firing; the breech keeps its own rate). Danger-close ones only once
     * confirmed. Returns how many fired.
     */
    public int fireAutocannons(boolean dangerCloseConfirmed) {
        if (!(level instanceof ServerLevel serverLevel))
            return 0;
        autocannonOrderTick = level.getGameTime();
        boolean checkDanger = CDCConfig.server().dangerClose.allowDangerClose.get() && !dangerCloseConfirmed;
        int firing = 0;
        for (BlockPos mountPos : barrels.keySet()) {
            if (!isAutocannon(mountPos))
                continue;
            boolean fire = isPowered && !bankConflict && isOnTarget(mountPos)
                    && !(checkDanger && dangerCloseGuns.contains(mountPos));
            setAutocannonFiring(serverLevel, mountPos, fire);
            if (fire)
                firing++;
        }
        return firing;
    }

    /** Puts an autocannon at full rate of fire, or ceases its fire (only changes are sent). */
    private void setAutocannonFiring(ServerLevel serverLevel, BlockPos mountPos, boolean fire) {
        if (fire == autocannonsFiring.contains(mountPos))
            return;
        if (fire)
            autocannonsFiring.add(mountPos);
        else
            autocannonsFiring.remove(mountPos);
        CannonFiring.setAutocannonFireRate(serverLevel, mountPos, fire ? CannonFiring.AUTOCANNON_FULL_RATE : 0);
    }

    /** Broken or picked up: the autocannons it set firing cease fire. */
    @Override
    public void remove() {
        if (level instanceof ServerLevel serverLevel)
            ceaseAutocannonFire(serverLevel);
        super.remove();
    }

    /** Ceases fire on every autocannon this computer set firing. */
    private void ceaseAutocannonFire(ServerLevel serverLevel) {
        for (BlockPos mountPos : new ArrayList<>(autocannonsFiring))
            setAutocannonFiring(serverLevel, mountPos, false);
    }

    /**
     * Whether the target is being ranged right now (server): a rangefinder's reading no older
     * than two of its intervals (readings, like polls, come at the network's pace), or a map or
     * navigation table's target, which stands. Not a solution the Integrator is only holding.
     */
    public boolean isTargetLive() {
        if (!hasTarget || level == null)
            return false;
        if (targetSource != TargetSource.RANGEFINDER)
            return true;
        float rpm = Math.max(1, panelNetworkRpm);
        long window = 2L * Math.round(640f / rpm) + 5;
        return level.getGameTime() - targetSampleTick <= window;
    }

    /** Whether an autocannon on target would land near our own side (last poll). */
    public boolean isAutocannonDangerClose() {
        for (BlockPos mountPos : dangerCloseGuns)
            if (isAutocannon(mountPos) && isOnTarget(mountPos))
                return true;
        return false;
    }

    private boolean isAutocannon(BlockPos mountPos) {
        ServoStatus.MountSnapshot mount = barrels.get(mountPos);
        return mount != null && mount.autocannon();
    }

    private int bigCannonCount() {
        int count = 0;
        for (BlockPos mountPos : barrels.keySet())
            if (!isAutocannon(mountPos))
                count++;
        return count;
    }

    /**
     * Whether this gun's barrel actually points along its solution (last poll):
     * the angle between the two directions, whatever turns the barrel (its mount,
     * a turret, or the ship steering onto the bearing).
     */
    private boolean isOnTarget(BlockPos mountPos) {
        FireSolution solution = aimCache.get(mountPos);
        ServoStatus.MountSnapshot barrel = barrels.get(mountPos);
        if (solution == null || !solution.valid() || barrel == null)
            return false;
        Vector3d pointing = barrel.barrel() != null
                ? new Vector3d(barrel.barrel().x, barrel.barrel().y, barrel.barrel().z)
                : aimDirection(barrel.currentAngle(), barrel.mountYaw());
        Vector3d wanted = aimDirection(solution.pitchDeg(), solution.yawDeg());
        double off = pointing.angle(wanted);
        // A gun riding bearings laid for another gun (side by side on one cradle) fires when its
        // shell would land within the salvo spread: about the guns' spacing, which no bearing
        // can take out of both at once.
        CServer.FireControl config = CDCConfig.server().fireControl;
        if (Math.toDegrees(off) <= config.onTargetTolerance.getF())
            return true;
        Double range = passengerRange.get(mountPos);
        return range != null && off * range <= config.salvoSpread.getF();
    }

    /**
     * Takes a console's fuze correction, if it was set more recently than the one in use
     * (server): a Fire Mission Console's for the big cannons, an Autocannon Control
     * Console's for the autocannons.
     */
    public void offerFuzeOffset(int ticks, long setTick, boolean autocannons) {
        long inUseTick = autocannons ? autocannonFuzeOffsetSetTick : fuzeOffsetSetTick;
        int inUse = autocannons ? autocannonFuzeOffsetTicks : fuzeOffsetTicks;
        if (setTick < inUseTick || (setTick == inUseTick && ticks == inUse))
            return;
        if (autocannons)
            autocannonFuzeOffsetSetTick = setTick;
        else
            fuzeOffsetSetTick = setTick;
        if (inUse != ticks) {
            if (autocannons)
                autocannonFuzeOffsetTicks = ticks;
            else
                fuzeOffsetTicks = ticks;
            setChanged();
        }
    }

    private int fuzeOffset(boolean autocannon) {
        return autocannon ? autocannonFuzeOffsetTicks : fuzeOffsetTicks;
    }

    /** A time of flight with the fuze correction added, as set on the shells or rounds, ticks. */
    private int correctedFuze(int timeOfFlightTicks, boolean autocannon) {
        return Math.max(MIN_FUZE_TICKS, timeOfFlightTicks + fuzeOffset(autocannon));
    }

    /**
     * The delay for delayed impact and inertia fuzes, ticks: the fuze correction
     * itself (they count from impact, not launch), never below none.
     */
    private int impactDelay(boolean autocannon) {
        return Math.max(0, fuzeOffset(autocannon));
    }

    /** Whether the bank has this module, i.e. the computer has its capability. */
    public boolean has(ComputerModule module) {
        return modules.contains(module);
    }

    private void clearServos() {
        KineticNetwork network = battleNetwork();
        if (network == null) return;
        for (KineticBlockEntity member : network.members.keySet())
            if (member instanceof ComputerServo servo)
                servo.receiveFiringSolution(null);
    }

    private boolean isLinkFresh() {
        return level != null && level.getGameTime() - lastLinkTick <= LINK_TIMEOUT_TICKS;
    }

    @Override
    public void tick() {
        super.tick();

        if (level != null && level.isClientSide()) {
            tickPanel();
            return;
        }
        if (level == null) return;

        // No console ordering the autocannons any more: cease fire.
        if (!autocannonsFiring.isEmpty() && level instanceof ServerLevel serverLevel
                && level.getGameTime() - autocannonOrderTick > AUTOCANNON_ORDER_TIMEOUT_TICKS)
            ceaseAutocannonFire(serverLevel);

        // The interface stopped polling (unpowered, turned away, removed): go idle.
        if (isPowered && !isLinkFresh()) {
            isPowered = false;
            linkedInterface = null;
            invalidateData();
            updatePanel();
            notifyUpdate();
        }
    }

    private void invalidateData() {
        gimbalCount = navHeadingCount = altitudeCount = 0;
        Arrays.fill(velocityAxisCounts, 0);


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

    // --- Front panel ---

    /** Works out what the front panel shows from the last poll (server). */
    private void updatePanel() {
        panelNetworkRpm = isPowered && linkedInterface != null
                && level.getBlockEntity(linkedInterface) instanceof NetworkInterfaceBlockEntity networkInterface
                ? Math.abs(networkInterface.getSpeed()) : 0;
        SubLevel ship = Sable.HELPER.getContaining(level, worldPosition);
        panelOwnSpeed = 0;
        if (isPowered && ship != null && has(ComputerModule.INTEGRATOR) && velocityAxesCovered() > 0) {
            Quaterniondc shipToWorld = ship.logicalPose().orientation();
            Vector3d worldVel = Sable.HELPER.getVelocity(level, ship, blockCenter(worldPosition), new Vector3d());
            panelOwnSpeed = (float) shipToWorld.transformInverse(worldVel).mul(velocityAxisMask()).length();
        }

        panelHoldStampTick = level.getGameTime();
        panelHoldLifetimeTicks = solutionLifetimeTicks();
        if (!isPowered || bankConflict || !hasTarget) {
            panelBearingDeg = panelRange = panelRangeFraction = panelElevationDeg = 0;
            panelFuzeTicks = 0;
            panelHoldLeftTicks = 0;
            panelHoldAges = false;
            return;
        }
        // Map and navigation table targets stand until changed: always fully held.
        panelHoldAges = targetSource == TargetSource.RANGEFINDER;
        panelHoldLeftTicks = panelHoldAges
                ? Math.max(0, panelHoldLifetimeTicks - targetAgeTicks) : panelHoldLifetimeTicks;

        Vec3 here = RangefinderOptics.toWorld(level, Vec3.atCenterOf(worldPosition));
        // Bearing in the ship's frame, so it stays put while the ship turns with the target.
        Vector3d toTarget = new Vector3d(targetX - here.x, targetY - here.y, targetZ - here.z);
        if (ship != null)
            ship.logicalPose().orientation().transformInverse(toTarget);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x, toTarget.z));
        Direction front = getBlockState().getValue(BallisticComputerBlock.FACING);
        panelBearingDeg = Mth.wrapDegrees(targetYaw - front.toYRot());

        panelRange = (float) Math.hypot(targetX - here.x, targetZ - here.z);
        double reach = plot.guns().isEmpty() ? 0 : plot.guns().get(0).radius();
        panelRangeFraction = reach > 0 ? (float) Mth.clamp(panelRange / reach, 0, 1) : 0;
        BallisticSolver.Result firstSolution = lastPitchResults.values().stream()
                .filter(BallisticSolver.Result::valid)
                .findFirst()
                .orElse(null);
        panelElevationDeg = firstSolution != null ? (float) firstSolution.pitchDeg() : 0;
        panelFuzeTicks = firstSolution != null ? correctedFuze(firstSolution.fuzeTicks(), false) : 0;
    }

    /** Eases the panel's moving parts towards the synced readings (client). */
    private void tickPanel() {
        // Broken or seized: the dials stand frozen where they stopped.
        if (health.isOutOfOrder()) {
            for (LerpedFloat value : new LerpedFloat[]{bearingNeedle, rangeCounter, camAngle, camFollower})
                ComputerModuleBlockEntity.hold(value);
            return;
        }
        bearingNeedle.chase(panelBearingDeg, PANEL_CHASE_SPEED, LerpedFloat.Chaser.EXP);
        rangeCounter.chase(panelRange, PANEL_CHASE_SPEED, LerpedFloat.Chaser.EXP);
        camAngle.chase(panelElevationDeg, PANEL_CHASE_SPEED, LerpedFloat.Chaser.EXP);
        camFollower.chase(panelRangeFraction, PANEL_CHASE_SPEED, LerpedFloat.Chaser.EXP);
        bearingNeedle.tickChaser();
        rangeCounter.tickChaser();
        camAngle.tickChaser();
        camFollower.tickChaser();
    }

    /** Target bearing on the dial, degrees clockwise from the computer's front (client). */
    public float getBearingNeedle(float partialTicks) {
        return bearingNeedle.getValue(partialTicks);
    }

    /** Horizontal range to the target shown on the counter, blocks (client). */
    public float getRangeCounter(float partialTicks) {
        return rangeCounter.getValue(partialTicks);
    }

    /** Ballistic cam turn: the first gun's elevation, degrees (client). */
    public float getCamAngle(float partialTicks) {
        return camAngle.getValue(partialTicks);
    }

    /** The fuze time set for the first gun (time of flight plus correction), seconds; 0 without a solution (client). */
    public float getFuzeSeconds() {
        return panelFuzeTicks / 20f;
    }

    /** The battle network's speed, RPM; 0 while unpowered (client). */
    /** Ponder only: the network speed its modules' mechanisms show (there is no real network there). */
    public void setPonderNetworkRpm(float rpm) {
        panelNetworkRpm = rpm;
    }

    public float getNetworkRpm() {
        return panelNetworkRpm;
    }

    /** The ship's own speed as the velocity sensors see it, blocks per second; 0 without an Integrator (client). */
    public float getOwnSpeed() {
        return panelOwnSpeed;
    }

    /**
     * How much of the target's hold is left, 1 (fresh, or a target that
     * does not age) to 0 (expired, or no target), counted down between syncs (client).
     */
    public float getHoldLeft(float partialTicks) {
        if (level == null)
            return 0;
        if (!panelHoldAges)
            return panelHoldLeftTicks > 0 ? 1 : 0;
        float elapsed = level.getGameTime() + partialTicks - panelHoldStampTick;
        return Mth.clamp((panelHoldLeftTicks - Math.max(0, elapsed)) / panelHoldLifetimeTicks, 0, 1);
    }

    /** Cam follower travel: the range as a fraction of the first gun's reach (client). */
    public float getCamFollower(float partialTicks) {
        return camFollower.getValue(partialTicks);
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetwork();
        if (network == null) return;

        Map<BlockPos, SensorReading.Rangefinder> rangefinderReadings = new HashMap<>();
        List<RangefinderBearingBlockEntity> rangefinders = new ArrayList<>();
        // Our own side, for danger close: the computer, and the spotters (the guns join later).
        friendlies.clear();
        Set<MapId> plottedMaps = new HashSet<>();
        friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(worldPosition)));
        SensorReading.MapTarget mapTarget = null;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof RangefinderBearingBlockEntity rangefinder) {
                rangefinders.add(rangefinder);
                friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(rangefinder.getBlockPos())));
            }
            // Every crew station (a console, a map table, a sensor) is reached through an
            // interface: where the interfaces are, people stand. And a map table's maps say
            // where friendlies carrying them are.
            if (member instanceof NetworkInterfaceBlockEntity station) {
                friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(station.getBlockPos())));
                MapTableBlockEntity table = station.getFacedMapTable();
                if (table != null)
                    plottedMaps.addAll(table.getMapIds());
            }
            if (!(member instanceof SensorReading.Provider provider)) continue;

            // A jostled interface reading the ship's attitude, or driving the computer, skews
            // every aim as a jostled cabinet does.
            if (member instanceof NetworkInterfaceBlockEntity networkInterface
                    && (provider.getSensorReading() instanceof SensorReading.Gimbal
                    || provider.getSensorReading() instanceof SensorReading.NavHeading
                    || provider.getSensorReading() instanceof SensorReading.Computer)) {
                bankBiasYaw += networkInterface.getHealth().biasYaw();
                bankBiasPitch += networkInterface.getHealth().biasPitch();
            }

            switch (provider.getSensorReading()) {
                case SensorReading.Gimbal ignored -> gimbalCount++;
                case SensorReading.NavHeading ignored -> navHeadingCount++;
                case SensorReading.NavTarget t -> {
                    if (!hasTarget) {
                        targetX = t.worldPos().x;
                        targetY = t.worldPos().y;
                        targetZ = t.worldPos().z;
                        hasTarget = true;
                        targetSource = TargetSource.NAV_TABLE;
                        targetSigma = NAV_TARGET_SIGMA;
                    }
                }
                case SensorReading.Velocity v -> velocityAxisCounts[v.dominantAxis()]++;
                case SensorReading.Altitude ignored -> altitudeCount++;
                case SensorReading.Rangefinder r -> {
                    if (isFresh(r))
                        rangefinderReadings.put(member.getBlockPos(), r);
                }
                case SensorReading.MapTarget m -> {
                    // Several tables: the latest designation wins.
                    if (m.worldPos() != null
                            && (mapTarget == null || m.designatedTick() > mapTarget.designatedTick()))
                        mapTarget = m;
                }
                case SensorReading.Computer ignored -> { }
                case SensorReading.NavConsole ignored -> { }
                case SensorReading.FireConsole ignored -> { }
                case SensorReading.FixedGun ignored -> { }
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
            targetSigma = mapTarget.sigma();
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
            rangefinder.setStabilisation(gimbalCount > 0, navHeadingCount > 0);
        }

        // Players carrying one of the plotted maps: plotted friendlies.
        if (!plottedMaps.isEmpty())
            for (Player player : level.players())
                if (carriesAnyOf(player, plottedMaps))
                    friendlies.add(player.position());
    }

    private static boolean carriesAnyOf(Player player, Set<MapId> maps) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            MapId id = inventory.getItem(slot).get(DataComponents.MAP_ID);
            if (id != null && maps.contains(id))
                return true;
        }
        return false;
    }

    private boolean isFresh(SensorReading.Rangefinder reading) {
        long age = level.getGameTime() - reading.sampleTick();
        return age >= 0 && age <= solutionLifetimeTicks();
    }

    /** How long a rangefinder solution stays usable: without an Integrator nothing holds it between readings. */
    private long solutionLifetimeTicks() {
        return has(ComputerModule.INTEGRATOR)
                ? RangefinderBearingBlockEntity.SOLUTION_LIFETIME_TICKS : UNHELD_SOLUTION_LIFETIME_TICKS;
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
                    .max(Comparator.comparingLong(e -> e.getValue().sampleTick()))
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
        Set<BlockPos> merged = new HashSet<>();

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
        targetSampleTick = directing.sampleTick();
        targetSigma = 1 / Math.sqrt(positionWeight);
        mergedRangefinders = 1 + merged.size();
        return merged;
    }

    // --- Gates ---

    /** Gimbal (tilt) and magnet navigation table (heading) are both present. */
    public boolean hasOrientationSensors() {
        return gimbalCount > 0 && navHeadingCount > 0;
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
     * Ship-frame mask for the velocity the computer may compensate for: 1 on
     * axes covered by a velocity sensor, 0 elsewhere.
     */
    public Vector3d velocityAxisMask() {
        return new Vector3d(
                velocityAxisCounts[0] > 0 ? 1 : 0,
                velocityAxisCounts[1] > 0 ? 1 : 0,
                velocityAxisCounts[2] > 0 ? 1 : 0);
    }

    // --- Solving ---


    /** A gun the computer aims: a cannon mount with a pitch servo, or a fixed mount read by an interface. */
    private record Gun(ServoStatus.MountSnapshot mount, ServoStatus status, ComputerServo servo) {
        BlockPos pos() { return mount.mountPos(); }
        /** Where its line on the console is kept: the servo or interface reporting it. */
        BlockPos reportPos() { return status.servoPos(); }
        boolean ownPitchDrive() { return status.axis() == PITCH; }
    }

    /** A swivel bearing on the network, and the servo turning it (if it can: an invalid setup does not). */
    private record Joint(ServoStatus.SwivelSnapshot swivel, ComputerServo servo, boolean driven, ServoLimits limits) {}

    private void pollAndPushServos() {
        KineticNetwork network = battleNetwork();
        if (network == null) return;

        lastPitchResults.clear();
        autocannonReports.clear();
        aimCache.clear();
        directsShipGuns = false;
        jointByPlate.clear();

        // Read the network: guns, the mounts' own yaw servos, and the bearings carrying them.
        List<Gun> guns = new ArrayList<>();
        Map<BlockPos, ComputerServo> yawServoOf = new HashMap<>();
        Map<BlockPos, ServoStatus> yawStatusOf = new HashMap<>();
        for (KineticBlockEntity member : network.members.keySet()) {
            if (!(member instanceof ComputerServo servo)) continue;

            ServoStatus status = servo.getServoStatus();
            if (status == null || !status.powered()) {
                servo.receiveFiringSolution(null);
                continue;
            }
            // A swivel is part of the chain even in an invalid setup, which its servo does not drive.
            if (status.swivel() != null && status.swivel().plate() != null) {
                jointByPlate.putIfAbsent(status.swivel().plate(),
                        new Joint(status.swivel(), servo, status.axis() == SWIVEL, status.limits()));
                continue;
            }
            if (status.mounts().isEmpty()) {
                servo.receiveFiringSolution(null);
                continue;
            }
            ServoStatus.MountSnapshot mount = status.mounts().get(0);
            switch (status.axis()) {
                case PITCH, FIXED_GUN -> guns.add(new Gun(mount, status, servo));
                case YAW -> {
                    yawServoOf.put(mount.mountPos(), servo);
                    yawStatusOf.put(mount.mountPos(), status);
                }
                default -> servo.receiveFiringSolution(null);
            }
        }

        // Each gun: what aims it, and whether that can work. Then its solution.
        Set<BlockPos> redundant = new HashSet<>();
        Map<BlockPos, List<Joint>> chains = new HashMap<>();
        for (Gun gun : guns) {
            List<Joint> chain = chainOf(gun.mount());
            if (gun.mount().autocannon())
                autocannonReports.add(gun.reportPos());
            chains.put(gun.pos(), chain);
            if (onShip(gun.mount()))
                directsShipGuns = true;

            if (hasRedundantDrives(gun.mount(), gun.ownPitchDrive(), yawServoOf.containsKey(gun.pos()), chain)) {
                redundant.add(gun.pos());
                lastPitchResults.put(gun.reportPos(),
                        BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.REDUNDANT_DRIVES));
                gun.servo().receiveFiringSolution(null);
                continue;
            }
            FireSolution solution = solveGun(gun.reportPos(), gun.mount(),
                    gun.ownPitchDrive() ? gun.status() : null, yawStatusOf.get(gun.pos()));
            gun.servo().receiveFiringSolution(gun.ownPitchDrive() ? withRates(gun.status(), solution) : solution);
        }

        // The mounts' own yaw servos, after the solve so both axes aim along one direction.
        for (Map.Entry<BlockPos, ComputerServo> entry : yawServoOf.entrySet()) {
            ServoStatus status = yawStatusOf.get(entry.getKey());
            ServoStatus.MountSnapshot mount = status.mounts().get(0);
            if (onShip(mount))
                directsShipGuns = true;
            // A mount with no pitch servo is no gun here, but its yaw may still fight a bearing.
            boolean isGun = chains.containsKey(entry.getKey());
            if (redundant.contains(entry.getKey())
                    || !isGun && hasRedundantDrives(mount, false, true, chainOf(mount))) {
                if (!isGun)
                    lastPitchResults.put(status.servoPos(),
                            BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.REDUNDANT_DRIVES));
                entry.getValue().receiveFiringSolution(null);
                continue;
            }
            entry.getValue().receiveFiringSolution(withRates(status, solveYaw(status)));
        }

        aimBearings(guns, chains, redundant);

        // Where each barrel points now, for the firing interlock.
        barrels.clear();
        for (Gun gun : guns)
            barrels.put(gun.pos(), gun.mount());

        // Danger close: which guns would land near our own side (their pivots join the list).
        List<SubLevel> hulls = new ArrayList<>();
        SubLevel ownHull = level != null ? Sable.HELPER.getContaining(level, worldPosition) : null;
        if (ownHull != null)
            hulls.add(ownHull);
        for (Gun gun : guns) {
            SubLevel standsOn = subLevelOf(gun.mount());
            if (standsOn != null && !hulls.contains(standsOn))
                hulls.add(standsOn);
            for (Joint joint : chains.get(gun.pos())) {
                SubLevel under = Sable.HELPER.getContaining(level, joint.swivel().swivelPos());
                if (under != null && !hulls.contains(under))
                    hulls.add(under);
            }
            Vector3d pivot = launchPivot(gun.mount(), subLevelOf(gun.mount()));
            friendlies.add(new Vec3(pivot.x, pivot.y, pivot.z));
        }
        dangerCloseGuns.clear();
        for (Gun gun : guns) {
            FireSolution aim = aimCache.get(gun.pos());
            if (aim != null && aim.valid() && isDangerClose(gun.mount(), hulls))
                dangerCloseGuns.add(gun.pos());
        }

        List<BattlePlot.GunRange> ranges = new ArrayList<>();
        for (Gun gun : guns) {
            double[] elevation = elevationLimits(gun, chains.get(gun.pos()));
            BattlePlot.GunRange range = gunRange(gun.mount(), elevation[1], elevation[0],
                    traverseArc(gun, chains.get(gun.pos()), yawStatusOf.get(gun.pos())));
            if (range != null)
                ranges.add(range);
        }
        plot = plotFixes.isEmpty() && ranges.isEmpty() ? BattlePlot.EMPTY : new BattlePlot(plotFixes, ranges);
    }

    /**
     * Turns each driven bearing so the gun it serves points along its solution:
     * from the root outwards, each bearing turns by the angle about its own axis
     * that brings that gun's barrel closest to the solution, as the bearings
     * below it will have turned it. Steered by where the barrel really points, so
     * the bearings' give and the guns' own offsets do not matter. A bearing does
     * not serve a gun it would have to turn past its dial's limits for: that gun
     * is out of arc, and holds fire.
     */
    private void aimBearings(List<Gun> guns, Map<BlockPos, List<Joint>> chains, Set<BlockPos> redundant) {
        List<Joint> joints = new ArrayList<>(jointByPlate.values());
        joints.sort(Comparator.comparingInt(this::depthOf));
        Map<BlockPos, Double> turnOf = new HashMap<>();
        Map<BlockPos, BlockPos> directorThisPoll = new HashMap<>();
        Set<BlockPos> outOfArc = new HashSet<>();

        // No target: every bearing back to the way it was assembled, as mounts return to rest.
        if (!hasTarget) {
            for (Joint joint : joints)
                joint.servo().receiveFiringSolution(joint.driven() ? new FireSolution(0, 0, 0, true) : null);
            directors.clear();
            jointHistory.clear();
            outOfArcLastPoll.clear();
            passengerRange.clear();
            return;
        }

        for (Joint joint : joints) {
            BlockPos swivelPos = joint.swivel().swivelPos();
            if (!joint.driven()) {
                joint.servo().receiveFiringSolution(null);
                jointHistory.remove(swivelPos);
                continue;
            }
            Gun director = null;
            Lay lay = null;
            for (Gun candidate : candidates(joint, guns, chains, redundant, directorThisPoll)) {
                Lay candidateLay = turnFor(joint, candidate, chains.get(candidate.pos()), turnOf);
                double dial = dialOf(joint, joint.swivel().actualAngle() + candidateLay.turn(), candidateLay.sense());
                if (tilts(joint) ? joint.limits().containsElevation(dial) : joint.limits().containsTurn(dial)) {
                    director = candidate;
                    lay = candidateLay;
                    break;
                }
                // This bearing cannot bring that gun round: it is out of arc.
                outOfArc.add(candidate.pos());
                aimCache.remove(candidate.pos());
                lastPitchResults.put(candidate.reportPos(),
                        BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.OUT_OF_ARC));
            }
            if (director == null) {
                joint.servo().receiveFiringSolution(null);
                jointHistory.remove(swivelPos);
                continue;
            }
            directorThisPoll.put(swivelPos, director.pos());
            turnOf.put(swivelPos, lay.turn());
            if (tilts(joint))
                joint.servo().receiveElevationSense(lay.sense());
            double angle = Mth.wrapDegrees(joint.swivel().actualAngle() + lay.turn());
            joint.servo().receiveFiringSolution(new FireSolution(0, angle, 0, true)
                    .withRates(0, jointRate(swivelPos, angle, director.pos(), lay.sense())));
        }
        directors.clear();
        directors.putAll(directorThisPoll);
        outOfArcLastPoll.clear();
        outOfArcLastPoll.addAll(outOfArc);

        // The guns these bearings carry but are not laid for: they fire with the salvo if near enough.
        passengerRange.clear();
        for (Gun gun : guns) {
            boolean passenger = false;
            for (Joint joint : chains.get(gun.pos()))
                if (joint.driven() && directorThisPoll.containsKey(joint.swivel().swivelPos())
                        && !gun.pos().equals(directorThisPoll.get(joint.swivel().swivelPos())))
                    passenger = true;
            if (passenger && hasTarget)
                passengerRange.put(gun.pos(), launchPivot(gun.mount(), subLevelOf(gun.mount()))
                        .distance(targetX, targetY, targetZ));
        }
    }

    /** How a bearing lays a gun: the turn to make (degrees), and which way turning raises the barrel (+1 or -1). */
    private record Lay(double turn, int sense) {}

    /**
     * The turn this bearing must make to lay this gun: about its own axis, as the
     * bearings under it (towards the root) will have turned it this poll.
     */
    private Lay turnFor(Joint joint, Gun gun, List<Joint> chain, Map<BlockPos, Double> turnOf) {
        int index = chain.indexOf(joint);
        Vector3d barrel = barrelWorld(gun.mount());
        Vector3d axis = axisWorld(joint.swivel());
        List<Vector3d> axesBetween = new ArrayList<>();
        for (int k = index + 1; k < chain.size(); k++)
            axesBetween.add(axisWorld(chain.get(k).swivel()));
        for (int k = chain.size() - 1; k > index; k--) {
            double turn = turnOf.getOrDefault(chain.get(k).swivel().swivelPos(), 0.0);
            if (turn == 0) continue;
            Quaterniond rotation = new Quaterniond().fromAxisAngleRad(axesBetween.get(k - index - 1), Math.toRadians(turn));
            rotation.transform(barrel);
            rotation.transform(axis);
            for (int i = 0; i < k - index - 1; i++)
                rotation.transform(axesBetween.get(i));
        }
        FireSolution aim = aimCache.get(gun.pos());
        Vector3d wanted = frameOf(gun.mount()).transform(aimDirection(aim.pitchDeg(), aim.yawDeg()));
        Vector3d raised = new Quaterniond().fromAxisAngleRad(axis, Math.toRadians(1)).transform(new Vector3d(barrel));
        return new Lay(turnAbout(axis, barrel, wanted), pitchOf(raised) >= pitchOf(barrel) ? 1 : -1);
    }

    /**
     * The guns a bearing could serve this poll, of those it carries with a
     * solution, in order of preference: the one the bearing under it serves, so a
     * turret and its cradle serve the same gun; then the one it served last poll,
     * until that one fires or loses its solution; then the rest, with the guns
     * found out of arc last poll last.
     */
    private List<Gun> candidates(Joint joint, List<Gun> guns, Map<BlockPos, List<Joint>> chains,
                                 Set<BlockPos> redundant, Map<BlockPos, BlockPos> directorThisPoll) {
        List<Gun> candidates = new ArrayList<>();
        for (Gun gun : guns) {
            FireSolution aim = aimCache.get(gun.pos());
            if (!redundant.contains(gun.pos()) && aim != null && aim.valid() && chains.get(gun.pos()).contains(joint))
                candidates.add(gun);
        }
        Joint under = joint.swivel().hull() != null ? jointByPlate.get(joint.swivel().hull()) : null;
        BlockPos preferred = under != null ? directorThisPoll.get(under.swivel().swivelPos()) : null;
        BlockPos previous = directors.get(joint.swivel().swivelPos());
        candidates.sort(Comparator
                .comparingInt((Gun gun) -> gun.pos().equals(preferred) ? 0 : gun.pos().equals(previous) ? 1
                        : outOfArcLastPoll.contains(gun.pos()) ? 3 : 2)
                .thenComparingLong(gun -> gun.pos().asLong()));
        return candidates;
    }

    /**
     * How far this gun may depress and elevate, degrees ({lowest, highest}): its
     * mount's limits and its pitch servo's dial if it elevates itself; else what
     * the dials of the bearings tilting it allow, worked out by turning its barrel
     * to each end of their arcs. For the map table's range rings.
     */
    private double[] elevationLimits(Gun gun, List<Joint> chain) {
        ServoStatus.MountSnapshot mount = gun.mount();
        if (gun.ownPitchDrive()) {
            ServoLimits dial = gun.status().limits();
            return new double[]{Math.max(mount.maxDepression(), dial.start()), Math.min(mount.maxElevation(), dial.end())};
        }
        double lowest = -90, highest = 90;
        Vector3d barrel = barrelWorld(mount);
        for (Joint joint : chain) {
            if (!joint.driven() || !joint.limits().limitsTurn())
                continue;
            Vector3d axis = axisWorld(joint.swivel());
            if (Math.abs(axis.y) > 0.9) // turns the gun about the vertical: elevation stays
                continue;
            double[] ends = new double[2];
            float[] dials = {joint.limits().start(), joint.limits().end()};
            for (int i = 0; i < 2; i++) {
                double angle = tilts(joint) ? joint.swivel().elevationSense() * dials[i] : -dials[i];
                double turn = Mth.wrapDegrees(angle - joint.swivel().actualAngle());
                Vector3d atEnd = new Quaterniond().fromAxisAngleRad(axis, Math.toRadians(turn)).transform(new Vector3d(barrel));
                ends[i] = pitchOf(atEnd);
            }
            lowest = Math.max(lowest, Math.min(ends[0], ends[1]));
            highest = Math.min(highest, Math.max(ends[0], ends[1]));
        }
        return new double[]{lowest, highest};
    }

    /**
     * The traverse this gun may turn through, as a world bearing arc ({start, width},
     * degrees, clockwise from the start; width 360 for all round): its yaw servo's
     * dial, or that of the turret carrying it. For the map table's pie slices; taken
     * as the ship stands now, level.
     */
    private double[] traverseArc(Gun gun, List<Joint> chain, @Nullable ServoStatus yawServo) {
        ServoStatus.MountSnapshot mount = gun.mount();
        if (yawServo != null && yawServo.limits().limitsTurn()) {
            ServoLimits dial = yawServo.limits();
            Vector3d from = frameOf(mount).transform(aimDirection(0, mount.facingYaw() + dial.start()));
            return new double[]{yawOf(from), dial.length()};
        }
        for (Joint joint : chain) {
            if (!joint.driven() || tilts(joint) || !joint.limits().limitsTurn())
                continue;
            ServoLimits dial = joint.limits();
            // The barrel turns with the turret, so its bearing moves with the dial: clockwise
            // from above for a bearing facing up, the other way for one hanging down.
            double barrelYaw = yawOf(barrelWorld(mount));
            double dialNow = dialOf(joint, joint.swivel().actualAngle(), 1);
            double start = axisWorld(joint.swivel()).y >= 0
                    ? barrelYaw + dial.start() - dialNow
                    : barrelYaw - (dial.end() - dialNow);
            return new double[]{start, dial.length()};
        }
        return new double[]{0, 360};
    }

    /**
     * Whether this gun's shell would land near our own side: within its burst radius of
     * a friendly (our guns, spotters, the computer) or a hull we stand on. A shrapnel shell
     * bursts at the target and throws its balls on in a cone along its path, so for it
     * the danger is a forward cone (and a small bubble at the burst).
     */
    private boolean isDangerClose(ServoStatus.MountSnapshot mount, List<SubLevel> hulls) {
        if (!hasTarget || !mount.hasShell() || mount.shellType() == null)
            return false;
        CServer.DangerZones config = CDCConfig.server().dangerClose.zones;
        Vec3 target = new Vec3(targetX, targetY, targetZ);
        String shell = mount.shellType().getPath();

        Predicate<Vec3> endangered;
        if (shell.contains("shrapnel")) {
            Vector3d pivot = launchPivot(mount, subLevelOf(mount));
            Vec3 path = target.subtract(pivot.x, pivot.y, pivot.z).normalize();
            double cos = Math.cos(Math.toRadians(config.shrapnelConeAngle.get()));
            double length = config.shrapnelConeLength.get(), bubble = config.otherShellRadius.get();
            endangered = point -> {
                Vec3 offset = point.subtract(target);
                double distance = offset.length();
                return distance <= bubble || distance <= length && offset.normalize().dot(path) >= cos;
            };
        } else {
            double radius = shell.contains("flak_autocannon") ? config.flakRadius.get()
                    : shell.contains("he_shell") || shell.contains("mortar_shell") ? config.highExplosiveRadius.get()
                    : shell.contains("fluid_shell") ? config.fluidShellRadius.get()
                    : shell.contains("ap_shell") ? config.armorPiercingRadius.get()
                    : config.otherShellRadius.get();
            endangered = point -> point.distanceTo(target) <= radius;
        }

        for (Vec3 friendly : friendlies)
            if (endangered.test(friendly))
                return true;
        for (SubLevel hull : hulls) {
            // The hull's nearest point to the burst.
            var bounds = hull.boundingBox();
            Vec3 nearest = new Vec3(Mth.clamp(target.x, bounds.minX(), bounds.maxX()),
                    Mth.clamp(target.y, bounds.minY(), bounds.maxY()), Mth.clamp(target.z, bounds.minZ(), bounds.maxZ()));
            if (endangered.test(nearest))
                return true;
        }
        return false;
    }

    /** Whether some gun's next shell would land near our own side (last poll). */
    public boolean isDangerClose() {
        return !dangerCloseGuns.isEmpty();
    }

    /** Whether this bearing lies on its side, tilting a cradle: its dial reads elevation. */
    private static boolean tilts(Joint joint) {
        return joint.swivel().facing().getAxis() != Direction.Axis.Y;
    }

    /**
     * A bearing's dial angle at this swivel angle: a cradle's reads elevation (up
     * positive, by {@code sense}), a turret's clockwise seen from its face.
     */
    private static double dialOf(Joint joint, double angle, int sense) {
        return Mth.wrapDegrees(tilts(joint) ? sense * angle : -angle);
    }

    /** The bearings carrying this gun, from the one under it down to the root. */
    private List<Joint> chainOf(ServoStatus.MountSnapshot mount) {
        List<Joint> chain = new ArrayList<>();
        SubLevel subLevel = subLevelOf(mount);
        UUID carrier = subLevel != null ? subLevel.getUniqueId() : null;
        while (carrier != null && chain.size() < MAX_CHAIN) {
            Joint joint = jointByPlate.get(carrier);
            if (joint == null || chain.contains(joint))
                break;
            chain.add(joint);
            carrier = joint.swivel().hull();
        }
        return chain;
    }

    /** How many bearings stand under this one. */
    private int depthOf(Joint joint) {
        int depth = 0;
        UUID hull = joint.swivel().hull();
        while (hull != null && depth < MAX_CHAIN) {
            Joint under = jointByPlate.get(hull);
            if (under == null)
                break;
            depth++;
            hull = under.swivel().hull();
        }
        return depth;
    }

    /**
     * Whether more drives aim this gun than it has ways to turn: a barrel is pointed
     * by two rotations, so a third drive, or two about the same axis, would only
     * fight. Its own mount's axes count, and every driven bearing carrying it.
     */
    private boolean hasRedundantDrives(ServoStatus.MountSnapshot mount, boolean ownPitch, boolean ownYaw, List<Joint> chain) {
        List<Vector3d> axes = new ArrayList<>();
        Quaterniondc frame = frameOf(mount);
        if (ownYaw)
            axes.add(frame.transform(new Vector3d(0, 1, 0)));
        if (ownPitch) {
            double yaw = Math.toRadians(mount.mountYaw());
            axes.add(frame.transform(new Vector3d(Math.cos(yaw), 0, Math.sin(yaw))));
        }
        for (Joint joint : chain)
            if (joint.driven())
                axes.add(axisWorld(joint.swivel()));
        if (axes.size() > 2)
            return true;
        return axes.size() == 2 && Math.abs(axes.get(0).dot(axes.get(1))) > PARALLEL_AXES_COS;
    }

    /** A bearing's axis in the world: the way it faces, as its hull now stands. */
    private Vector3d axisWorld(ServoStatus.SwivelSnapshot swivel) {
        Direction facing = swivel.facing();
        Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        SubLevel hull = level != null ? Sable.HELPER.getContaining(level, swivel.swivelPos()) : null;
        return hull != null ? hull.logicalPose().orientation().transform(axis) : axis;
    }

    /** Where this gun's barrel points now, in the world. */
    private Vector3d barrelWorld(ServoStatus.MountSnapshot mount) {
        Vector3d barrel = mount.barrel() != null
                ? new Vector3d(mount.barrel().x, mount.barrel().y, mount.barrel().z)
                : aimDirection(mount.currentAngle(), mount.mountYaw());
        return frameOf(mount).transform(barrel);
    }

    /** The frame a gun stands in: its sub-level's orientation, or the world's. */
    private Quaterniondc frameOf(ServoStatus.MountSnapshot mount) {
        SubLevel subLevel = subLevelOf(mount);
        return subLevel != null ? subLevel.logicalPose().orientation() : WORLD_FRAME;
    }

    /**
     * The turn about this axis (degrees, right-handed) that brings one direction
     * closest to another; 0 if either lies along the axis, which turning cannot change.
     */
    private static double turnAbout(Vector3dc axis, Vector3dc from, Vector3dc to) {
        Vector3d f = new Vector3d(from).fma(-axis.dot(from), axis);
        Vector3d t = new Vector3d(to).fma(-axis.dot(to), axis);
        if (f.lengthSquared() < ALONG_AXIS_SQ || t.lengthSquared() < ALONG_AXIS_SQ)
            return 0;
        return Math.toDegrees(Math.atan2(axis.dot(f.cross(t, new Vector3d())), f.dot(t)));
    }

    /**
     * Whether this gun's attitude depends on a ship's: whatever its chain of bearings
     * stands on at the bottom is a ship. Guns ashore, and on turrets ashore, need no
     * ship sensors.
     */
    private boolean onShip(ServoStatus.MountSnapshot mount) {
        List<Joint> chain = chainOf(mount);
        if (chain.isEmpty())
            return subLevelOf(mount) != null;
        return chain.get(chain.size() - 1).swivel().hull() != null;
    }

    /**
     * How far a mount can reach with its current shell and charge, for the
     * map table: down to the target's height, or without a target to the
     * ground (or sea level, if unloaded) under the mount. Uses the same top
     * pitch as the ballistic solver (45°, or less if the mount cannot elevate that far),
     * so it matches where solutions turn into "out of range". The ship's own
     * motion is left out. Its aim line is where the barrel really points.
     */
    @Nullable
    private BattlePlot.GunRange gunRange(ServoStatus.MountSnapshot mount, double maxElevation, double maxDepression,
                                         double[] arc) {
        if (level == null || !mount.hasShell())
            return null;
        double charge = mount.totalChargePower() + mount.addedChargePower();
        if (mount.minimumChargePower() > charge)
            return null;

        SubLevel mountSubLevel = subLevelOf(mount);
        // From the cannon's pivot: the muzzle's few blocks matter little at the edge of its reach.
        Vector3d muzzleWorld = launchPivot(mount, mountSubLevel);

        double referenceY;
        if (hasTarget) {
            referenceY = targetY;
        } else {
            int x = Mth.floor(muzzleWorld.x), z = Mth.floor(muzzleWorld.z);
            referenceY = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
                    ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                    : level.getSeaLevel();
        }

        int flightTicks = mount.maxFlightTicks() > 0 ? mount.maxFlightTicks() : Integer.MAX_VALUE;
        double radius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                Math.min(45, maxElevation), referenceY - muzzleWorld.y, flightTicks);
        if (radius <= 0)
            return null;

        // The barrel's bearing in the world, flattened onto the map.
        double aimX = 0, aimZ = 0;
        Vector3d barrel = barrelWorld(mount);
        double horizontal = Math.sqrt(barrel.x * barrel.x + barrel.z * barrel.z);
        if (horizontal > 1e-6) {
            aimX = barrel.x / horizontal;
            aimZ = barrel.z / horizontal;
        }
        // The dead zone around the gun, for a level ship (as a plotting officer
        // would draw it from the range table). Plunging fire: the steepest shot
        // comes down this far out. Direct fire, only with the target below the
        // gun: the lowest the barrel depresses still lands this far out.
        double minRadius = 0;
        double relativeY = referenceY - muzzleWorld.y;
        if (plungingFire && !mount.autocannon()) {
            if (maxElevation > 45)
                minRadius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                        BallisticSolver.steepestPlungingPitch(maxElevation), relativeY);
        } else if (relativeY < 0) {
            minRadius = BallisticSolver.maxRange(charge, mount.gravity(), mount.drag(), mount.isQuadraticDrag(),
                    maxDepression, relativeY);
        }
        return new BattlePlot.GunRange(muzzleWorld.x, muzzleWorld.z, radius, aimX, aimZ, minRadius, arc[0], arc[1]);
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
                double rawYaw = Mth.wrapDegrees(solution.yawDeg() - prev.yawDeg()) / dt;
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

    /**
     * How fast a driven swivel bearing's commanded angle is changing (degrees per tick),
     * estimated from consecutive polls as {@link #withRates} does for mounts, so its servo
     * keeps pace with a moving solution instead of trailing it. A new director gun or
     * elevation sense starts the estimate afresh: the angle jumps, which is not motion.
     */
    private double jointRate(BlockPos swivelPos, double angleDeg, BlockPos director, int sense) {
        // Rates are the Differentiator's job; without it servos only chase the angles.
        if (level == null || !has(ComputerModule.DIFFERENTIATOR)) {
            jointHistory.remove(swivelPos);
            return 0;
        }
        long now = level.getGameTime();
        JointSample prev = jointHistory.get(swivelPos);
        if (prev != null && prev.tick() == now)
            return prev.rate();
        double rate = 0;
        if (prev != null && prev.director().equals(director) && prev.sense() == sense) {
            long dt = now - prev.tick();
            if (dt > 0 && dt <= MAX_RATE_SAMPLE_GAP) {
                double raw = Mth.wrapDegrees(angleDeg - prev.angleDeg()) / dt;
                // A jump (rest to tracking, a new target) is not motion.
                if (Math.abs(raw) <= MAX_RATE_DEG_PER_TICK)
                    rate = RATE_SMOOTHING * raw + (1 - RATE_SMOOTHING) * prev.rate();
            }
        }
        jointHistory.put(swivelPos, new JointSample(angleDeg, now, rate, director, sense));
        return rate;
    }

    private FireSolution solveYaw(ServoStatus status) {
        ServoStatus.MountSnapshot mount = status.mounts().get(0);

        if (!hasTarget) return restSolution(mount);

        FireSolution cached = aimCache.get(mount.mountPos());
        if (cached != null) {
            return new FireSolution(cached.pitchDeg(), cached.yawDeg(), 0, true);
        }

        // No ballistic solution for this mount (no pitch servo, no shell, ...).
        // Keep tracking the target along the line of sight.
        SubLevel mountSubLevel = subLevelOf(mount);
        if (onShip(mount) && !hasOrientationSensors()) return null;

        Vector3d toTargetWorld = new Vector3d(targetX, targetY, targetZ).sub(launchPivot(mount, mountSubLevel));
        if (toTargetWorld.lengthSquared() < 1e-8) return null;

        Quaterniondc shipToWorld = mountSubLevel != null ? mountSubLevel.logicalPose().orientation() : WORLD_FRAME;
        Vector3d shipDir = shipToWorld.transformInverse(toTargetWorld.normalize(), new Vector3d());
        return new FireSolution(pitchOf(shipDir), yawOf(shipDir), 0, true);
    }

    /**
     * Solves a gun: the direction its barrel must point, in the frame it stands in,
     * and its fuze. Its line on the console is kept under {@code servoPos}.
     *
     * @param pitchServo the mount's own pitch servo, if it elevates the barrel itself (so its
     *                   elevation limits apply); null if bearings carrying it do
     * @param yawServo   the mount's own yaw servo, if any (its traverse limits apply)
     */
    private FireSolution solveGun(BlockPos servoPos, ServoStatus.MountSnapshot mount,
                                  @Nullable ServoStatus pitchServo, @Nullable ServoStatus yawServo) {
        boolean ownPitchDrive = pitchServo != null;
        SubLevel mountSubLevel = subLevelOf(mount);

        if (!hasTarget) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_TARGET));
            return restSolution(mount);
        }

        // On a ship, aiming in its frame takes knowing its attitude. A gun ashore (a
        // fort, a battery) needs none: its frame is the world, which neither tilts nor turns.
        if (onShip(mount) && !hasOrientationSensors()) {
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

        Quaterniondc shipToWorld = mountSubLevel != null ? mountSubLevel.logicalPose().orientation() : WORLD_FRAME;
        Vector3d pivotWorld = launchPivot(mount, mountSubLevel);
        Vector3d targetWorld = new Vector3d(targetX, targetY, targetZ);
        // The shell leaves from the muzzle, which moves as the barrel turns: start
        // with the barrel pointing straight at the target, then from where it aims.
        Vector3d muzzleWorld = muzzleAt(pivotWorld, new Vector3d(targetWorld).sub(pivotWorld), mount);

        // A map or navigation table gives the target as an absolute position: comparing
        // heights with it takes knowing your own altitude, which a ship only gets from an
        // Altitude Sensor. A rangefinder measures the target relative to itself, and a gun
        // ashore stands at a surveyed height, so neither needs one.
        boolean absoluteTarget = targetSource == TargetSource.MAP_TABLE || targetSource == TargetSource.NAV_TABLE;
        if (onShip(mount) && absoluteTarget && altitudeCount == 0
                && Math.abs(targetWorld.y - pivotWorld.y) > CDCConfig.server().fireControl.altitudeGate.getF()) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_ALTITUDE));
            return null;
        }
        Vector3d targetVel = new Vector3d(targetVelX, targetVelY, targetVelZ);

        double muzzleVelocity = charge;
        double gravity = mount.gravity();
        double drag = mount.drag();
        boolean isQuadraticDrag = mount.isQuadraticDrag();

        Vector3d inheritedVelocity = inheritedLaunchVelocity(mountSubLevel, mount.mountPos());

        // The mount's elevation limits apply in ship frame, so let the ballistic solver
        // search the whole world-frame arc and check the limits afterwards.
        BallisticSolver.Result result = BallisticSolver.solve(
                muzzleWorld, targetWorld, targetVel, inheritedVelocity,
                muzzleVelocity, gravity, drag, isQuadraticDrag,
                90.0, -90.0, plungingFire && !mount.autocannon());
        for (int pass = 0; pass < MUZZLE_REFINEMENT_PASSES && result.valid() && mount.muzzleDistance() > 0; pass++) {
            muzzleWorld = muzzleAt(pivotWorld, aimDirection(result.pitchDeg(), result.yawDeg()), mount);
            result = BallisticSolver.solve(
                    muzzleWorld, targetWorld, targetVel, inheritedVelocity,
                    muzzleVelocity, gravity, drag, isQuadraticDrag,
                    90.0, -90.0, plungingFire && !mount.autocannon());
        }

        if (!result.valid()) {
            lastPitchResults.put(servoPos, result);
            return null;
        }
        // An autocannon's round expires after its lifetime: out of reach if it would not get there.
        if (mount.maxFlightTicks() > 0 && result.timeOfFlightTicks() > mount.maxFlightTicks()) {
            lastPitchResults.put(servoPos, BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.ROUND_EXPIRES));
            return null;
        }

        // The world-frame aim direction from the solve's pitch and yaw.
        Vector3d worldDir = aimDirection(result.pitchDeg(), result.yawDeg());

        // Rotate into ship frame. This is the inverse of the rotation Sable
        // applies to the shell's velocity when it kicks it out of the plot.
        Vector3d shipDir = shipToWorld.transformInverse(worldDir, new Vector3d());
        double shipPitchDeg = pitchOf(shipDir);
        double shipYawDeg = yawOf(shipDir);
        // Jostled cabinets put every aim off by their bias.
        shipPitchDeg += bankBiasPitch;
        shipYawDeg += bankBiasYaw;

        boolean outOfArc = ownPitchDrive && (shipPitchDeg > mount.maxElevation() || shipPitchDeg < mount.maxDepression()
                || !pitchServo.limits().containsElevation(shipPitchDeg));
        // The traverse set on its yaw servo's dial, counted from the way it was assembled.
        if (yawServo != null && !yawServo.limits().containsTurn(Mth.wrapDegrees(shipYawDeg - mount.facingYaw())))
            outOfArc = true;
        if (outOfArc) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.OUT_OF_ARC));
            return null;
        }

        lastPitchResults.put(servoPos, result);

        // Setting fuzes takes a Fuze Computer: without one, fuzes stay as loaded (0 and -1).
        boolean setsFuzes = has(ComputerModule.FUZE_COMPUTER);
        FireSolution solution = new FireSolution(shipPitchDeg, shipYawDeg,
                setsFuzes ? correctedFuze(result.fuzeTicks(), mount.autocannon()) : 0,
                setsFuzes ? impactDelay(mount.autocannon()) : -1, true);
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
     * The computer only compensates along ship axes it has velocity sensors for.
     */
    private Vector3d inheritedLaunchVelocity(@Nullable SubLevel mountSubLevel, BlockPos mountPos) {
        // Compensating for own-ship speed takes an Integrator (and velocity sensors);
        // a gun ashore does not move.
        if (level == null || mountSubLevel == null || velocityAxesCovered() == 0 || !has(ComputerModule.INTEGRATOR)
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
     * The sublevel the mount itself sits on. Usually the computer's own, but a
     * mount on a separately simulated body must use that body's pose.
     */
    @Nullable
    private SubLevel subLevelOf(ServoStatus.MountSnapshot mount) {
        if (level == null) return null;
        return Sable.HELPER.getContaining(level, mount.mountPos());
    }

    /** A world-frame barrel direction from a solve's pitch and yaw, degrees. */
    private static Vector3d aimDirection(double pitchDeg, double yawDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg);
        return new Vector3d(-Math.sin(y) * Math.cos(p), Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    /**
     * The cannon's rotation centre in the world, where its barrel turns about;
     * the mount block's centre if the cannon has not been measured (unassembled).
     */
    private static Vector3d launchPivot(ServoStatus.MountSnapshot mount, @Nullable SubLevel mountSubLevel) {
        Vector3d local = mount.pivot() != null
                ? new Vector3d(mount.pivot().x, mount.pivot().y, mount.pivot().z)
                : blockCenter(mount.mountPos());
        return mountSubLevel != null ? mountSubLevel.logicalPose().transformPosition(local, new Vector3d()) : local;
    }

    /** Where the shell appears with the barrel pointing this way (world frame): along it from the pivot. */
    private static Vector3d muzzleAt(Vector3d pivotWorld, Vector3d barrelDirection, ServoStatus.MountSnapshot mount) {
        if (barrelDirection.lengthSquared() < 1e-12)
            return new Vector3d(pivotWorld);
        return new Vector3d(barrelDirection).normalize().mul(mount.muzzleDistance()).add(pivotWorld);
    }

    private static Vector3d blockCenter(BlockPos pos) {
        return new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static double pitchOf(Vector3d dir) {
        return Math.toDegrees(Math.asin(Math.clamp(dir.y, -1.0, 1.0)));
    }

    private static double yawOf(Vector3d dir) {
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        return yaw < 0 ? yaw + 360 : yaw;
    }

    // --- Accessors for other blocks (fire control panel, map table, etc.) ---

    public boolean hasTarget() { return hasTarget; }

    public boolean isPowered() { return isPowered; }

    /** The network interface driving this computer, if one is (server). */
    @Nullable
    public BlockPos getLinkedInterface() {
        return isLinkFresh() ? linkedInterface : null;
    }

    /** Whether the computer is leading a tracked, moving target (a velocity estimate exists). */
    public boolean isTrackingTarget() {
        return isPowered && !bankConflict && hasTarget && hasTargetVelocity;
    }

    /**
     * How precisely the guns' target is known, blocks: the rangefinder fix's
     * uncertainty, or how finely a map or navigation table placed it; NaN
     * without a target (client).
     */
    public double getFixSigma() {
        return isPowered && !bankConflict && hasTarget ? targetSigma : Double.NaN;
    }

    /** The tracked target's speed, blocks per tick; 0 while not tracking. */
    public double getTargetSpeed() {
        return isTrackingTarget() ? Math.sqrt(targetVelX * targetVelX + targetVelY * targetVelY + targetVelZ * targetVelZ) : 0;
    }

    // --- NBT ---
    // The state is re-derived from the network and pose each poll, so it is
    // only synced for the client tooltip.

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        this.isPowered = compound.getBoolean("IsPowered");
        this.bankConflict = compound.getBoolean("BankConflict");
        this.plungingFire = compound.getBoolean("PlungingFire");
        if (!clientPacket) {
            autocannonsFiring.clear();
            for (long pos : compound.getLongArray("AutocannonsFiring"))
                autocannonsFiring.add(BlockPos.of(pos));
        }
        this.fuzeOffsetTicks = compound.getInt("FuzeOffset");
        if (!clientPacket)
            this.fuzeOffsetSetTick = compound.contains("FuzeOffsetSet") ? compound.getLong("FuzeOffsetSet") : Long.MIN_VALUE;
        this.autocannonFuzeOffsetTicks = compound.getInt("AutocannonFuzeOffset");
        if (!clientPacket)
            this.autocannonFuzeOffsetSetTick = compound.contains("AutocannonFuzeOffsetSet")
                    ? compound.getLong("AutocannonFuzeOffsetSet") : Long.MIN_VALUE;
        this.panelBearingDeg = compound.getFloat("PanelBearing");
        this.panelRange = compound.getFloat("PanelRange");
        this.panelRangeFraction = compound.getFloat("PanelRangeFraction");
        this.panelElevationDeg = compound.getFloat("PanelElevation");
        this.panelFuzeTicks = compound.getInt("PanelFuze");
        this.panelNetworkRpm = compound.getFloat("PanelNetworkRpm");
        this.panelOwnSpeed = compound.getFloat("PanelOwnSpeed");
        this.panelHoldLeftTicks = compound.getLong("PanelHoldLeft");
        this.panelHoldLifetimeTicks = Math.max(1, compound.getLong("PanelHoldLifetime"));
        this.panelHoldStampTick = compound.getLong("PanelHoldStamp");
        this.panelHoldAges = compound.getBoolean("PanelHoldAges");
        this.modules.clear();
        int moduleBits = compound.getInt("Modules");
        for (ComputerModule module : ComputerModule.values())
            if ((moduleBits & 1 << module.ordinal()) != 0)
                this.modules.add(module);

        this.gimbalCount = compound.getInt("GimbalCount");
        this.navHeadingCount = compound.getInt("NavHeadingCount");
        this.altitudeCount = compound.getInt("AltitudeCount");
        this.velocityAxisCounts[0] = compound.getInt("VelocityCountX");
        this.velocityAxisCounts[1] = compound.getInt("VelocityCountY");
        this.velocityAxisCounts[2] = compound.getInt("VelocityCountZ");

        this.directsShipGuns = compound.getBoolean("DirectsShipGuns");

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
        this.mergedRangefinders = compound.getInt("MergedRangefinders");

        if (clientPacket) {
            this.lastPitchResults.clear();
            this.autocannonReports.clear();
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
                if (t.getBoolean("Autocannon"))
                    this.autocannonReports.add(pos);
            }
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("IsPowered", isPowered);
        compound.putBoolean("BankConflict", bankConflict);
        compound.putBoolean("PlungingFire", plungingFire);
        if (!clientPacket)
            compound.putLongArray("AutocannonsFiring", autocannonsFiring.stream().mapToLong(BlockPos::asLong).toArray());
        compound.putInt("FuzeOffset", fuzeOffsetTicks);
        if (!clientPacket)
            compound.putLong("FuzeOffsetSet", fuzeOffsetSetTick);
        compound.putInt("AutocannonFuzeOffset", autocannonFuzeOffsetTicks);
        if (!clientPacket)
            compound.putLong("AutocannonFuzeOffsetSet", autocannonFuzeOffsetSetTick);
        compound.putFloat("PanelBearing", panelBearingDeg);
        compound.putFloat("PanelRange", panelRange);
        compound.putFloat("PanelRangeFraction", panelRangeFraction);
        compound.putFloat("PanelElevation", panelElevationDeg);
        compound.putInt("PanelFuze", panelFuzeTicks);
        compound.putFloat("PanelNetworkRpm", panelNetworkRpm);
        compound.putFloat("PanelOwnSpeed", panelOwnSpeed);
        compound.putLong("PanelHoldLeft", panelHoldLeftTicks);
        compound.putLong("PanelHoldLifetime", panelHoldLifetimeTicks);
        compound.putLong("PanelHoldStamp", panelHoldStampTick);
        compound.putBoolean("PanelHoldAges", panelHoldAges);
        int moduleBits = 0;
        for (ComputerModule module : modules)
            moduleBits |= 1 << module.ordinal();
        compound.putInt("Modules", moduleBits);

        compound.putInt("GimbalCount", gimbalCount);
        compound.putInt("NavHeadingCount", navHeadingCount);
        compound.putInt("AltitudeCount", altitudeCount);
        compound.putInt("VelocityCountX", velocityAxisCounts[0]);
        compound.putInt("VelocityCountY", velocityAxisCounts[1]);
        compound.putInt("VelocityCountZ", velocityAxisCounts[2]);

        compound.putBoolean("DirectsShipGuns", directsShipGuns);

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
            t.putBoolean("Autocannon", autocannonReports.contains(e.getKey()));
            results.add(t);
        }
        compound.put("PitchResults", results);
    }

    // --- TOOLTIP ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.ballistic_computer.title"))
                .forGoggles(tooltip);
        health.addToGoggleTooltip(tooltip);

        if (!isPowered) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.not_connected"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        if (bankConflict) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.bank_conflict"))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip);
            return true;
        }

        // --- Modules: installed ones, then what each missing one costs ---
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.ballistic_computer.modules"))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        for (ComputerModule module : ComputerModule.values()) {
            boolean installed = has(module);
            CDCLang.builder()
                    .add(Component.literal(installed ? "  ✔ " : "  ✖ "))
                    .add(Component.translatable("block.createdangerclose." + module.id()))
                    .add(installed ? Component.empty() : Component.literal(": ").append(Component.translatable(
                            "createdangerclose.ballistic_computer.module_missing." + module.id())))
                    .style(installed ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }

        // --- Missing sensors: only guns on a ship need them (the ship's own readings
        // are on the Navigation Console) ---
        if (directsShipGuns && !hasOrientationSensors()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.missing_orientation"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }
        if (directsShipGuns && !hasFullVelocity()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.missing_velocity"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }
        if (directsShipGuns && altitudeCount == 0) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.missing_altitude"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        // Target and firing solutions are shown on the Fire Mission Console.
        if (!hasTarget) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.missing_target"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        return true;
    }

    /**
     * The fire mission: target, its source and speed, the trajectory mode and
     * each gun's solution. Shown by Fire Mission Consoles, which read it from
     * this computer (client side, from its synced data).
     */
    public void addFireMissionTooltip(List<Component> tooltip, boolean autocannons) {
        // Autocannons always fire direct: their console shows its own fire mode instead.
        if (!autocannons)
            CDCLang.builder()
                    .add(Component.translatable(plungingFire
                            ? "createdangerclose.fire_mission_console.mode.plunging"
                            : "createdangerclose.fire_mission_console.mode.direct"))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip);

        if (hasTarget) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.target"))
                    .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetX)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetY)).withStyle(ChatFormatting.GOLD))
                    .add(Component.literal(", ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(String.format("%.0f", targetZ)).withStyle(ChatFormatting.GOLD))
                    .forGoggles(tooltip);

            Component source;
            if (targetSource == TargetSource.MAP_TABLE) {
                source = Component.translatable("createdangerclose.ballistic_computer.target_source.map_table");
            } else if (targetSource != TargetSource.RANGEFINDER) {
                source = Component.translatable("createdangerclose.ballistic_computer.target_source.nav_table");
            } else if (mergedRangefinders > 1) {
                source = Component.translatable("createdangerclose.ballistic_computer.target_source.rangefinders_merged",
                        mergedRangefinders, String.format("%.1f", targetAgeTicks / 20.0), String.format("%.0f", targetSigma));
            } else {
                source = Component.translatable("createdangerclose.ballistic_computer.target_source.rangefinder",
                        String.format("%.1f", targetAgeTicks / 20.0), String.format("%.0f", targetSigma));
            }
            CDCLang.builder()
                    .add(source)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            if (hasTargetVelocity) {
                double speed = Math.sqrt(targetVelX * targetVelX + targetVelY * targetVelY + targetVelZ * targetVelZ) * 20;
                CDCLang.builder()
                        .add(Component.translatable("createdangerclose.ballistic_computer.target_speed"))
                        .add(Component.literal(": ").withStyle(ChatFormatting.RESET))
                        .add(CDCLang.numberWithUnit(speed, "createdangerclose.unit.velocity").withStyle(ChatFormatting.GOLD))
                        .forGoggles(tooltip);
            }
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.missing_target"))
                    .style(ChatFormatting.DARK_RED)
                    .forGoggles(tooltip);
        }

        if (!lastPitchResults.isEmpty()) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.ballistic_computer.solutions"))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);

            for (Map.Entry<BlockPos, BallisticSolver.Result> entry : lastPitchResults.entrySet()) {
                // Only the guns this console orders.
                if (autocannonReports.contains(entry.getKey()) != autocannons)
                    continue;
                BallisticSolver.Result r = entry.getValue();
                String reasonKey = "createdangerclose.ballistic_computer.reason."
                        + r.reason().name().toLowerCase();

                if (r.valid()) {
                    CDCLang.builder()
                            .add(Component.literal("  "))
                            .add(Component.translatable(
                                    "createdangerclose.ballistic_computer.solution_ok",
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
                                    "createdangerclose.ballistic_computer.solution_failed",
                                    Component.translatable(reasonKey)))
                            .style(ChatFormatting.RED)
                            .forGoggles(tooltip);
                }
            }
        }
    }
}
