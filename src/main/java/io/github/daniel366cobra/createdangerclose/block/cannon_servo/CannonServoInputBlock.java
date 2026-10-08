package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlock;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** The Cannon Servo: placed by its input half, on the battle network. */
public class CannonServoInputBlock extends PairInputBlock<CannonServoInputBlockEntity> {

    public CannonServoInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected Block outputBlock() {
        return CDCBlocks.CANNON_SERVO_OUTPUT.get();
    }

    @Override
    public BlockEntityType<? extends CannonServoInputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.CANNON_SERVO_INPUT.get();
    }

    @Override
    public Class<CannonServoInputBlockEntity> getBlockEntityClass() {
        return CannonServoInputBlockEntity.class;
    }
}