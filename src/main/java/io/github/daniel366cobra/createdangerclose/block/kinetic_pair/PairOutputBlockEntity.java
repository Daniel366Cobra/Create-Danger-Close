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
 * The output half of a two-block kinetic pair: a source turning a network of its own,
 * paid for by the input half's network. Every pair on an input network gets an equal share
 * of its spare power: its capacity, less a margin, less everything but the pairs working now.
 * The subclass decides how fast to run on that share, and what to offer its own network.
 */
public abstract class PairOutputBlockEntity extends GeneratingKineticBlockEntity {

    // The share of the input network's capacity never claimed, and how much a speed
    // limit may rise per update (it drops at once).
    protected static final float POWER_MARGIN = 0.1f;
    protected static final float POWER_RAMP_UP = 1.25f;
    // Overstressed below the operating speed; back only with this much to spare over it.
    protected static final float RESUME_FACTOR = 1.1f;
    // Overstressed, it offers no more than this share of the load.
    protected static final float UNDER_OFFER = 0.99f;
    // Capacity offered over the load (so rounding never overstresses it), and at the least.
    protected static final float CAPACITY_HEADROOM = 1.1f, MIN_CAPACITY = 0.5f;

    // What it offers its own network, per RPM.
    private float outputCapacity = MIN_CAPACITY;

    protected PairOutputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Whether it is working now, for sharing the input network's spare power. */
    public abstract boolean isPassingPower();

    /**
     * This pair's share of the input network's spare power (su): its capacity less the
     * margin, less everything but the pairs working now (this one counted as working),
     * split equally among those. Servos and couplers on one network share alike.
     */
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

    /** A speed limit that drops at once to what is affordable, and rises towards it gently. */
    protected static float rampedLimit(float limit, float affordable) {
        return Math.min(affordable, limit >= Float.MAX_VALUE / 2 ? affordable : limit * POWER_RAMP_UP + 1);
    }

    /** Offers its own network this capacity per RPM, from now on (server). */
    protected void offerCapacity(float capacity) {
        // Against what the network has on record for us too: it may have been rebuilt with an older figure.
        Float recorded = hasNetwork() ? getOrCreateNetwork().sources.get(this) : null;
        if (Math.abs(capacity - outputCapacity) <= Math.max(0.01f, outputCapacity * 0.02f)
                && (recorded == null || Math.abs(recorded - capacity) <= Math.max(0.01f, capacity * 0.02f)))
            return;
        outputCapacity = capacity;
        // Create subtracts this, as saved, from the network's saved capacity on reload: stale, it
        // would leave the difference behind as phantom capacity for good.
        lastCapacityProvided = capacity;
        if (hasNetwork())
            getOrCreateNetwork().updateCapacityFor(this, capacity);
    }

    @Override
    public float calculateAddedStressCapacity() {
        lastCapacityProvided = outputCapacity;
        return outputCapacity;
    }

    /** The input half behind it, if any. */
    @Nullable
    public PairInputBlockEntity getPairedInput() {
        if (level == null)
            return null;
        BlockPos inputPos = worldPosition.relative(getBlockState().getValue(FACING).getOpposite());
        return level.getBlockEntity(inputPos) instanceof PairInputBlockEntity input ? input : null;
    }

    // What it offers its own network, saved so the network is rebuilt with it on load (else it
    // is rebuilt before anything is measured, short of the load).
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
