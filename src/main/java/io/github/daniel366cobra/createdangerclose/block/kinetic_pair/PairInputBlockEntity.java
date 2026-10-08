package io.github.daniel366cobra.createdangerclose.block.kinetic_pair;

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
    }
}