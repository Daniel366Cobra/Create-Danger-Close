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
import io.github.daniel366cobra.createdangerclose.CDCLang;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

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
        implements IBearingBlockEntity, IDisplayAssemblyExceptions {

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

    @Override
    public void tick() {
        super.tick();
        prevAngle = angle;

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
    }

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

        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        return true;
    }
}
