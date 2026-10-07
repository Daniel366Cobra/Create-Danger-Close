package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import io.github.daniel366cobra.createdangerclose.CDCLang;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/**
 * The Kinetic Network Coupler's input side: an ordinary consumer there, charging
 * whatever the output network behind it draws (set by the output half, see
 * {@link CouplerOutputBlockEntity}). Its value box, on the brass plate on each side,
 * sets the floor speed: the slowest it gears the output network down to before
 * letting it overstress.
 */
public class CouplerInputBlockEntity extends KineticBlockEntity {

    // Fire control equipment needs 32 RPM to work.
    public static final int DEFAULT_FLOOR_RPM = 32, MAX_FLOOR_RPM = 256;

    private ScrollValueBehaviour floorSpeed;
    // What it charges the input side, per RPM.
    private float drawImpact;

    public CouplerInputBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        floorSpeed = new ScrollValueBehaviour(CDCLang.translate("kinetic_network_coupler.floor_speed").component(),
                this, new SideSlot())
                .between(1, MAX_FLOOR_RPM);
        floorSpeed.value = DEFAULT_FLOOR_RPM;
        behaviours.add(floorSpeed);
    }

    /** The slowest the output network is geared down to before it is left overstressed (RPM). */
    int getFloorRpm() {
        return floorSpeed.getValue();
    }

    /** The value box: centred on each side around the coupler's axis, where the brass plate is. */
    private static class SideSlot extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 15.5);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return direction.getAxis() != state.getValue(FACING).getAxis();
        }
    }

    /** Charges the input side this much per RPM from now on (server). */
    void setDrawImpact(float impact) {
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

    // Saved with the network's stress, which counts it: reloaded without it, the network's
    // total would charge the input side for a draw the coupler no longer accounts for.
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
