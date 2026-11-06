package io.github.daniel366cobra.createdangerclose.block.munitions_chute;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** The arm takes the staged round straight from the chute, which offers no inventory to anything else. */
public class MunitionsChuteArmPoint extends ArmInteractionPoint {

    public MunitionsChuteArmPoint(ArmInteractionPointType type, Level level, BlockPos pos, BlockState state) {
        super(type, level, pos, state);
        mode = Mode.TAKE;
    }

    // Take only.
    @Override
    public Mode getMode() {
        return Mode.TAKE;
    }

    @Override
    public void cycleMode() {}

    @Nullable
    @Override
    protected IItemHandler getHandler(ArmBlockEntity armBlockEntity) {
        return level.getBlockEntity(pos) instanceof MunitionsChuteBlockEntity chute ? chute.getArmView() : null;
    }

    public static class Type extends ArmInteractionPointType {
        @Override
        public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
            return CDCBlocks.MUNITIONS_CHUTE.has(state);
        }

        @Override
        public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
            return new MunitionsChuteArmPoint(this, level, pos, state);
        }
    }
}
