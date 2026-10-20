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
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonFiring;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.ballistics.BallisticSolver;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ComputerServo;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.FireSolution;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoLimits;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.createmod.catnip.animation.LerpedFloat;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import net.minecraft.world.phys.Vec3;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity.FireControlStatus;
import io.github.daniel366cobra.createdangerclose.registries.CDCTriggers;
import io.github.daniel366cobra.createdangerclose.foundation.advancement.DelayedTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
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
import net.minecraft.network.chat.MutableComponent;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.PITCH;
import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.YAW;
import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.FIXED_GUN;
import static io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis.SWIVEL;

/**
 * Core of the fire control computer. A facing Battle Network Interface drives each poll
 * ({@link #pollFrom}); when it stops, the computer goes idle.
 *
 * <p>Sensors only gate capabilities. Orientation, position and velocity come from the Sable
 * pose; the only sensor value read is a navigation table's target.
 */
public class BallisticComputerBlockEntity extends SmartBlockEntity
        implements IHaveGoggleInformation {

    // A powered interface polls at least every 20 ticks.
    private static final int LINK_TIMEOUT_TICKS = 40;

    // The network interface driving this computer (server only), and when it last polled.
    @Nullable private BlockPos linkedInterface;
    private long lastLinkTick;

    // Without an Integrator: two polls at the slowest rate, so it does not flicker.
    private static final int UNHELD_SOLUTION_LIFETIME_TICKS = 40;

    // --- Module gates: what is in the bank around this computer ---
    private final Set<ComputerModule> modules = EnumSet.noneOf(ComputerModule.class);
    // Another Ballistic Computer touches this one's modules: neither works.
    private boolean bankConflict = false;

    // Set from Fire Mission Consoles; the last flip wins.
    private boolean plungingFire = false;
    // Added to timed fuzes, ticks (negative bursts early). The most recently set console wins.
    private int fuzeOffsetTicks = 0;
    private long fuzeOffsetSetTick = Long.MIN_VALUE;
    // Autocannons are corrected apart, from the Autocannon Control Console.
    private int autocannonFuzeOffsetTicks = 0;
    private long autocannonFuzeOffsetSetTick = Long.MIN_VALUE;
    // A fuze of 0 would go off in the barrel.
    private static final int MIN_FUZE_TICKS = 1;
    // Each pass from the last aim's muzzle shrinks the launch point error; two settle it.
    private static final int MUZZLE_REFINEMENT_PASSES = 2;
    private static final Quaterniondc WORLD_FRAME = new Quaterniond();

    // Each gun's barrel as last read, for the firing interlock.
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
    // Bias from jostled cabinets and interfaces, degrees, added to every aim.
    private EquipmentHealth health;
    private float bankBiasYaw, bankBiasPitch;
    // Per gun riding bearings laid for another gun: its range to the target, blocks.
    private final Map<BlockPos, Double> passengerRange = new HashMap<>();

    /**
     * What a salvo order did: guns fired, and guns held back (no solution, or not yet on target).
     *
     * @param heldNames the named guns among those held, as "Turret C ×2, Bow-2" (empty if none)
     */
    public record SalvoResult(int fired, int held, boolean awaitingConfirmation, String heldNames) {
        public SalvoResult(int fired, int held) {
            this(fired, held, false, "");
        }

        public SalvoResult(int fired, int held, boolean awaitingConfirmation) {
            this(fired, held, awaitingConfirmation, "");
        }
    }

    // Autocannons set firing; with no console order for a moment they cease fire.
    private final Set<BlockPos> autocannonsFiring = new HashSet<>();
    private long autocannonOrderTick;
    private static final int AUTOCANNON_ORDER_TIMEOUT_TICKS = 2;

    // Danger close: guns that would land near our own side, where our side stands, and when a warned salvo was armed.
    private final Map<BlockPos, Danger> dangerCloseGuns = new HashMap<>();
    // Our own positions, and the players carrying a plotted map.
    private final List<Vec3> friendlies = new ArrayList<>();
    private final List<Vec3> spotters = new ArrayList<>();
    // Each gun's time of flight to the target (last poll), to credit what a salvo hits as it lands.
    private final Map<BlockPos, Integer> flightTicks = new HashMap<>();
    // From map tables linked to the network.
    private final List<MapTableBlockEntity.SafeZone> safeZones = new ArrayList<>();
    private long dangerArmedTick = -1;

    // --- Sensor gates: what is present on the battle network ---
    private int gimbalCount = 0;
    private int navHeadingCount = 0;
    private int altitudeCount = 0;
    // Per ship axis (X, Y, Z); extra sensors on a covered axis add nothing.
    private final int[] velocityAxisCounts = new int[3];

    // Only guns on a ship need attitude and velocity sensors.
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
    private long targetAgeTicks = 0;
    // For telling a live target from a held one.
    private long targetSampleTick;
    private double targetSigma = 0;
    private int mergedRangefinders = 0;

    // Keeps control until its solution goes stale, so a second rangefinder cannot yank the guns away.
    @Nullable private BlockPos directingRangefinder;
    // Solutions within this many combined sigmas of the directing one are merged...
    private static final double MERGE_SIGMAS = 3;
    // A block position: uniform within half a block.
    private static final double NAV_TARGET_SIGMA = 1 / Math.sqrt(12);
    // ...with at least this much tolerance, blocks.
    private static final double MIN_MERGE_DISTANCE = 4;

    // For map tables: the last poll's rangefinder fixes and gun ranges.
    private BattlePlot plot = BattlePlot.EMPTY;
    private List<BattlePlot.RangefinderFix> plotFixes = List.of();

    private final Map<BlockPos, BallisticSolver.Result> lastPitchResults = new HashMap<>();
    // Each console lists only its own guns.
    private final Set<BlockPos> autocannonReports = new HashSet<>();
    // Report (servo) to the mount it lays, for guns only.
    private final Map<BlockPos, BlockPos> mountOfReport = new HashMap<>();
    // The name each report's gun shows (set on a servo laying it); synced for the console tooltips.
    private final Map<BlockPos, String> reportNames = new HashMap<>();
    // The same by mount, for the salvo message (server).
    private final Map<BlockPos, String> mountNames = new HashMap<>();

    // Shared by a mount's pitch and yaw servos.
    private final Map<BlockPos, FireSolution> aimCache = new HashMap<>();

    // Last commanded angles, for feed-forward rates.
    private record AimSample(double pitchDeg, double yawDeg, long tick, double pitchRate, double yawRate) {}
    private final Map<BlockPos, AimSample> aimHistory = new HashMap<>();
    // Poll interval is at most 20 ticks.
    private static final long MAX_RATE_SAMPLE_GAP = 40;
    // Faster is a jump between solutions, not tracking.
    private static final double MAX_RATE_DEG_PER_TICK = 2.0;
    private static final double RATE_SMOOTHING = 0.5;
    // The same for driven swivel bearings, per director gun and elevation sense (a change of either jumps the angle).
    private record JointSample(double angleDeg, long tick, double rate, BlockPos director, int sense) {}
    private final Map<BlockPos, JointSample> jointHistory = new HashMap<>();
    // Last poll's target, for telling a new target from a moving one.
    private TargetSource lastTargetSource = TargetSource.NONE;
    private double lastTargetX;
    private double lastTargetY;
    private double lastTargetZ;
    private long lastTargetTick;
    private static final double TARGET_MOVED_EPSILON = 0.01;

    private boolean isPowered = false;

    // --- Front panel ---
    // Synced readings (zero without a target): bearing clockwise from the front, horizontal range,
    // range as a fraction of the first gun's reach, and its elevation.
    private float panelBearingDeg;
    private float panelRange;
    private float panelRangeFraction;
    private float panelElevationDeg;
    // Time of flight plus the fuze correction.
    private int panelFuzeTicks;
    // For the modules. The hold is counted down on the client between syncs.
    private float panelNetworkRpm;
    private float panelOwnSpeed;
    private long panelHoldLeftTicks;
    private long panelHoldLifetimeTicks = 1;
    private long panelHoldStampTick;
    private boolean panelHoldAges;
    // Client only.
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

    /** Looked up on every use: Create replaces the network instance when it loses and regains its source. */
    @Nullable
    private KineticNetwork battleNetwork() {
        if (level == null || linkedInterface == null
                || !(level.getBlockEntity(linkedInterface) instanceof NetworkInterfaceBlockEntity networkInterface))
            return null;
        return Create.TORQUE_PROPAGATOR.getOrCreateNetworkFor(networkInterface);
    }

    /**
     * Called by a powered facing interface each time it polls. A second interface is turned away
     * while the first still polls.
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
            forgetRatesOnNewTarget();
            pollAndPushServos();
        }
        updatePanel();
        notifyUpdate();
        return true;
    }

    /** Rescanned each poll: a handful of blocks, no bookkeeping needed. */
    private void scanBank() {
        ComputerBank.Scan scan = ComputerBank.scan(level, worldPosition);
        modules.clear();
        modules.addAll(scan.modules());
        bankConflict = scan.cores().size() > 1;
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
     * Fires every gun with a solution whose barrel has settled on it (or, riding bearings laid for
     * another gun, within the salvo spread). The rest hold fire. Uses the last poll.
     */
    public SalvoResult fireSalvo(@Nullable Player orderedBy) {
        if (!(level instanceof ServerLevel serverLevel) || !isPowered || bankConflict)
            return new SalvoResult(0, 0);
        // Danger close: the first press only warns; a second within the window fires.
        CServer.DangerClose dangerConfig = CDCConfig.server().dangerClose;
        boolean danger = false;
        for (BlockPos mountPos : dangerCloseGuns.keySet())
            danger |= !isAutocannon(mountPos) && isOnTarget(mountPos);
        long now = level.getGameTime();
        if (danger && dangerConfig.allowDangerClose.get() && (dangerArmedTick < 0
                || now - dangerArmedTick > dangerConfig.confirmSeconds.get() * 20)) {
            dangerArmedTick = now;
            return new SalvoResult(0, bigCannonCount(), true);
        }
        dangerArmedTick = -1;
        List<BlockPos> fired = new ArrayList<>();
        int held = 0;
        Map<String, Integer> heldNames = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (BlockPos mountPos : barrels.keySet()) {
            if (isAutocannon(mountPos))
                continue;
            if (isOnTarget(mountPos) && CannonFiring.fire(serverLevel, mountPos)) {
                fired.add(mountPos);
            } else {
                held++;
                String name = mountNames.get(mountPos);
                if (name != null)
                    heldNames.merge(name, 1, Integer::sum);
            }
        }
        creditFire(orderedBy, fired);
        return new SalvoResult(fired.size(), held, false, listNames(heldNames));
    }

    /** Music of Battle at once; what the shells land on, one time of flight later (the longest of those fired). */
    private void creditFire(@Nullable Player player, List<BlockPos> fired) {
        if (!(player instanceof ServerPlayer serverPlayer) || fired.isEmpty() || !(level instanceof ServerLevel serverLevel))
            return;
        CDCTriggers.SALVO_FIRED.get().trigger(serverPlayer);
        int landing = 0;
        Set<Danger> dangers = EnumSet.noneOf(Danger.class);
        for (BlockPos mountPos : fired) {
            landing = Math.max(landing, flightTicks.getOrDefault(mountPos, 0));
            Danger danger = dangerCloseGuns.get(mountPos);
            if (danger != null)
                dangers.add(danger);
        }
        for (Danger danger : dangers)
            DelayedTriggers.schedule(serverPlayer, switch (danger) {
                case SAFE_ZONE -> CDCTriggers.SAFE_ZONE_SHELLED;
                case OWN_SIDE -> CDCTriggers.OWN_SIDE_SHELLED;
                case FRIENDLY -> CDCTriggers.DANGER_CLOSE;
            }, landing);
        // Loaded ground only: looking up structures would load the chunk.
        BlockPos target = BlockPos.containing(targetX, targetY, targetZ);
        if (hasTarget && serverLevel.isLoaded(target)
                && serverLevel.structureManager().getStructureWithPieceAt(target, StructureTags.VILLAGE).isValid())
            DelayedTriggers.schedule(serverPlayer, CDCTriggers.VILLAGE_SHELLED, landing);
    }

    // Held guns named in the salvo message before the rest are only counted.
    private static final int MAX_SALVO_NAMES = 4;

    /** "Turret C ×2, Bow-2, +3": counted per name, the overflow as a number. */
    private static String listNames(Map<String, Integer> counts) {
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (parts.size() < MAX_SALVO_NAMES)
                parts.add(entry.getValue() > 1 ? entry.getKey() + " ×" + entry.getValue() : entry.getKey());
            else
                more += entry.getValue();
        }
        if (more > 0)
            parts.add("+" + more);
        return String.join(", ", parts);
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
     * Fires every autocannon on target, called every tick while firing. Danger-close ones only once confirmed.
     *
     * @param creditTo the gunner, credited with what the guns firing now hit; null to credit no one
     */
    public int fireAutocannons(boolean dangerCloseConfirmed, @Nullable Player creditTo) {
        if (!(level instanceof ServerLevel serverLevel))
            return 0;
        autocannonOrderTick = level.getGameTime();
        boolean checkDanger = CDCConfig.server().dangerClose.allowDangerClose.get() && !dangerCloseConfirmed;
        List<BlockPos> firing = new ArrayList<>();
        for (BlockPos mountPos : barrels.keySet()) {
            if (!isAutocannon(mountPos))
                continue;
            boolean fire = isPowered && !bankConflict && isOnTarget(mountPos)
                    && !(checkDanger && dangerCloseGuns.containsKey(mountPos));
            setAutocannonFiring(serverLevel, mountPos, fire);
            if (fire)
                firing.add(mountPos);
        }
        creditFire(creditTo, firing);
        return firing.size();
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

    /** A rangefinder reading no older than two intervals, or a map/nav table target. Not an Integrator hold. */
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
        for (BlockPos mountPos : dangerCloseGuns.keySet())
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

    /** Whether the barrel points along its solution, whatever turns it. */
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
        // A gun riding bearings laid for another fires when its shell lands within the salvo spread.
        CServer.FireControl config = CDCConfig.server().fireControl;
        if (Math.toDegrees(off) <= config.onTargetTolerance.getF())
            return true;
        Double range = passengerRange.get(mountPos);
        return range != null && off * range <= config.salvoSpread.getF();
    }

    /** Takes a console's fuze correction if it was set more recently than the one in use. */
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

    /** Delay for delayed impact and inertia fuzes: they count from impact, so the correction alone. */
    private int impactDelay(boolean autocannon) {
        return Math.max(0, fuzeOffset(autocannon));
    }

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
        // The panel follows one gun so it does not hop: the lowest big cannon, else an autocannon.
        BallisticSolver.Result bigCannon = panelSolution(false);
        boolean autocannon = bigCannon == null;
        BallisticSolver.Result shown = autocannon ? panelSolution(true) : bigCannon;
        panelElevationDeg = shown != null ? (float) shown.pitchDeg() : 0;
        panelFuzeTicks = shown != null ? correctedFuze(shown.fuzeTicks(), autocannon) : 0;
    }

    /** The valid solution of the lowest-placed big cannon (or autocannon), if any. */
    @Nullable
    private BallisticSolver.Result panelSolution(boolean autocannon) {
        return lastPitchResults.entrySet().stream()
                .filter(e -> e.getValue().valid() && autocannonReports.contains(e.getKey()) == autocannon)
                .min(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /** Eases the panel's moving parts toward the synced readings (client). */
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

    /** Degrees clockwise from the computer's front (client). */
    public float getBearingNeedle(float partialTicks) {
        return bearingNeedle.getValue(partialTicks);
    }

    public float getRangeCounter(float partialTicks) {
        return rangeCounter.getValue(partialTicks);
    }

    /** The panel gun's elevation, degrees (client). */
    public float getCamAngle(float partialTicks) {
        return camAngle.getValue(partialTicks);
    }

    public int getFuzeTicks() {
        return panelFuzeTicks;
    }

    /** Ponder only: there is no real network there. */
    public void setPonderNetworkRpm(float rpm) {
        panelNetworkRpm = rpm;
    }

    public float getNetworkRpm() {
        return panelNetworkRpm;
    }

    public float getOwnSpeed() {
        return panelOwnSpeed;
    }

    /** 1 (fresh, or a target that does not age) to 0 (expired), counted down between syncs (client). */
    public float getHoldLeft(float partialTicks) {
        if (level == null)
            return 0;
        if (!panelHoldAges)
            return panelHoldLeftTicks > 0 ? 1 : 0;
        float elapsed = level.getGameTime() + partialTicks - panelHoldStampTick;
        return Mth.clamp((panelHoldLeftTicks - Math.max(0, elapsed)) / panelHoldLifetimeTicks, 0, 1);
    }

    /** The range as a fraction of the first gun's reach (client). */
    public float getCamFollower(float partialTicks) {
        return camFollower.getValue(partialTicks);
    }

    void pollNetwork() {
        invalidateData();

        KineticNetwork network = battleNetwork();
        if (network == null) return;

        Map<BlockPos, SensorReading.Rangefinder> rangefinderReadings = new HashMap<>();
        List<RangefinderBearingBlockEntity> rangefinders = new ArrayList<>();
        // Our own side, for danger close: the computer and crew stations (the guns join later), and the spotters.
        friendlies.clear();
        spotters.clear();
        safeZones.clear();
        Set<MapId> plottedMaps = new HashSet<>();
        friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(worldPosition)));
        SensorReading.MapTarget mapTarget = null;

        for (KineticBlockEntity member : network.members.keySet()) {
            if (member instanceof RangefinderBearingBlockEntity rangefinder) {
                rangefinders.add(rangefinder);
                friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(rangefinder.getBlockPos())));
            }
            // Where interfaces are, crew stand. A map table's maps also mark spotters carrying them.
            if (member instanceof NetworkInterfaceBlockEntity station) {
                friendlies.add(RangefinderOptics.toWorld(level, Vec3.atCenterOf(station.getBlockPos())));
                MapTableBlockEntity table = station.getFacedMapTable();
                if (table != null) {
                    plottedMaps.addAll(table.getMapIds());
                    safeZones.addAll(table.getSafeZones());
                }
            }
            if (!(member instanceof SensorReading.Provider provider)) continue;

            // A jostled interface reading attitude or driving the computer skews every aim too.
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
            if (!merged.isEmpty() && (merged.contains(pos) || pos.equals(directingRangefinder)))
                CDCTriggers.RANGEFINDERS_MERGED.get().trigger(rangefinder.getOperator());
            rangefinder.setStabilization(gimbalCount > 0, navHeadingCount > 0);
        }

        // Players carrying one of the plotted maps: plotted spotters.
        if (!plottedMaps.isEmpty())
            for (Player player : level.players())
                if (carriesAnyOf(player, plottedMaps))
                    spotters.add(player.position());
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

    /** Without an Integrator nothing holds a solution between readings. */
    private long solutionLifetimeTicks() {
        return has(ComputerModule.INTEGRATOR)
                ? RangefinderBearingBlockEntity.SOLUTION_LIFETIME_TICKS : UNHELD_SOLUTION_LIFETIME_TICKS;
    }

    /** Carried forward on its tracked velocity: takes both a Differentiator and an Integrator. */
    private Vector3d extrapolate(SensorReading.Rangefinder reading) {
        long age = level.getGameTime() - reading.sampleTick();
        Vector3d position = new Vector3d(reading.hitWorldPos().x, reading.hitWorldPos().y, reading.hitWorldPos().z);
        boolean carryForward = reading.targetVelocity() != null
                && has(ComputerModule.DIFFERENTIATOR) && has(ComputerModule.INTEGRATOR);
        return carryForward ? position.fma(age, reading.targetVelocity()) : position;
    }

    /**
     * The directing rangefinder keeps control while fresh; otherwise the freshest takes over. Others
     * on the same target (within {@link #MERGE_SIGMAS}) are averaged in, weighted by 1/σ².
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

    public boolean hasOrientationSensors() {
        return gimbalCount > 0 && navHeadingCount > 0;
    }

    public int velocityAxesCovered() {
        int n = 0;
        for (int count : velocityAxisCounts) if (count > 0) n++;
        return n;
    }

    public boolean hasFullVelocity() {
        return velocityAxesCovered() == 3;
    }

    /** 1 on ship axes covered by a velocity sensor, 0 elsewhere. */
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
        mountOfReport.clear();
        reportNames.clear();
        mountNames.clear();
        aimCache.clear();
        directsShipGuns = false;
        jointByPlate.clear();

        // Read the network: guns, the mounts' own yaw servos, and the bearings carrying them.
        List<Gun> guns = new ArrayList<>();
        Map<BlockPos, ComputerServo> yawServoOf = new HashMap<>();
        Map<BlockPos, ServoStatus> yawStatusOf = new HashMap<>();
        List<ComputerServo> namedServos = new ArrayList<>();
        for (KineticBlockEntity member : network.members.keySet()) {
            if (!(member instanceof ComputerServo servo)) continue;
            if (!servo.getServoName().isEmpty())
                namedServos.add(servo);

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
            mountOfReport.put(gun.reportPos(), gun.pos());
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

        nameGuns(guns, chains, yawServoOf, yawStatusOf, namedServos);

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
        flightTicks.clear();
        for (Gun gun : guns) {
            BallisticSolver.Result result = lastPitchResults.get(gun.reportPos());
            if (result != null && result.valid())
                flightTicks.put(gun.pos(), (int) Math.round(result.timeOfFlightTicks()));
            FireSolution aim = aimCache.get(gun.pos());
            Danger danger = aim != null && aim.valid() ? dangerOf(gun.mount(), hulls) : null;
            if (danger != null)
                dangerCloseGuns.put(gun.pos(), danger);
        }

        List<BattlePlot.GunRange> ranges = new ArrayList<>();
        for (Gun gun : guns) {
            double[] elevation = elevationLimits(gun, chains.get(gun.pos()));
            BattlePlot.GunRange range = gunRange(gun.mount(), elevation[1], elevation[0],
                    traverseArc(gun, chains.get(gun.pos()), yawStatusOf.get(gun.pos())),
                    mountNames.getOrDefault(gun.pos(), ""));
            if (range != null)
                ranges.add(range);
        }
        plot = plotFixes.isEmpty() && ranges.isEmpty() ? BattlePlot.EMPTY : new BattlePlot(plotFixes, ranges);
    }

    /**
     * Turns each driven bearing, root first, by the angle about its axis that brings its gun's barrel
     * closest to the solution. Steered by where the barrel really points. A gun needing a turn past the
     * dial's limits is out of arc and holds fire.
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
                    .withRates(0, jointRate(swivelPos, angle, director, lay.sense())));
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

    /** About its own axis, as the bearings under it will have turned it this poll. */
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
     * In order: the gun the bearing under it serves, the one it served last poll, then the rest,
     * with last poll's out-of-arc guns last.
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
     * {lowest, highest} elevation, degrees: the mount's limits and pitch dial, or what the tilting
     * bearings' dials allow. For the map table's range rings.
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

    /** World bearing arc {start, width}, degrees (360: all round), from the yaw or turret dial. For the map table. */
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
            // The barrel turns with the turret: clockwise for a bearing facing up, the other way hanging down.
            double barrelYaw = yawOf(barrelWorld(mount));
            double dialNow = dialOf(joint, joint.swivel().actualAngle(), 1);
            double start = axisWorld(joint.swivel()).y >= 0
                    ? barrelYaw + dial.start() - dialNow
                    : barrelYaw - (dial.end() - dialNow);
            return new double[]{start, dial.length()};
        }
        return new double[]{0, 360};
    }

    /** Why a gun's shot needs confirming: a safe zone, our own positions or hulls, a spotter. The later kind outranks. */
    public enum Danger { SAFE_ZONE, OWN_SIDE, FRIENDLY }

    /**
     * Whether the shell would land within its burst radius of a spotter, our own positions or hulls, or
     * else of a safe zone. Shrapnel throws its balls on in a forward cone, so for it the danger is that cone.
     */
    @Nullable
    private Danger dangerOf(ServoStatus.MountSnapshot mount, List<SubLevel> hulls) {
        if (!hasTarget || !mount.hasShell() || mount.shellType() == null)
            return null;
        CServer.DangerZones config = CDCConfig.server().dangerClose.zones;
        Vec3 target = new Vec3(targetX, targetY, targetZ);
        String shell = mount.shellType().getPath();

        Predicate<Vec3> endangered;
        // Where shrapnel's cone ends: a zone it reaches lies nearest there, not the burst.
        Vec3 coneTip = null;
        if (shell.contains("shrapnel")) {
            Vector3d pivot = launchPivot(mount, subLevelOf(mount));
            Vec3 path = target.subtract(pivot.x, pivot.y, pivot.z).normalize();
            double cos = Math.cos(Math.toRadians(config.shrapnelConeAngle.get()));
            double length = config.shrapnelConeLength.get(), bubble = config.otherShellRadius.get();
            coneTip = target.add(path.scale(length));
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

        for (Vec3 spotter : spotters)
            if (endangered.test(spotter))
                return Danger.FRIENDLY;
        for (Vec3 friendly : friendlies)
            if (endangered.test(friendly))
                return Danger.OWN_SIDE;
        for (SubLevel hull : hulls) {
            var bounds = hull.boundingBox();
            Vec3 nearest = new Vec3(Mth.clamp(target.x, bounds.minX(), bounds.maxX()),
                    Mth.clamp(target.y, bounds.minY(), bounds.maxY()), Mth.clamp(target.z, bounds.minZ(), bounds.maxZ()));
            if (endangered.test(nearest))
                return Danger.OWN_SIDE;
        }
        // A zone reaches all heights: its nearest point is level with the burst.
        for (MapTableBlockEntity.SafeZone zone : safeZones) {
            if (endangered.test(nearestIn(zone, target))
                    || coneTip != null && endangered.test(nearestIn(zone, coneTip)))
                return Danger.SAFE_ZONE;
        }
        return null;
    }

    private static Vec3 nearestIn(MapTableBlockEntity.SafeZone zone, Vec3 point) {
        return new Vec3(Mth.clamp(point.x, zone.minX(), zone.maxX() + 1), point.y,
                Mth.clamp(point.z, zone.minZ(), zone.maxZ() + 1));
    }

    /** Whether some gun's next shell would land near our own side (last poll). */
    public boolean isDangerClose() {
        return !dangerCloseGuns.isEmpty();
    }

    /** Among the guns a console orders that are on target, the gravest reason to confirm; null if none. */
    @Nullable
    public Danger dangerOnTarget(boolean autocannons) {
        Danger worst = null;
        for (Map.Entry<BlockPos, Danger> entry : dangerCloseGuns.entrySet())
            if (isAutocannon(entry.getKey()) == autocannons && isOnTarget(entry.getKey())
                    && (worst == null || entry.getValue().compareTo(worst) > 0))
                worst = entry.getValue();
        return worst;
    }

    /** Whether this bearing lies on its side, tilting a cradle: its dial reads elevation. */
    private static boolean tilts(Joint joint) {
        return joint.swivel().facing().getAxis() != Direction.Axis.Y;
    }

    /** A cradle's dial reads elevation (by {@code sense}), a turret's clockwise from its face. */
    private static double dialOf(Joint joint, double angle, int sense) {
        return Mth.wrapDegrees(tilts(joint) ? sense * angle : -angle);
    }

    /** The bearings carrying this gun, from the one under it down to the root. */
    /**
     * Each gun shows the name set closest to it: its pitch servo's, its yaw servo's, then the swivels'
     * carrying it, innermost first. A named servo none of its guns show learns what they show instead.
     */
    private void nameGuns(List<Gun> guns, Map<BlockPos, List<Joint>> chains, Map<BlockPos, ComputerServo> yawServoOf,
                          Map<BlockPos, ServoStatus> yawStatusOf, List<ComputerServo> namedServos) {
        Set<ComputerServo> shown = new HashSet<>();
        Map<ComputerServo, String> shownInstead = new HashMap<>();
        for (Gun gun : guns) {
            List<ComputerServo> candidates = new ArrayList<>();
            candidates.add(gun.servo());
            ComputerServo yaw = yawServoOf.get(gun.pos());
            if (yaw != null)
                candidates.add(yaw);
            for (Joint joint : chains.get(gun.pos()))
                candidates.add(joint.servo());
            String name = null;
            for (ComputerServo candidate : candidates) {
                String own = candidate.getServoName();
                if (own.isEmpty())
                    continue;
                if (name == null) {
                    name = own;
                    shown.add(candidate);
                } else {
                    shownInstead.putIfAbsent(candidate, name);
                }
            }
            if (name != null) {
                reportNames.put(gun.reportPos(), name);
                mountNames.put(gun.pos(), name);
            }
        }
        // A yaw servo with no pitch servo reports for itself.
        for (Map.Entry<BlockPos, ComputerServo> entry : yawServoOf.entrySet()) {
            String own = entry.getValue().getServoName();
            if (!chains.containsKey(entry.getKey()) && !own.isEmpty()) {
                reportNames.put(yawStatusOf.get(entry.getKey()).servoPos(), own);
                shown.add(entry.getValue());
            }
        }
        for (ComputerServo servo : namedServos)
            servo.receiveShownName(shown.contains(servo) ? null : shownInstead.get(servo));
    }

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

    /** More drives than a barrel's two rotations, or two about the same axis: they would only fight. */
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

    private Quaterniondc frameOf(ServoStatus.MountSnapshot mount) {
        SubLevel subLevel = subLevelOf(mount);
        return subLevel != null ? subLevel.logicalPose().orientation() : WORLD_FRAME;
    }

    /** Turn about the axis, degrees, bringing one direction closest to another; 0 if either lies along it. */
    private static double turnAbout(Vector3dc axis, Vector3dc from, Vector3dc to) {
        Vector3d f = new Vector3d(from).fma(-axis.dot(from), axis);
        Vector3d t = new Vector3d(to).fma(-axis.dot(to), axis);
        if (f.lengthSquared() < ALONG_AXIS_SQ || t.lengthSquared() < ALONG_AXIS_SQ)
            return 0;
        return Math.toDegrees(Math.atan2(axis.dot(f.cross(t, new Vector3d())), f.dot(t)));
    }

    /** Whether the bottom of its bearing chain stands on a ship. */
    private boolean onShip(ServoStatus.MountSnapshot mount) {
        List<Joint> chain = chainOf(mount);
        if (chain.isEmpty())
            return subLevelOf(mount) != null;
        return chain.get(chain.size() - 1).swivel().hull() != null;
    }

    /**
     * Reach for the map table with the current shell and charge, down to the target's height (or the
     * ground). Same top pitch as the solver, so it matches "out of range". Ignores own-ship motion.
     */
    @Nullable
    private BattlePlot.GunRange gunRange(ServoStatus.MountSnapshot mount, double maxElevation, double maxDepression,
                                         double[] arc, String name) {
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
        // Dead zone for a level ship: plunging, where the steepest shot lands; direct with the
        // target below, where the lowest depression lands.
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
        return new BattlePlot.GunRange(muzzleWorld.x, muzzleWorld.z, radius, aimX, aimZ, minRadius, arc[0], arc[1], name);
    }

    /**
     * Whether a gun's angles can be moving: a still target fired on from the ground has nothing to
     * feed forward, and the solution's drift as the turret swings round would only make the guns hunt.
     */
    private boolean feedsForward(ServoStatus.MountSnapshot mount) {
        return hasTargetVelocity || onShip(mount);
    }

    /**
     * After a poll: a target that moved more than it could have (a new designation, the
     * rangefinders on another target, another source) is a jump, not motion; rates start afresh.
     */
    private void forgetRatesOnNewTarget() {
        long now = level.getGameTime();
        if (hasTarget && lastTargetSource != TargetSource.NONE) {
            boolean jumped = targetSource != lastTargetSource;
            if (!jumped) {
                long dt = now - lastTargetTick;
                double dx = targetX - lastTargetX - targetVelX * dt;
                double dy = targetY - lastTargetY - targetVelY * dt;
                double dz = targetZ - lastTargetZ - targetVelZ * dt;
                // Ranging scatter is not a jump; a designation either stays put or is a new one.
                double tolerance = targetSource == TargetSource.RANGEFINDER
                        ? Math.max(MIN_MERGE_DISTANCE, MERGE_SIGMAS * targetSigma) : TARGET_MOVED_EPSILON;
                jumped = dx * dx + dy * dy + dz * dz > tolerance * tolerance;
            }
            if (jumped) {
                aimHistory.clear();
                jointHistory.clear();
            }
        }
        lastTargetSource = hasTarget ? targetSource : TargetSource.NONE;
        lastTargetX = targetX;
        lastTargetY = targetY;
        lastTargetZ = targetZ;
        lastTargetTick = now;
    }

    /** Attaches the rates of the commanded angles from consecutive polls, for servo feed-forward. */
    @Nullable
    private FireSolution withRates(ServoStatus status, @Nullable FireSolution solution) {
        // Rates are the Differentiator's job; without it servos only chase the angles.
        if (solution == null || level == null || !has(ComputerModule.DIFFERENTIATOR)) return solution;

        BlockPos mountPos = status.mounts().get(0).mountPos();
        if (!feedsForward(status.mounts().get(0))) {
            aimHistory.remove(mountPos);
            return solution;
        }
        long now = level.getGameTime();
        AimSample prev = aimHistory.get(mountPos);

        // Pitch and yaw servos solve in the same poll; the second reuses the estimate.
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

    /** The same as {@link #withRates} for a driven swivel bearing; a new director or sense starts afresh. */
    private double jointRate(BlockPos swivelPos, double angleDeg, Gun directorGun, int sense) {
        BlockPos director = directorGun.pos();
        // Rates are the Differentiator's job; without it servos only chase the angles.
        if (level == null || !has(ComputerModule.DIFFERENTIATOR) || !feedsForward(directorGun.mount())) {
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

        // No ballistic solution: keep tracking along the line of sight.
        SubLevel mountSubLevel = subLevelOf(mount);
        if (onShip(mount) && !hasOrientationSensors()) return null;

        Vector3d toTargetWorld = new Vector3d(targetX, targetY, targetZ).sub(launchPivot(mount, mountSubLevel));
        if (toTargetWorld.lengthSquared() < 1e-8) return null;

        Quaterniondc shipToWorld = mountSubLevel != null ? mountSubLevel.logicalPose().orientation() : WORLD_FRAME;
        Vector3d shipDir = shipToWorld.transformInverse(toTargetWorld.normalize(), new Vector3d());
        return new FireSolution(pitchOf(shipDir), yawOf(shipDir), 0, true);
    }

    /**
     * Solves a gun's barrel direction in its own frame, and its fuze.
     *
     * @param pitchServo the mount's own pitch servo; null if bearings carrying it elevate it
     * @param yawServo   the mount's own yaw servo, if any
     */
    private FireSolution solveGun(BlockPos servoPos, ServoStatus.MountSnapshot mount,
                                  @Nullable ServoStatus pitchServo, @Nullable ServoStatus yawServo) {
        boolean ownPitchDrive = pitchServo != null;
        SubLevel mountSubLevel = subLevelOf(mount);

        // No barrel: nothing to read. Without a target it still rests as before.
        if (mount.barrel() == null) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NOT_ASSEMBLED));
            return hasTarget ? null : restSolution(mount);
        }

        if (!hasTarget) {
            lastPitchResults.put(servoPos,
                    BallisticSolver.Result.invalid(BallisticSolver.Result.Reason.NO_TARGET));
            return restSolution(mount);
        }

        // A gun ashore aims in the world frame and needs no attitude sensors.
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
        // The muzzle moves as the barrel turns: start aiming straight at the target.
        Vector3d muzzleWorld = muzzleAt(pivotWorld, new Vector3d(targetWorld).sub(pivotWorld), mount);

        // Absolute targets need own altitude, which a ship only gets from an Altitude Sensor.
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

        // Elevation limits are in ship frame: solve the whole arc, check limits afterward.
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

        Vector3d worldDir = aimDirection(result.pitchDeg(), result.yawDeg());

        // Inverse of Sable's rotation of the shell's velocity when it leaves the plot.
        Vector3d shipDir = shipToWorld.transformInverse(worldDir, new Vector3d());
        double shipPitchDeg = pitchOf(shipDir);
        double shipYawDeg = yawOf(shipDir);
        // Jostled cabinets put every aim off by their bias.
        shipPitchDeg += bankBiasPitch;
        shipYawDeg += bankBiasYaw;

        // Past the barrel's top elevation: the flat arc is too far, the steep one too close.
        boolean plunging = plungingFire && !mount.autocannon();
        if (ownPitchDrive && shipPitchDeg > mount.maxElevation() && (!plunging || mount.maxElevation() > 45)) {
            lastPitchResults.put(servoPos, BallisticSolver.Result.invalid(plunging
                    ? BallisticSolver.Result.Reason.TOO_CLOSE : BallisticSolver.Result.Reason.OUT_OF_RANGE));
            return null;
        }
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
     * Velocity a shell inherits from the ship (CBC's {@code EntitySubLevelUtilMixin}), world, blocks/tick.
     * Only compensated along ship axes with velocity sensors.
     */
    private Vector3d inheritedLaunchVelocity(@Nullable SubLevel mountSubLevel, BlockPos mountPos) {
        // Takes an Integrator and velocity sensors; a gun ashore does not move.
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

    /** No target: level barrel, the way it faced when assembled. */
    private static FireSolution restSolution(ServoStatus.MountSnapshot mount) {
        return new FireSolution(0, mount.facingYaw(), 0, true);
    }

    /** A mount on a separately simulated body uses that body's pose. */
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

    /** The barrel's pivot in the world; the mount block's center if not yet measured. */
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

    public boolean hasBankConflict() { return bankConflict; }

    /** World position; null without a target, unpowered or in conflict. */
    @Nullable
    public Vec3 getTargetPos() {
        return isPowered && !bankConflict && hasTarget ? new Vec3(targetX, targetY, targetZ) : null;
    }

    public TargetSource getTargetSource() {
        return getTargetPos() != null ? targetSource : TargetSource.NONE;
    }

    /** Blocks per tick, world space; null unless tracking. */
    @Nullable
    public Vec3 getTargetVelocity() {
        return isTrackingTarget() ? new Vec3(targetVelX, targetVelY, targetVelZ) : null;
    }

    /**
     * One reporting servo as the last poll left it (server).
     *
     * @param mount  null for a yaw servo whose mount has no pitch servo
     * @param danger why a shot would need confirming, if it would
     */
    public record GunReport(BlockPos servo, @Nullable BlockPos mount, @Nullable String name, boolean autocannon,
                            BallisticSolver.Result solution, boolean onTarget, @Nullable Danger danger) {}

    public List<GunReport> getGunReports() {
        List<GunReport> reports = new ArrayList<>();
        for (Map.Entry<BlockPos, BallisticSolver.Result> entry : lastPitchResults.entrySet()) {
            BlockPos mount = mountOfReport.get(entry.getKey());
            reports.add(new GunReport(entry.getKey(), mount, reportNames.get(entry.getKey()),
                    autocannonReports.contains(entry.getKey()), entry.getValue(),
                    mount != null && isPowered && !bankConflict && isOnTarget(mount),
                    mount != null ? dangerCloseGuns.get(mount) : null));
        }
        return reports;
    }

    /** The network interface driving this computer, if one is (server). */
    @Nullable
    public BlockPos getLinkedInterface() {
        return isLinkFresh() ? linkedInterface : null;
    }

    public boolean isTrackingTarget() {
        return isPowered && !bankConflict && hasTarget && hasTargetVelocity;
    }

    /** Rangefinder fix uncertainty or map/nav table precision, blocks; NaN without a target (client). */
    public double getFixSigma() {
        return isPowered && !bankConflict && hasTarget ? targetSigma : Double.NaN;
    }

    public double getTargetSpeed() {
        return isTrackingTarget() ? Math.sqrt(targetVelX * targetVelX + targetVelY * targetVelY + targetVelZ * targetVelZ) : 0;
    }

    // --- NBT: re-derived each poll, so only synced for the client ---

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
            this.reportNames.clear();
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
                if (t.contains("Name"))
                    this.reportNames.put(pos, t.getString("Name"));
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
            String name = reportNames.get(e.getKey());
            if (name != null)
                t.putString("Name", name);
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

        // --- Missing sensors (only guns on a ship need them) ---
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

    /** The fire mission, shown by Fire Mission Consoles from synced data. */
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

        // One line per outcome with its count, then which guns: shared names counted, the unnamed last.
        Map<BallisticSolver.Result.Reason, List<BlockPos>> outcomes = new EnumMap<>(BallisticSolver.Result.Reason.class);
        for (Map.Entry<BlockPos, BallisticSolver.Result> entry : lastPitchResults.entrySet()) {
            // Only the guns this console orders.
            if (autocannonReports.contains(entry.getKey()) != autocannons)
                continue;
            BallisticSolver.Result r = entry.getValue();
            outcomes.computeIfAbsent(r.valid() ? BallisticSolver.Result.Reason.OK : r.reason(), reason -> new ArrayList<>())
                    .add(entry.getKey());
        }
        if (outcomes.isEmpty())
            return;

        CDCLang.builder()
                .add(Component.translatable("createdangerclose.ballistic_computer.solutions"))
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        for (Map.Entry<BallisticSolver.Result.Reason, List<BlockPos>> outcome : outcomes.entrySet()) {
            boolean found = outcome.getKey() == BallisticSolver.Result.Reason.OK;
            List<BlockPos> reports = outcome.getValue();
            Component label = Component.translatable(found ? "createdangerclose.ballistic_computer.solution_ok"
                    : "createdangerclose.ballistic_computer.reason." + outcome.getKey().name().toLowerCase());
            CDCLang.builder()
                    .add(Component.literal("  "))
                    .add(Component.translatable("createdangerclose.ballistic_computer.outcome_count", label, reports.size()))
                    .style(found ? ChatFormatting.GREEN : ChatFormatting.RED)
                    .forGoggles(tooltip);
            for (String line : gunNameLines(reports))
                CDCLang.builder()
                        .add(Component.literal("    " + line))
                        .style(ChatFormatting.GRAY)
                        .forGoggles(tooltip);
            if (found) {
                long fastest = Long.MAX_VALUE, slowest = Long.MIN_VALUE;
                for (BlockPos report : reports) {
                    long ticks = Math.round(lastPitchResults.get(report).timeOfFlightTicks());
                    fastest = Math.min(fastest, ticks);
                    slowest = Math.max(slowest, ticks);
                }
                MutableComponent time = Component.literal(fastest == slowest ? fastest + "" : fastest + "–" + slowest)
                        .append(Component.translatable("createdangerclose.unit.ticks"));
                CDCLang.builder()
                        .add(Component.literal("    "))
                        .add(Component.translatable("createdangerclose.ballistic_computer.time_of_flight", time))
                        .style(ChatFormatting.DARK_GREEN)
                        .forGoggles(tooltip);
            }
        }
    }

    // Names listed per outcome before the rest are only counted; a line wraps past about this many characters.
    private static final int MAX_LISTED_NAMES = 6;
    private static final int NAME_LINE_CHARS = 36;

    /** "Turret A ×2, Turret B, 3 unnamed", wrapped; none if every gun is unnamed (the count says it all). */
    private List<String> gunNameLines(List<BlockPos> reports) {
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        int unnamed = 0;
        for (BlockPos report : reports) {
            String name = reportNames.get(report);
            if (name == null)
                unnamed++;
            else
                counts.merge(name, 1, Integer::sum);
        }
        if (counts.isEmpty())
            return List.of();

        List<String> parts = new ArrayList<>();
        int more = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (parts.size() < MAX_LISTED_NAMES)
                parts.add(entry.getValue() > 1 ? entry.getKey() + " ×" + entry.getValue() : entry.getKey());
            else
                more += entry.getValue();
        }
        if (more > 0)
            parts.add(Component.translatable("createdangerclose.ballistic_computer.more_guns", more).getString());
        if (unnamed > 0)
            parts.add(Component.translatable("createdangerclose.ballistic_computer.unnamed_guns", unnamed).getString());

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i) + (i < parts.size() - 1 ? "," : "");
            if (!line.isEmpty() && line.length() + 1 + part.length() > NAME_LINE_CHARS) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty())
                line.append(' ');
            line.append(part);
        }
        lines.add(line.toString());
        return lines;
    }
}
