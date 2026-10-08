package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.KineticLoad;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * The coupler's output: turns its own network with the input's rotation, charging the input side what
 * it draws. Short of power it gears down to the floor speed; below that it stays at the floor but
 * offers too little, so Create shows it overstressed, and it resumes by itself. The share comes from
 * {@link PairOutputBlockEntity#fairShare}, offered whole as capacity, charged only as drawn.
 */
public class CouplerOutputBlockEntity extends PairOutputBlockEntity implements IHaveGoggleInformation {

    private static final int POWER_INTERVAL_TICKS = 5;
    // Each change re-propagates the network.
    private static final float SPEED_STEP = 0.5f;

    public enum State { UNPOWERED, RUNNING, GEARED_DOWN, OVERSTRESSED }

    private EquipmentHealth health;
    private boolean initialSpeedApplied;
    // Signed, as the input turns.
    private float outputSpeed;
    // su per RPM (see KineticLoad).
    private float loadImpact;
    private float rpmLimit = Float.MAX_VALUE;
    private float share;
    private State state = State.UNPOWERED;
    // For the goggles (synced), su.
    private float shownDraw, shownShare;

    public CouplerOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // No lubrication, unlike fire control equipment: it serves factories too.
        behaviours.add(health = new EquipmentHealth(this, EquipmentHealth.Breaks.COG, false)
                .withBox(() -> new AABB(worldPosition).minmax(
                        new AABB(worldPosition.relative(getBlockState().getValue(FACING).getOpposite())))));
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (!initialSpeedApplied) {
            initialSpeedApplied = true;
            updateGeneratedRotation();
        }
        if (level.getGameTime() % POWER_INTERVAL_TICKS == 0)
            updatePower();
    }

    private void updatePower() {
        CouplerInputBlockEntity input = getAdjacentInput();
        float inputSpeed = input != null ? input.getSpeed() : 0;

        loadImpact = KineticLoad.perRpmOf(this, loadImpact);

        if (input == null || !input.hasNetwork() || Math.abs(inputSpeed) < 1e-3f || health.isOutOfOrder()) {
            setOutput(0, State.UNPOWERED, input);
            return;
        }

        share = fairShare(input);
        float affordable = loadImpact > 0 ? share / loadImpact : Float.MAX_VALUE;
        rpmLimit = rampedLimit(rpmLimit, affordable);

        float wanted = Math.abs(inputSpeed);
        float minimum = Math.min(wanted, input.getFloorRpm());
        // A margin, so it does not flip back and forth.
        float resumeAt = state == State.OVERSTRESSED ? minimum * RESUME_FACTOR : minimum;
        if (rpmLimit >= wanted && (state != State.OVERSTRESSED || rpmLimit >= resumeAt))
            setOutput(inputSpeed, State.RUNNING, input);
        else if (rpmLimit >= resumeAt)
            setOutput(Math.copySign(rpmLimit, inputSpeed), State.GEARED_DOWN, input);
        else
            // At the floor but offered too little: overstressed, still connected, resumes by itself.
            setOutput(Math.copySign(minimum, inputSpeed), State.OVERSTRESSED, input);
    }

    private void setOutput(float speed, State newState, @Nullable CouplerInputBlockEntity input) {
        boolean stateChanged = newState != state;
        state = newState;
        if (Math.abs(speed - outputSpeed) >= SPEED_STEP || (speed == 0) != (outputSpeed == 0)) {
            outputSpeed = speed;
            updateGeneratedRotation();
        }
        boolean overstressed = state == State.OVERSTRESSED;
        float load = loadImpact * Math.abs(outputSpeed);
        float draw = overstressed ? 0 : load;
        if (input != null)
            input.setDrawImpact(Math.abs(input.getSpeed()) > 1e-3f ? draw / Math.abs(input.getSpeed()) : 0);
        // Offer the whole share (so servos downstream see real spare), never less than the draw.
        float capacity = loadImpact * CAPACITY_HEADROOM + MIN_CAPACITY;
        if (Math.abs(outputSpeed) > 1e-3f)
            capacity = overstressed
                    // Short of the load, to stay overstressed.
                    ? Math.min(share, load * UNDER_OFFER) / Math.abs(outputSpeed)
                    : Math.max(capacity, share / Math.abs(outputSpeed));
        offerCapacity(capacity);
        boolean shownChanged = Math.round(load) != Math.round(shownDraw) || Math.round(share) != Math.round(shownShare);
        shownDraw = load;
        shownShare = share;
        if (stateChanged || shownChanged)
            sendData();
    }

    @Override
    public boolean isPassingPower() {
        return Math.abs(outputSpeed) > 1e-3f;
    }

    @Override
    public float getGeneratedSpeed() {
        return outputSpeed;
    }

    @Nullable
    private CouplerInputBlockEntity getAdjacentInput() {
        return getPairedInput() instanceof CouplerInputBlockEntity input ? input : null;
    }

    // --- Goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        CDCLang.builder()
                .add(Component.translatable("createdangerclose.kinetic_network_coupler.title"))
                .forGoggles(tooltip);
        health.addToGoggleTooltip(tooltip);
        String key = "createdangerclose.kinetic_network_coupler." + state.name().toLowerCase();
        ChatFormatting style = switch (state) {
            case RUNNING -> ChatFormatting.GRAY;
            case GEARED_DOWN -> ChatFormatting.GOLD;
            case OVERSTRESSED -> ChatFormatting.RED;
            case UNPOWERED -> ChatFormatting.DARK_GRAY;
        };
        CDCLang.builder()
                .add(Component.translatable(key, Math.round(Math.abs(outputSpeed))))
                .style(style)
                .forGoggles(tooltip);
        if (state != State.UNPOWERED)
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.equipment.power_balance",
                            Math.round(shownDraw), Math.round(shownShare)))
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip);
        return true;
    }

    // --- Saving ---

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("OutputSpeed", outputSpeed);
        compound.putFloat("LoadImpact", loadImpact);
        compound.putInt("State", state.ordinal());
        compound.putFloat("Draw", shownDraw);
        compound.putFloat("Share", shownShare);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        outputSpeed = compound.getFloat("OutputSpeed");
        loadImpact = compound.getFloat("LoadImpact");
        State[] states = State.values();
        int ordinal = compound.getInt("State");
        state = ordinal >= 0 && ordinal < states.length ? states[ordinal] : State.UNPOWERED;
        shownDraw = compound.getFloat("Draw");
        shownShare = compound.getFloat("Share");
    }
}