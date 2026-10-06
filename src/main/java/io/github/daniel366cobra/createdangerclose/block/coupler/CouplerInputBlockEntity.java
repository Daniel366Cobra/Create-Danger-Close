package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * The Kinetic Network Coupler's input side: an ordinary consumer there, charging
 * whatever the output network behind it draws (set by the output half, see
 * {@link CouplerOutputBlockEntity}).
 */
public class CouplerInputBlockEntity extends KineticBlockEntity {

    // What it charges the input side, per RPM.
    private float drawImpact;

    public CouplerInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Charges the input side this much per RPM from now on (server). */
    void setDrawImpact(float impact) {
        if (Math.abs(impact - drawImpact) <= Math.max(0.01f, drawImpact * 0.02f) && (impact == 0) == (drawImpact == 0))
            return;
        drawImpact = impact;
        if (hasNetwork())
            getOrCreateNetwork().updateStressFor(this, impact);
    }

    @Override
    public float calculateStressApplied() {
        lastStressApplied = drawImpact;
        return drawImpact;
    }

    /** What the input side can carry in all, and carries now (su). */
    float getNetworkCapacity() {
        return capacity;
    }

    float getNetworkStress() {
        return stress;
    }

    /** What this coupler draws from the input side now (su). */
    float getDraw() {
        return drawImpact * Math.abs(getSpeed());
    }

    /** Whether this coupler is passing power on (for sharing the input side's spare). */
    boolean isPassingPower() {
        CouplerOutputBlockEntity output = getAdjacentOutput();
        return output != null && output.isPassingPower();
    }

    @Nullable
    public CouplerOutputBlockEntity getAdjacentOutput() {
        if (level == null)
            return null;
        BlockPos outputPos = worldPosition.relative(getBlockState().getValue(FACING));
        return level.getBlockEntity(outputPos) instanceof CouplerOutputBlockEntity output ? output : null;
    }
}
