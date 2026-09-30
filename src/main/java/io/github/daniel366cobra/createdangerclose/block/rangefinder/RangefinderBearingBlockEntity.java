package io.github.daniel366cobra.createdangerclose.block.rangefinder;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.IDisplayAssemblyExceptions;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;
import dev.ryanhcode.sable.Sable;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import io.github.daniel366cobra.createdangerclose.api.sensor.SensorReading;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

/**
 * Trains a rangefinder tube in yaw.
 *
 * <p>Unlike a mechanical bearing, the tube does not spin with the shaft. The
 * bearing is a stress consumer on the battle network and turns the tube
 * towards a target angle, easing in and out, at most
 * {@link #DEG_PER_TICK_PER_RPM} per network RPM per tick. The target follows the operator seated at the eyepiece; with
 * nobody seated it holds their last heading, or comes from the manual
 * training value box (0..359°) once that is changed.
 *
 * <p>Angles are the contraption's rotation about the vertical axis, in
 * degrees relative to the heading it was assembled at.
 */
public class RangefinderBearingBlockEntity extends KineticBlockEntity
        implements IBearingBlockEntity, IDisplayAssemblyExceptions, SensorReading.Provider {

    // Top traverse speed: a quarter of CBC's cannon mount scale (0.0375°/tick
    // per RPM), i.e. 6°/s at 32 RPM, 48°/s at 256 RPM.
    private static final float DEG_PER_TICK_PER_RPM = 0.0375f / 4;
    // Ticks to accelerate from rest to top speed (and to brake from it).
    private static final float ACCELERATION_TICKS = 10;
    // Client-side angle within this of the server's is kept, so the smoothly
    // simulated motion is not snapped back on every sync.
    private static final float CLIENT_RESYNC_THRESHOLD_DEG = 2f;

    protected ScrollValueBehaviour manualTraining;
    @Nullable protected ControlledContraptionEntity movedContraption;
    protected float angle;
    protected float prevAngle;
    // Current traverse rate, °/tick. Ramps up and down for ease-in/ease-out.
    protected float angularVelocity;
    protected boolean running;
    @Nullable protected AssemblyException lastException;
    // Last heading an operator trained to. Held after they dismount, until
    // the manual training value is changed.
    @Nullable protected Float operatorTarget;
    // Reticle lamp brightness: the strongest redstone signal into the bearing (0 = unlit).
    protected int illumination;

    // --- Ranging ---
    // Tube counts as caught up with the operator's look within this, degrees.
    private static final float ON_TARGET_DEG = 0.5f;
    private static final double MAX_RANGE = 1024;
    // Angular resolution of the optics, radians: range error at best is
    // R²·σθ / baseline, e.g. ~5 blocks at 300 blocks with an 8-block baseline.
    private static final double SIGMA_THETA_RAD = 4.4e-4;
    // Error multiplier at the ranging threshold, falling to 1 when merged.
    private static final double UNMERGED_ERROR_FACTOR = 5;
    // Error multiplier for each cracked lens (both cracked: squared).
    private static final double CRACKED_LENS_ERROR_FACTOR = 4;
    private static final int MIN_VELOCITY_SAMPLES = 3;

    private final RangefinderSteadiness steadiness = new RangefinderSteadiness();
    private final RangeTracker tracker = new RangeTracker();
    private boolean rangingActive;
    private boolean pendingSample;
    private int ticksToNextSample;
    // Readouts, synced to the client.
    private boolean hasRange;
    private float range;          // blocks
    private boolean hasVelocity;
    private float rangeRate;      // blocks per second, positive = opening
    private float bearingRate;    // degrees per second, positive = clockwise
    // Target solution for the fire control solver (server only). Outlives
    // the operator and the readouts: fire control keeps shooting on the last
    // good solution, extrapolated, until it goes stale.
    public static final long SOLUTION_LIFETIME_TICKS = 200;
    @Nullable private Vec3 targetPosition;
    @Nullable private Vec3 targetVelocity;
    @Nullable private Vec3 solutionOrigin;
    private long solutionTick;
    private double solutionSigma;
    private boolean solutionOnShip;

    /** This rangefinder's role in fire control, as decided by the solver. */
    public enum FireControlStatus {
        /** No fire control using rangefinder data (no solver, or nothing ranged). */
        NONE,
        /** In control: the guns follow this rangefinder. */
        DIRECTING,
        /** Ranging the same target as the directing rangefinder; readings are merged. */
        MERGED,
        /** Another rangefinder is directing; this one's readings are not used. */
        STANDBY
    }

    // The solver refreshes this every poll (at most every 20 ticks); without
    // refreshes the status lapses.
    private static final long STATUS_LIFETIME_TICKS = 40;
    private FireControlStatus fireControlStatus = FireControlStatus.NONE;
    private long fireControlStatusTick;

    public RangefinderBearingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(3);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // Create's value panel only covers 0..max, so train over a full circle
        // with non-negative values; training still takes the shortest way round.
        manualTraining = new ScrollValueBehaviour(
                Component.translatable("createdangerclose.rangefinder_bearing.manual_training"),
                this, getMovementModeSlot())
                .between(0, 359)
                .withFormatter(v -> v + "°")
                .withCallback(v -> operatorTarget = null);
        behaviours.add(manualTraining);
    }

    // --- Assembly ---

    public void toggleAssembly() {
        if (running) {
            disassemble();
        } else {
            assemble();
        }
    }

    public void assemble() {
        if (level == null || !(getBlockState().getBlock() instanceof RangefinderBearingBlock))
            return;

        // Yaw only: standing on a deck (up) or hanging under a hull (down).
        // Either way the tube turns about the world vertical axis.
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

    /**
     * Where the tube should point, as a contraption angle: the operator's
     * heading if one is (or was) seated, else the manual training value.
     */
    public float getTargetAngle() {
        return operatorTarget != null ? operatorTarget : manualTraining.getValue();
    }

    /** The player seated at the eyepiece, if any. */
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
     * Contraption angle that points the lenses along the operator's yaw.
     *
     * <p>Create rotates a contraption about Y as x' = x·cos a + z·sin a,
     * z' = z·cos a − x·sin a, which lowers a direction's Minecraft yaw by a.
     * So lenses whose local yaw is ψ₀ look along yaw ψ at a = ψ₀ − ψ. The
     * operator's yaw is taken as ship-relative, matching the contraption's
     * frame (Sable carries a rider's view with the ship).
     */
    private static float angleForOperator(RangefinderContraption contraption, Player operator) {
        Direction lensFacing = contraption.getLensFacing();
        float localYaw = lensFacing != null ? lensFacing.toYRot() : 0;
        return Mth.wrapDegrees(localYaw - operator.getYRot());
    }

    /** Reticle lamp brightness, 0 (unlit) to 15. */
    public int getIllumination() {
        return illumination;
    }

    @Override
    public void tick() {
        super.tick();
        prevAngle = angle;

        if (!level.isClientSide) {
            int signal = level.getBestNeighborSignal(worldPosition);
            if (signal != illumination) {
                illumination = signal;
                sendData();
            }
            if (fireControlStatus != FireControlStatus.NONE
                    && level.getGameTime() - fireControlStatusTick > STATUS_LIFETIME_TICKS) {
                fireControlStatus = FireControlStatus.NONE;
                sendData();
            }
        }

        if (!running)
            return;

        Player operator = getOperator();
        if (operator != null && movedContraption.getContraption() instanceof RangefinderContraption rc)
            operatorTarget = angleForOperator(rc, operator);

        if (movedContraption == null || !movedContraption.isStalled()) {
            stepTraining();
        } else {
            angularVelocity = 0;
        }

        applyRotation();

        if (!level.isClientSide) {
            tickRanging(operator);
            if (movedContraption != null && movedContraption.getContraption() instanceof RangefinderContraption rc)
                RangefinderDamage.checkProjectiles(this, movedContraption, rc);
        }
    }

    // --- Damage ---

    @Override
    public void initialize() {
        super.initialize();
        if (level != null && !level.isClientSide)
            RangefinderDamage.track(this);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        RangefinderDamage.untrack(this);
    }

    /** Cracks a lens of the assembled tube (local position), from blast or a hit. */
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
    }

    /** Whether the tube has caught up with where it is being trained. */
    public boolean isOnTarget() {
        return Math.abs(AngleHelper.getShortestAngleDiff(angle, getTargetAngle())) < ON_TARGET_DEG;
    }

    // --- Ranging ---

    /** From the operator's client: start (RMB pressed) or stop (released) ranging. */
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

        // Readouts only stand while the images stay converged; once they
        // drift apart, a fresh reading is needed. (The tracked target is kept
        // for fire control.)
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
            takeSample(sight);
        }
    }

    /** Readings come at the battle network's pace, like its other devices. */
    private int sampleInterval() {
        float rpm = Math.abs(getSpeed());
        return rpm < 1 ? Integer.MAX_VALUE : Math.max(1, Math.round(640f / rpm));
    }

    private void takeSample(RangefinderOptics.Sight sight) {
        if (!steadiness.canRange()
                || movedContraption == null
                || !(movedContraption.getContraption() instanceof RangefinderContraption tube)
                || tube.getBaseline() <= 0)
            return;

        Vec3 end = sight.origin().add(sight.direction().scale(MAX_RANGE));
        BlockHitResult hit = level.clip(new ClipContext(sight.origin(), end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS)
            return;

        // Sable reports hits on ships in ship coordinates.
        double trueRange = RangefinderOptics.toWorld(level, hit.getLocation()).distanceTo(sight.origin());
        double convergence = 1 + (UNMERGED_ERROR_FACTOR - 1)
                * steadiness.getSeparation() / RangefinderSteadiness.RANGING_SEPARATION;
        double sigma = trueRange * trueRange * SIGMA_THETA_RAD / tube.getBaseline() * convergence
                * Math.pow(CRACKED_LENS_ERROR_FACTOR, tube.getCrackedLensCount());
        double measuredRange = Math.max(1, trueRange + level.random.nextGaussian() * sigma);

        tracker.update(sight.origin().add(sight.direction().scale(measuredRange)), level.getGameTime(), sigma);
        targetPosition = tracker.getPosition();
        solutionOrigin = sight.origin();
        solutionTick = level.getGameTime();
        solutionSigma = sigma;
        solutionOnShip = Sable.HELPER.getContaining(level, hit.getLocation()) != null;
        Vec3 toTarget = targetPosition.subtract(sight.origin());
        hasRange = true;
        range = (float) toTarget.length();

        // A new track (new target, or after a pause) has no velocity yet;
        // don't lead it with the previous target's.
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

    /** Everything: view state and the target solution. */
    private void clearRanging() {
        clearOperatorState();
        tracker.reset();
        targetPosition = targetVelocity = solutionOrigin = null;
    }

    /** The operator's view state; the target solution is kept. */
    private void clearOperatorState() {
        steadiness.reset();
        rangingActive = pendingSample = false;
        hasRange = hasVelocity = false;
        range = rangeRate = bearingRate = 0;
    }

    /** Called by the fire control solver on every poll. */
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

    /**
     * The target solution as a battle network reading, while it is fresh.
     * Rangefinder readings outrank navigation tables in the fire control solver.
     */
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

    /** Tracked target position, world space (server only). */
    @Nullable
    public Vec3 getTargetPosition() { return targetPosition; }

    /** Tracked target velocity, world space, blocks per tick (server only). */
    @Nullable
    public Vec3 getTargetVelocity() { return targetVelocity; }

    /**
     * Trapezoidal motion towards the target: accelerate at a fixed rate up to
     * the top speed, but never faster than we can still brake to a stop at
     * the target. Top speed and acceleration both scale with network RPM.
     */
    private void stepTraining() {
        float maxSpeed = Math.abs(getSpeed()) * DEG_PER_TICK_PER_RPM;
        if (level.isClientSide)
            maxSpeed *= ServerSpeedProvider.get();
        if (maxSpeed <= 0) {
            // No rotation, no traverse (and nothing to brake with).
            angularVelocity = 0;
            return;
        }
        float acceleration = maxSpeed / ACCELERATION_TICKS;

        float remaining = AngleHelper.getShortestAngleDiff(angle, getTargetAngle());
        float brakingSpeed = (float) Math.sqrt(2 * acceleration * Math.abs(remaining));
        float desired = Math.copySign(Math.min(maxSpeed, brakingSpeed), remaining);
        angularVelocity += Mth.clamp(desired - angularVelocity, -acceleration, acceleration);

        // Arrive exactly, without overshooting past the target.
        boolean towardsTarget = Math.signum(angularVelocity) == Math.signum(remaining);
        if (towardsTarget && Math.abs(angularVelocity) >= Math.abs(remaining)) {
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
        // Renderers pass (partialTicks - 1), i.e. -1 = last tick, 0 = this tick.
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

    public boolean isRunning() {
        return running;
    }

    @Nullable
    public ControlledContraptionEntity getMovedContraption() {
        return movedContraption;
    }

    // --- NBT ---

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.putBoolean("Running", running);
        compound.putFloat("Angle", angle);
        compound.putFloat("AngularVelocity", angularVelocity);
        compound.putInt("Illumination", illumination);
        compound.putBoolean("HasRange", hasRange);
        compound.putFloat("Range", range);
        compound.putBoolean("HasVelocity", hasVelocity);
        compound.putFloat("RangeRate", rangeRate);
        compound.putFloat("BearingRate", bearingRate);
        compound.putInt("FireControlStatus", fireControlStatus.ordinal());
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
        hasRange = compound.getBoolean("HasRange");
        range = compound.getFloat("Range");
        hasVelocity = compound.getBoolean("HasVelocity");
        rangeRate = compound.getFloat("RangeRate");
        bearingRate = compound.getFloat("BearingRate");
        FireControlStatus[] statuses = FireControlStatus.values();
        int statusOrdinal = compound.getInt("FireControlStatus");
        fireControlStatus = statusOrdinal >= 0 && statusOrdinal < statuses.length
                ? statuses[statusOrdinal] : FireControlStatus.NONE;
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
