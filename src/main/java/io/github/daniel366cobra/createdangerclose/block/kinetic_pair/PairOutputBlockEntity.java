package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * The output half of a kinetic pair: a source for its own network, paid for by the input network.
 * Each working pair gets an equal share of the input's spare power ({@link #fairShare}).
 */
public abstract class PairOutputBlockEntity extends GeneratingKineticBlockEntity {

    // Capacity never claimed, and how much a limit may rise per update (it drops at once).
    protected static final float POWER_MARGIN = 0.1f;
    protected static final float POWER_RAMP_UP = 1.25f;
    // Resume only with this much to spare.
    protected static final float RESUME_FACTOR = 1.1f;
    // Overstressed, it offers at most this share of the load.
    protected static final float UNDER_OFFER = 0.99f;
    // Over the load (so rounding never overstresses), and the floor.
    protected static final float CAPACITY_HEADROOM = 1.1f, MIN_CAPACITY = 0.5f;

    // Per RPM.
    private float outputCapacity = MIN_CAPACITY;

    protected PairOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Working now, for sharing the input's spare power. */
    public abstract boolean isPassingPower();

    /** Capacity less the margin and everything but the working pairs (this one included), split equally among them, su. */
    protected static float fairShare(PairInputBlockEntity input) {
        KineticNetwork network = input.getOrCreateNetwork();
        int working = 0;
        float workingDraw = 0;
        for (KineticBlockEntity member : network.members.keySet())
            if (member instanceof PairInputBlockEntity pair && (pair == input || pair.isPassingPower())) {
                working++;
                workingDraw += pair.getDraw();
            }
        float otherLoad = input.getNetworkStress() - workingDraw;
        return Math.max(0, (input.getNetworkCapacity() * (1 - POWER_MARGIN) - otherLoad) / Math.max(1, working));
    }

    /** Drops at once, rises gently. */
    protected static float rampedLimit(float limit, float affordable) {
        return Math.min(affordable, limit >= Float.MAX_VALUE / 2 ? affordable : limit * POWER_RAMP_UP + 1);
    }

    /** Per RPM, server. */
    protected void offerCapacity(float capacity) {
        // Likewise for capacity (see PairInputBlockEntity#setDrawImpact): not before Create re-adds it.
        if (hasNetwork() && !getOrCreateNetwork().members.containsKey(this))
            return;
        // The network may have been rebuilt with an older figure.
        Float recorded = hasNetwork() ? getOrCreateNetwork().sources.get(this) : null;
        if (Math.abs(capacity - outputCapacity) <= Math.max(0.01f, outputCapacity * 0.02f)
                && (recorded == null || Math.abs(recorded - capacity) <= Math.max(0.01f, capacity * 0.02f)))
            return;
        outputCapacity = capacity;
        // Create subtracts this on reload: stale, it would leave phantom capacity.
        lastCapacityProvided = capacity;
        if (hasNetwork())
            getOrCreateNetwork().updateCapacityFor(this, capacity);
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = outputCapacity;
        return outputCapacity;
    }

    @Nullable
    public PairInputBlockEntity getPairedInput() {
        if (level == null)
            return null;
        BlockPos inputPos = worldPosition.relative(getBlockState().getValue(FACING).getOpposite());
        return level.getBlockEntity(inputPos) instanceof PairInputBlockEntity input ? input : null;
    }

    // Saved, so the network is rebuilt with it rather than short of the load.
    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("OutputCapacity", outputCapacity);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        outputCapacity = compound.contains("OutputCapacity") ? compound.getFloat("OutputCapacity") : MIN_CAPACITY;
    }
}