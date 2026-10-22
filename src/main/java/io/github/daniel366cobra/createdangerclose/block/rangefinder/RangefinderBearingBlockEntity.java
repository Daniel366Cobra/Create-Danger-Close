package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.config.CServer;
import io.github.daniel366cobra.createdangerclose.damage.BattleDamage;
import io.github.daniel366cobra.createdangerclose.damage.LensDust;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import io.github.daniel366cobra.createdangerclose.foundation.damage.BattleDamageable;
import io.github.daniel366cobra.createdangerclose.foundation.damage.DamagePart;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.SensorReading;
import io.github.daniel366cobra.createdangerclose.registries.CDCTriggers;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Trains a rangefinder tube in yaw. A stress consumer: the tube does not spin with the shaft but
 * eases toward the seated operator's heading (held after they leave), at most
 * {@link #DEG_PER_TICK_PER_RPM} per RPM. Unpowered, it parks at the assembled heading, to which angles
 * are relative.
 */
public class RangefinderBearingBlockEntity extends KineticBlockEntity
        implements IBearingBlockEntity, IDisplayAssemblyExceptions, SensorReading.Provider, BattleDamageable {

    // A quarter of CBC's mount scale: 6°/s at 32 RPM.
    private static final float DEG_PER_TICK_PER_RPM = 0.0375f / 4;
    // Unpowered (or overstressed), it returns to its assembled heading at this speed.
    private static final float PARKING_RPM = 64;
    // From rest to top speed, and back.
    private static final float ACCELERATION_TICKS = 10;
    // Within this of the server, the client keeps its own smooth motion.
    private static final float CLIENT_RESYNC_THRESHOLD_DEG = 2f;

    @Nullable protected ControlledContraptionEntity movedContraption;
    protected float angle;
    protected float prevAngle;
    // °/tick.
    protected float angularVelocity;
    protected boolean running;
    @Nullable protected AssemblyException lastException;
    // Held after the operator dismounts, until disassembly.
    @Nullable protected Float operatorTarget;
    // Strongest redstone signal into the bearing (0 = unlit).
    protected int illumination;

    // --- Ranging ---
    // Degrees.
    private static final float ON_TARGET_DEG = 0.5f;
    // Error multiplier at the ranging threshold, falling to 1 when merged.
    private static final double UNMERGED_ERROR_FACTOR = 5;
    private static final int DUST_INTERVAL_TICKS = 20;
    // 0 clean to 1 caked, by place in the tube's lens list.
    private final float[] lensDust = new float[2];
    private static final int MIN_VELOCITY_SAMPLES = 3;

    private final RangefinderSteadiness steadiness = new RangefinderSteadiness();
    private final RangeTracker tracker = new RangeTracker();
    // To credit the lens advancements on entering (server).
    @Nullable private Player lastOperator;
    private boolean rangingActive;
    private boolean pendingSample;
    private int ticksToNextSample;
    // Readouts, synced to the client.
    private boolean hasRange;
    private float range;          // blocks
    private boolean hasVelocity;
    private float rangeRate;      // blocks per second, positive = opening
    private float bearingRate;    // degrees per second, positive = clockwise
    // The solution (server only) outlives the operator: fire control keeps shooting on it, extrapolated, until stale.
    public static final long SOLUTION_LIFETIME_TICKS = 200;
    // A lock this far off earns an advancement, blocks.
    private static final double FARSIGHTED_RANGE = 300;
    @Nullable private Vec3 targetPosition;
    @Nullable private Vec3 targetVelocity;
    @Nullable private Vec3 solutionOrigin;
    private long solutionTick;
    private double solutionSigma;
    private boolean solutionOnShip;

    /** Set by the Ballistic Computer. */
    public enum FireControlStatus {
        NONE,
        DIRECTING,
    /** Same target as the directing rangefinder; readings are merged. */
        MERGED,
    /** Another rangefinder is directing; readings unused. */
        STANDBY
    }

    // Refreshed every poll (at most 20 ticks), else it lapses.
    private static final long STATUS_LIFETIME_TICKS = 40;
    private FireControlStatus fireControlStatus = FireControlStatus.NONE;
    private long fireControlStatusTick;
    // From the computer's sensors: Gimbal Sensor for pitch and roll, Nav Table with a Magnet for yaw.
    private boolean stabilizedTilt, stabilizedYaw;

    public RangefinderBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(3);
    }

    // --- Assembly ---

    public void toggleAssembly(@Nullable Player player) {
        if (running) {
            disassemble();
        } else {
            assemble(player);
        }
    }

    public void assemble(@Nullable Player player) {
        if (level == null || !(getBlockState().getBlock() instanceof RangefinderBearingBlock))
            return;

        // Standing on a deck or hanging under a hull: either way it turns about the vertical.
        Direction facing = getBlockState().getValue(BearingBlock.FACING);
        if (facing.getAxis() != Direction.Axis.Y) {
            lastException = new AssemblyException(
                    Component.translatable("createdangerclose.rangefinder_bearing.must_be_vertical"));
            sendData();
            return;
        }

        RangefinderContraption contraption = new RangefinderContraption(facing);
        try {
            if (!contraption.assemble(level, worldPosition))
                return;
            lastException = null;
        } catch (AssemblyException e) {
            lastException = e;
            sendData();
            return;
        }

        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        movedContraption = ControlledContraptionEntity.create(level, this, contraption);
        BlockPos anchor = worldPosition.relative(facing);
        movedContraption.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        movedContraption.setRotationAxis(facing.getAxis());
        level.addFreshEntity(movedContraption);

        AllSoundEvents.CONTRAPTION_ASSEMBLE.playOnServer(level, worldPosition);

        running = true;
        angle = prevAngle = 0;
        angularVelocity = 0;
        sendData();
        CDCTriggers.RANGEFINDER_ASSEMBLED.get().trigger(player);
    }

    public void disassemble() {
        if (!running && movedContraption == null)
            return;
        if (movedContraption != null) {
            movedContraption.disassemble();
            AllSoundEvents.CONTRAPTION_DISASSEMBLE.playOnServer(level, worldPosition);
        }
        movedContraption = null;
        running = false;
        angle = prevAngle = 0;
        angularVelocity = 0;
        operatorTarget = null;
        clearRanging();
        sendData();
    }

    @Override
    public void remove() {
        if (level != null && !level.isClientSide)
            disassemble();
        super.remove();
    }

    // --- Training ---

    /** The heading of the seated (or last) operator; with none yet, it stays put. Unpowered, the assembled heading. */
    public float getTargetAngle() {
        if (getSpeed() == 0)
            return 0;
        return operatorTarget != null ? operatorTarget : angle;
    }

    @Nullable
    public Player getOperator() {
        if (movedContraption == null || !(movedContraption.getContraption() instanceof RangefinderContraption rc))
            return null;
        BlockPos eyepiece = rc.getEyepiecePos();
        if (eyepiece == null)
            return null;
        for (Entity passenger : movedContraption.getPassengers()) {
            if (passenger instanceof Player player && eyepiece.equals(rc.getSeatOf(player.getUUID())))
                return player;
        }
        return null;
    }

    /**
     * Create's Y rotation lowers a direction's yaw by a, so lenses at local yaw ψ₀ look along ψ at
     * a = ψ₀ − ψ. The operator's yaw is ship-relative (Sable carries the rider's view).
     */
    private static float angleForOperator(RangefinderContraption contraption, Player operator) {
        Direction lensFacing = contraption.getLensFacing();
        float localYaw = lensFacing != null ? lensFacing.toYRot() : 0;
        return Mth.wrapDegrees(localYaw - operator.getYRot());
    }

    /** 0 (unlit) to 15. */
    public int getIllumination() {
        return illumination;
    }

    @Override
    public void tick() {
        super.tick();
        prevAngle = angle;

        if (level.isClientSide)
            emitDust();
        else
            tickDust();

        if (!level.isClientSide) {
            int signal = level.getBestNeighborSignal(worldPosition);
            if (signal != illumination) {
                illumination = signal;
                sendData();
            }
            if ((fireControlStatus != FireControlStatus.NONE || stabilizedTilt || stabilizedYaw)
                    && level.getGameTime() - fireControlStatusTick > STATUS_LIFETIME_TICKS) {
                fireControlStatus = FireControlStatus.NONE;
                stabilizedTilt = stabilizedYaw = false;
                sendData();
            }
        }

        if (!running)
            return;

        Player operator = getOperator();
        // Parked, it waits at the assembled heading for the next operator rather than the last one.
        if (getSpeed() == 0)
            operatorTarget = null;
        else if (operator != null && movedContraption.getContraption() instanceof RangefinderContraption rc)
            operatorTarget = angleForOperator(rc, operator);
        if (!level.isClientSide && operator != lastOperator) {
            lastOperator = operator;
            creditCondition(operator);
        }

        if (movedContraption == null || !movedContraption.isStalled()) {
            stepTraining();
        } else {
            angularVelocity = 0;
        }

        applyRotation();

        if (!level.isClientSide)
            tickRanging(operator);
    }

    // --- Damage ---

    @Override
    public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide)
            BattleDamage.track(this);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        BattleDamage.untrack(this);
    }

    // --- Dust (see LensDust) ---

    /** Once a second. */
    private void tickDust() {
        if (level.getGameTime() % DUST_INTERVAL_TICKS != 0 || !CDCConfig.server().maintenance.dust.allowDust.get()
                || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube))
            return;
        List<BlockPos> lenses = tube.getLenses();
        boolean changed = false;
        for (int i = 0; i < Math.min(lenses.size(), lensDust.length); i++) {
            BlockPos lensWorld = BlockPos.containing(RangefinderOptics.toWorld(level,
                    movedContraption.toGlobalVector(lenses.get(i).getCenter(), 1f)));
            float before = lensDust[i];
            lensDust[i] = LensDust.weather(level, lensWorld, before, DUST_INTERVAL_TICKS);
            // Sent on in whole percent: the drift between is too slow to matter.
            changed |= (int) (before * 100) != (int) (lensDust[i] * 100);
        }
        // Covers dust settling while someone is at the eyepiece.
        creditCondition(getOperator());
        if (changed) {
            setChanged();
            sendData();
        }
    }

    /** A few motes drifting off a dusty lens (client). */
    private void emitDust() {
        if (!CDCConfig.client().damageParticles.get() || movedContraption == null || !(movedContraption.getContraption() instanceof RangefinderContraption tube))
            return;
        List<BlockPos> lenses = tube.getLenses();
        for (int i = 0; i < Math.min(lenses.size(), lensDust.length); i++) {
            if (lensDust[i] < LensDust.DUSTY || level.random.nextInt(40) != 0)
                continue;
            Vec3 at = movedContraption.toGlobalVector(lenses.get(i).getCenter(), 0)
                    .add(level.random.nextGaussian() * 0.3, 0.3, level.random.nextGaussian() * 0.3);
            level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState()),
                    at.x, at.y, at.z, 0, 0, 0);
        }
    }

    public float getLensDust(int index) {
        return index >= 0 && index < lensDust.length ? lensDust[index] : 0;
    }

    /** Lens advancements for the operator: cracked, dusty, caked. */
    private void creditCondition(@Nullable Player operator) {
        if (operator == null || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube))
            return;
        if (tube.getCrackedLensCount() > 0) {
            CDCTriggers.EQUIPMENT_MALFUNCTION.get().trigger(operator);
            CDCTriggers.LENS_CRACKED.get().trigger(operator);
        }
        float dustiest = Math.max(lensDust[0], lensDust[1]);
        if (dustiest >= LensDust.DUSTY)
            CDCTriggers.LENS_DUSTY.get().trigger(operator);
        if (dustiest >= 1)
            CDCTriggers.LENS_CAKED.get().trigger(operator);
    }

    /** Local position to lens list index, or -1. */
    public int lensIndex(BlockPos lens) {
        return movedContraption != null && movedContraption.getContraption() instanceof RangefinderContraption tube
                ? tube.getLenses().indexOf(lens) : -1;
    }

    /** Returns whether it was dusty at all. */
    public boolean cleanLens(BlockPos lens) {
        if (movedContraption == null || !(movedContraption.getContraption() instanceof RangefinderContraption tube))
            return false;
        int index = tube.getLenses().indexOf(lens);
        if (index < 0 || index >= lensDust.length || lensDust[index] < 0.01f)
            return false;
        lensDust[index] = 0;
        setChanged();
        sendData();
        return true;
    }

    // Blast or a hit cracks lenses (see BattleDamage).

    @Override
    @Nullable
    public Level damageLevel() {
        return isRemoved() ? null : level;
    }

    @Override
    public List<DamagePart> damageParts() {
        return lensParts(false);
    }

    @Override
    public List<DamagePart> faultyParts() {
        return lensParts(true);
    }

    private List<DamagePart> lensParts(boolean cracked) {
        if (level == null || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube))
            return List.of();
        ControlledContraptionEntity tubeEntity = movedContraption;
        // World -> ship -> tube: in the tube's own frame, each lens is a unit box at its local position.
        SubLevel ship = Sable.HELPER.getContaining(level, worldPosition);
        UnaryOperator<Vec3> worldToTube = world -> tubeEntity.toLocalVector(
                ship != null ? ship.logicalPose().transformPositionInverse(world) : world, 1f);
        List<DamagePart> parts = new ArrayList<>();
        for (BlockPos lens : tube.getLenses()) {
            if (tube.isLensCracked(lens) != cracked)
                continue;
            Vec3 center = RangefinderOptics.toWorld(level, tubeEntity.toGlobalVector(lens.getCenter(), 1f));
            parts.add(new DamagePart(lens, DamagePart.PartKind.LENS, new AABB(lens), center, worldToTube));
        }
        return parts;
    }

    @Override
    public void onBlast(DamagePart part, double severity) {
        if (BattleDamage.radiiFromCenter(severity) <= CDCConfig.server().battleDamage.lensCrackDistance.getF())
            crackLens((BlockPos) part.id());
    }

    @Override
    public void onHit(DamagePart part) {
        crackLens((BlockPos) part.id());
    }

    public void crackLens(BlockPos lens) {
        if (level == null || level.isClientSide || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube)
                || tube.isLensCracked(lens))
            return;
        StructureBlockInfo info = tube.getBlocks().get(lens);
        if (info == null || !(info.state().getBlock() instanceof RangefinderLensBlock))
            return;
        // Create syncs the changed block to everyone tracking the tube.
        movedContraption.setBlock(lens, new StructureBlockInfo(info.pos(),
                info.state().setValue(RangefinderLensBlock.CRACKED, true), info.nbt()));
        level.playSound(null, BlockPos.containing(movedContraption.toGlobalVector(lens.getCenter(), 1f)),
                SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1f, 0.8f);
        creditCondition(getOperator());
    }

    public boolean isOnTarget() {
        return Math.abs(AngleHelper.getShortestAngleDiff(angle, getTargetAngle())) < ON_TARGET_DEG;
    }

    // --- Ranging ---

    /** From the operator's client: RMB pressed or released. */
    public void setRanging(Player player, boolean active) {
        if (player != getOperator())
            return;
        if (active && !rangingActive)
            pendingSample = true; // a click takes one reading straight away
        rangingActive = active;
    }

    private void tickRanging(@Nullable Player operator) {
        if (operator == null) {
            // Nobody at the eyepiece: drop the view state, keep the solution.
            if (hasRange || hasVelocity || rangingActive || pendingSample) {
                clearOperatorState();
                sendData();
            }
            return;
        }
        RangefinderOptics.Sight sight = RangefinderOptics.lineOfSight(operator);
        if (sight == null)
            return;
        steadiness.update(sight.direction(), isOnTarget());

        // Readouts stand only while the images stay converged (the tracked target is kept).
        if (hasRange && !steadiness.canRange()) {
            hasRange = hasVelocity = false;
            sendData();
        } else if (hasVelocity && !steadiness.canTrackVelocity()) {
            hasVelocity = false;
            sendData();
        }

        if (pendingSample || (rangingActive && --ticksToNextSample <= 0)) {
            pendingSample = false;
            ticksToNextSample = sampleInterval();
            takeSample(operator, sight);
        }
    }

    /** Readings come at the battle network's pace, like its other devices. */
    private int sampleInterval() {
        float rpm = Math.abs(getSpeed());
        return rpm < 1 ? Integer.MAX_VALUE : Math.max(1, Math.round(640f / rpm));
    }

    private void takeSample(Player operator, RangefinderOptics.Sight sight) {
        if (!steadiness.canRange()
                || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube)
                || tube.getBaseline() <= 0)
            return;

        Vec3 end = sight.origin().add(sight.direction().scale(CDCConfig.server().rangefinder.maxRange.get()));
        BlockHitResult hit = level.clip(new ClipContext(sight.origin(), end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS)
            return;

        // Sable reports hits on ships in ship coordinates.
        double trueRange = RangefinderOptics.toWorld(level, hit.getLocation()).distanceTo(sight.origin());
        double convergence = 1 + (UNMERGED_ERROR_FACTOR - 1)
                * steadiness.getSeparation() / RangefinderSteadiness.RANGING_SEPARATION;
        // Best error R²·σθ / baseline (σθ in mrad, config). Cracked lenses multiply it; dust adds in proportion.
        CServer.Rangefinder config = CDCConfig.server().rangefinder;
        double dusty = config.dustyLensFactor.getF() - 1;
        double sigma = trueRange * trueRange * config.rangingError.getF() * 1e-3 / tube.getBaseline() * convergence
                * Math.pow(config.crackedLensFactor.getF(), tube.getCrackedLensCount())
                * (1 + dusty * lensDust[0]) * (1 + dusty * lensDust[1]);
        double measuredRange = Math.max(1, trueRange + level.random.nextGaussian() * sigma);

        tracker.update(sight.origin().add(sight.direction().scale(measuredRange)), level.getGameTime(), sigma);
        targetPosition = tracker.getPosition();
        solutionOrigin = sight.origin();
        solutionTick = level.getGameTime();
        solutionSigma = sigma;
        SubLevel targetShip = Sable.HELPER.getContaining(level, hit.getLocation());
        solutionOnShip = targetShip != null;
        Vec3 toTarget = targetPosition.subtract(sight.origin());
        hasRange = true;
        range = (float) toTarget.length();
        CDCTriggers.RANGEFINDER_LOCK.get().trigger(operator);
        if (trueRange > FARSIGHTED_RANGE)
            CDCTriggers.RANGEFINDER_LOCK_FAR.get().trigger(operator);
        if (targetShip != null && targetShip != Sable.HELPER.getContaining(level, worldPosition))
            CDCTriggers.RANGEFINDER_LOCK_SHIP.get().trigger(operator);

        // A new track has no velocity yet: don't lead with the previous target's.
        if (tracker.getSamples() < MIN_VELOCITY_SAMPLES)
            targetVelocity = null;

        if (steadiness.canTrackVelocity() && tracker.getSamples() >= MIN_VELOCITY_SAMPLES) {
            targetVelocity = tracker.getVelocity();
            Vec3 perSecond = targetVelocity.scale(20);
            hasVelocity = true;
            rangeRate = (float) (perSecond.dot(toTarget) / toTarget.length());
            // Bearing θ = atan2(x, −z); dθ/dt = (x·ż − z·ẋ) / (x² + z²).
            double horizontalSq = toTarget.x * toTarget.x + toTarget.z * toTarget.z;
            bearingRate = horizontalSq < 1e-6 ? 0 : (float) Math.toDegrees(
                    (toTarget.x * perSecond.z - toTarget.z * perSecond.x) / horizontalSq);
        }
        sendData();
    }

    /** View state and the target solution. */
    private void clearRanging() {
        clearOperatorState();
        tracker.reset();
        targetPosition = targetVelocity = solutionOrigin = null;
    }

    /** View state only; the solution is kept. */
    private void clearOperatorState() {
        steadiness.reset();
        rangingActive = pendingSample = false;
        hasRange = hasVelocity = false;
        range = rangeRate = bearingRate = 0;
    }

    /** Every Ballistic Computer poll. */
    public void setFireControlStatus(FireControlStatus status) {
        fireControlStatusTick = level.getGameTime();
        if (status != fireControlStatus) {
            fireControlStatus = status;
            sendData();
        }
    }

    public FireControlStatus getFireControlStatus() {
        return fireControlStatus;
    }

    /** Every Ballistic Computer poll, after {@link #setFireControlStatus}. */
    public void setStabilization(boolean tilt, boolean yaw) {
        if (tilt != stabilizedTilt || yaw != stabilizedYaw) {
            stabilizedTilt = tilt;
            stabilizedYaw = yaw;
            sendData();
        }
    }

    public boolean isStabilizedTilt() {
        return stabilizedTilt;
    }

    public boolean isStabilizedYaw() {
        return stabilizedYaw;
    }

    /** The solution while fresh. Outranks navigation tables. */
    @Override
    public SensorReading getSensorReading() {
        if (level == null || targetPosition == null || solutionOrigin == null
                || level.getGameTime() - solutionTick > SOLUTION_LIFETIME_TICKS)
            return new SensorReading.None();

        Vec3 toTarget = targetPosition.subtract(solutionOrigin);
        double targetRange = toTarget.length();
        if (targetRange < 1e-6)
            return new SensorReading.None();
        Vector3d velocity = targetVelocity != null
                ? new Vector3d(targetVelocity.x, targetVelocity.y, targetVelocity.z)
                : null;
        return new SensorReading.Rangefinder(
                RangefinderOptics.compassBearing(toTarget),
                Math.toDegrees(Math.asin(toTarget.y / targetRange)),
                targetRange,
                solutionOnShip,
                velocity,
                targetPosition,
                solutionTick,
                solutionSigma);
    }

    public boolean hasRange() { return hasRange; }
    public float getRange() { return range; }
    public boolean hasVelocity() { return hasVelocity; }
    public float getRangeRate() { return rangeRate; }
    public float getBearingRate() { return bearingRate; }

    /** World space (server only). */
    @Nullable
    public Vec3 getTargetPosition() { return targetPosition; }

    /** Trapezoidal motion: accelerate up to top speed, never faster than it can brake to the target. Both scale with RPM. */
    private void stepTraining() {
        float maxSpeed = (getSpeed() != 0 ? Math.abs(getSpeed()) : PARKING_RPM) * DEG_PER_TICK_PER_RPM;
        if (level.isClientSide)
            maxSpeed *= ServerSpeedProvider.get();
        if (maxSpeed <= 0) {
            angularVelocity = 0;
            return;
        }
        float acceleration = maxSpeed / ACCELERATION_TICKS;

        float remaining = AngleHelper.getShortestAngleDiff(angle, getTargetAngle());
        float brakingSpeed = (float) Math.sqrt(2 * acceleration * Math.abs(remaining));
        float desired = Math.copySign(Math.min(maxSpeed, brakingSpeed), remaining);
        angularVelocity += Mth.clamp(desired - angularVelocity, -acceleration, acceleration);

        boolean towardTarget = Math.signum(angularVelocity) == Math.signum(remaining);
        if (towardTarget && Math.abs(angularVelocity) >= Math.abs(remaining)) {
            angle = Mth.wrapDegrees(angle + remaining);
            angularVelocity = 0;
            return;
        }
        angle = Mth.wrapDegrees(angle + angularVelocity);
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (movedContraption != null && level != null && !level.isClientSide)
            sendData();
    }

    protected void applyRotation() {
        if (movedContraption != null)
            movedContraption.setAngle(angle);
    }

    // --- IBearingBlockEntity / IControlContraption ---

    @Override
    public float getInterpolatedAngle(float partialTicks) {
        // Renderers pass partialTicks - 1: -1 is last tick, 0 this tick.
        if (!running)
            return angle;
        return AngleHelper.angleLerp(partialTicks + 1, prevAngle, angle);
    }

    @Override
    public boolean isWoodenTop() {
        return false;
    }

    @Override
    public void setAngle(float forcedAngle) {
        angle = forcedAngle;
    }

    @Override
    public boolean isAttachedTo(AbstractContraptionEntity contraption) {
        return movedContraption == contraption;
    }

    @Override
    public void attach(ControlledContraptionEntity contraption) {
        if (!(contraption.getContraption() instanceof RangefinderContraption))
            return;
        BlockState state = getBlockState();
        if (!state.hasProperty(BearingBlock.FACING))
            return;

        movedContraption = contraption;
        setChanged();
        BlockPos anchor = worldPosition.relative(state.getValue(BearingBlock.FACING));
        movedContraption.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
        if (level != null && !level.isClientSide) {
            running = true;
            sendData();
        }
    }

    @Override
    public void onStall() {
        if (level != null && !level.isClientSide)
            sendData();
    }

    @Override
    public boolean isValid() {
        return !isRemoved();
    }

    @Override
    public BlockPos getBlockPosition() {
        return worldPosition;
    }

    @Override
    @Nullable
    public AssemblyException getLastAssemblyException() {
        return lastException;
    }

    // --- NBT ---

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.putBoolean("Running", running);
        compound.putFloat("Angle", angle);
        compound.putFloat("AngularVelocity", angularVelocity);
        compound.putInt("Illumination", illumination);
        compound.putFloat("LensDust0", lensDust[0]);
        compound.putFloat("LensDust1", lensDust[1]);
        compound.putBoolean("HasRange", hasRange);
        compound.putFloat("Range", range);
        compound.putBoolean("HasVelocity", hasVelocity);
        compound.putFloat("RangeRate", rangeRate);
        compound.putFloat("BearingRate", bearingRate);
        compound.putInt("FireControlStatus", fireControlStatus.ordinal());
        compound.putBoolean("StabilizedTilt", stabilizedTilt);
        compound.putBoolean("StabilizedYaw", stabilizedYaw);
        if (operatorTarget != null)
            compound.putFloat("OperatorTarget", operatorTarget);
        AssemblyException.write(compound, registries, lastException);
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (wasMoved) {
            super.read(compound, registries, clientPacket);
            return;
        }

        float clientAngle = angle;
        float clientVelocity = angularVelocity;
        running = compound.getBoolean("Running");
        angle = compound.getFloat("Angle");
        angularVelocity = compound.getFloat("AngularVelocity");
        illumination = compound.getInt("Illumination");
        lensDust[0] = compound.getFloat("LensDust0");
        lensDust[1] = compound.getFloat("LensDust1");
        hasRange = compound.getBoolean("HasRange");
        range = compound.getFloat("Range");
        hasVelocity = compound.getBoolean("HasVelocity");
        rangeRate = compound.getFloat("RangeRate");
        bearingRate = compound.getFloat("BearingRate");
        FireControlStatus[] statuses = FireControlStatus.values();
        int statusOrdinal = compound.getInt("FireControlStatus");
        fireControlStatus = statusOrdinal >= 0 && statusOrdinal < statuses.length
                ? statuses[statusOrdinal] : FireControlStatus.NONE;
        stabilizedTilt = compound.getBoolean("StabilizedTilt");
        stabilizedYaw = compound.getBoolean("StabilizedYaw");
        operatorTarget = compound.contains("OperatorTarget") ? compound.getFloat("OperatorTarget") : null;
        lastException = AssemblyException.read(compound, registries);
        super.read(compound, registries, clientPacket);

        if (!clientPacket)
            return;
        if (!running) {
            movedContraption = null;
            return;
        }
        // The client simulates the same training motion; only correct real drift.
        if (Math.abs(AngleHelper.getShortestAngleDiff(clientAngle, angle)) < CLIENT_RESYNC_THRESHOLD_DEG) {
            angle = clientAngle;
            angularVelocity = clientVelocity;
        }
    }

    // --- Tooltip ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.rangefinder_bearing.title"))
                .forGoggles(tooltip);

        if (running) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.rangefinder_bearing.angle",
                            String.format("%.1f", angle)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip);
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.rangefinder_bearing.target",
                            String.format("%.1f", getTargetAngle())))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
            if (movedContraption != null && movedContraption.getContraption() instanceof RangefinderContraption rc) {
                CDCLang.builder()
                        .add(Component.translatable("createdangerclose.rangefinder_bearing.baseline",
                                rc.getBaseline()))
                        .style(ChatFormatting.GRAY)
                        .forGoggles(tooltip);
                if (rc.getCrackedLensCount() > 0) {
                    // The message carries its own line break before the repair hint.
                    for (String line : Component.translatable("createdangerclose.error.broken_lens")
                            .getString().split("\n"))
                        CDCLang.builder()
                                .add(Component.literal(line.trim()))
                                .style(ChatFormatting.RED)
                                .forGoggles(tooltip);
                }
                if (Math.max(lensDust[0], lensDust[1]) >= LensDust.DUSTY) {
                    for (String line : Component.translatable("createdangerclose.error.dusty_lens")
                            .getString().split("\n"))
                        CDCLang.builder()
                                .add(Component.literal(line.trim()))
                                .style(ChatFormatting.GOLD)
                                .forGoggles(tooltip);
                }
            }
            Player operator = getOperator();
            CDCLang.builder()
                    .add(operator != null
                            ? Component.translatable("createdangerclose.rangefinder_bearing.operator",
                                    operator.getDisplayName())
                            : Component.translatable("createdangerclose.rangefinder_bearing.no_operator"))
                    .style(operator != null ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.rangefinder_bearing.not_assembled"))
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }

        CDCLang.builder()
                .add(illumination > 0
                        ? Component.translatable("createdangerclose.rangefinder_bearing.illumination", illumination)
                        : Component.translatable("createdangerclose.rangefinder_bearing.unlit"))
                .style(illumination > 0 ? ChatFormatting.GOLD : ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);

        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }
}