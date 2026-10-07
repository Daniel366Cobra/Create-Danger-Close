package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * The input half of a two-block kinetic pair (Cannon Servo, Kinetic Network Coupler): an
 * ordinary consumer on the input network, charging it whatever the output half's own
 * network draws. The output half sets the charge (see {@link PairOutputBlockEntity}).
 */
public abstract class PairInputBlockEntity extends KineticBlockEntity {

    // What it charges the input network, per RPM.
    private float drawImpact;

    protected PairInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Charges the input network this much per RPM from now on (server). */
    public void setDrawImpact(float impact) {
        // Against what the network has on record for us too: after a reload it may hold an older figure,
        // and an unchanged draw would never correct it.
        Float recorded = hasNetwork() ? getOrCreateNetwork().members.get(this) : null;
        if (Math.abs(impact - drawImpact) <= Math.max(0.01f, drawImpact * 0.02f) && (impact == 0) == (drawImpact == 0)
                && (recorded == null || Math.abs(recorded - impact) <= Math.max(0.01f, impact * 0.02f)))
            return;
        drawImpact = impact;
        // Create subtracts this, as saved, from the network's saved stress on reload: stale, it would
        // leave the difference behind as phantom stress for good.
        lastStressApplied = impact;
        if (hasNetwork())
            getOrCreateNetwork().updateStressFor(this, impact);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = drawImpact;
        return drawImpact;
    }

    /** What the input network can carry in all, and carries now (su). */
    public float getNetworkCapacity() {
        return capacity;
    }

    public float getNetworkStress() {
        return stress;
    }

    /** What this pair draws from the input network now (su). */
    public float getDraw() {
        return drawImpact * Math.abs(getSpeed());
    }

    /** Whether its output half is working now, for sharing the input network's spare power. */
    public boolean isPassingPower() {
        PairOutputBlockEntity output = getPairedOutput();
        return output != null && output.isPassingPower();
    }

    /** The output half in front of it, if any. */
    @Nullable
    public PairOutputBlockEntity getPairedOutput() {
        if (level == null)
            return null;
        BlockPos outputPos = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(outputPos) instanceof PairOutputBlockEntity output ? output : null;
    }

    // Saved with the network's stress, which counts it: reloaded without it, the network's
    // total would charge the input network for a draw the pair no longer accounts for.
    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putFloat("DrawImpact", drawImpact);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        drawImpact = compound.getFloat("DrawImpact");
    }
}
