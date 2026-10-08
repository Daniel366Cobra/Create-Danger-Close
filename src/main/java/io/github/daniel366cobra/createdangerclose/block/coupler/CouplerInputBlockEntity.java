package io.github.daniel366cobra.createdangerclose.block.coupler;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING;

/** The coupler's input half; its value box (on the brass plates) sets the floor speed. */
public class CouplerInputBlockEntity extends PairInputBlockEntity {

    // Fire control equipment needs 32 RPM to work.
    public static final int DEFAULT_FLOOR_RPM = 32, MAX_FLOOR_RPM = 256;

    private ScrollValueBehaviour floorSpeed;

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

    /** RPM. */
    int getFloorRpm() {
        return floorSpeed.getValue();
    }

    /** Centered on each side around the axis, on the brass plate. */
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
}