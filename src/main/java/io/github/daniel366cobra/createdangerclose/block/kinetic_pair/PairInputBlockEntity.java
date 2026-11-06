package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/** The input half of a kinetic pair: a consumer charging its network what the output half draws. */
public abstract class PairInputBlockEntity extends KineticBlockEntity {

    // Per RPM.
    private float drawImpact;

    protected PairInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Per RPM, server. */
    public void setDrawImpact(float impact) {
        // After a load, wait until Create has re-added this block: it subtracts the saved draw from the
        // network's unloaded stress only for blocks not yet members. Joining early would skip that and
        // leave the saved draw as phantom stress, saved again and growing with every reload.
        if (!isNetworkMember())
            return;
        // After a reload the network may hold an older figure that an unchanged draw would never correct.
        Float recorded = hasNetwork() ? getOrCreateNetwork().members.get(this) : null;
        if (Math.abs(impact - drawImpact) <= Math.max(0.01f, drawImpact * 0.02f) && (impact == 0) == (drawImpact == 0)
                && (recorded == null || Math.abs(recorded - impact) <= Math.max(0.01f, impact * 0.02f)))
            return;
        drawImpact = impact;
        // Create subtracts this on reload: stale, it would leave phantom stress.
        lastStressApplied = impact;
        if (hasNetwork())
            getOrCreateNetwork().updateStressFor(this, impact);
    }

    // Joined its saved network (Create's initialize, or ours in removeSource).
    private boolean joinedSavedNetwork;
    // Create keeps its own copy private.
    private int savedNetworkSize;

    @Override
    public void initialize() {
        super.initialize();
        joinedSavedNetwork = true;
    }

    /**
     * A clutch toggled while the world loads (as by a Redstone Link) cuts this off before it has joined:
     * Create would never subtract its saved draw from the network's stress, and that phantom stress is
     * saved again with every reload. So it joins first, as Create's initialize would, while its speed is
     * still the saved one, and then leaves properly.
     */
    @Override
    public void removeSource() {
        if (!joinedSavedNetwork && hasNetwork() && level != null && !level.isClientSide) {
            joinedSavedNetwork = true;
            KineticNetwork network = getOrCreateNetwork();
            if (!network.initialized)
                network.initFromTE(capacity, stress, savedNetworkSize);
            network.addSilently(this, lastCapacityProvided, lastStressApplied);
        }
        super.removeSource();
    }

    /** Not on a network, or already counted in it. */
    protected boolean isNetworkMember() {
        return !hasNetwork() || getOrCreateNetwork().members.containsKey(this);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = drawImpact;
        return drawImpact;
    }

    /** su. */
    public float getNetworkCapacity() {
        return capacity;
    }

    public float getNetworkStress() {
        return stress;
    }

    /** su. */
    public float getDraw() {
        return drawImpact * Math.abs(getSpeed());
    }

    /** For sharing the input's spare power. */
    public boolean isPassingPower() {
        PairOutputBlockEntity output = getPairedOutput();
        return output != null && output.isPassingPower();
    }

    @Nullable
    public PairOutputBlockEntity getPairedOutput() {
        if (level == null)
            return null;
        BlockPos outputPos = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(outputPos) instanceof PairOutputBlockEntity output ? output : null;
    }

    // Saved with the network's stress, else it would be charged for a draw no longer accounted for.
    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("DrawImpact", drawImpact);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        drawImpact = compound.getFloat("DrawImpact");
        if (!clientPacket)
            savedNetworkSize = compound.getCompound("Network").getInt("Size");
    }
}