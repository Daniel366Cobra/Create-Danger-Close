package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairOutputBlock;
import io.github.daniel366cobra.createdangerclose.client.ServoLimitsScreen;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoAxis;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The Cannon Servo's output half, driving the gun's own network. */
public class CannonServoOutputBlock extends PairOutputBlock<CannonServoOutputBlockEntity> {

    public CannonServoOutputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected Block inputBlock() {
        return CDCBlocks.CANNON_SERVO_INPUT.get();
    }

    /** Right-click with an empty hand: the protractor dial, to set the limits it may turn through. Not on the shaft side, where shafts go. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (hasShaftTowards(level, pos, state, hit.getDirection())
                || !(level.getBlockEntity(pos) instanceof CannonServoOutputBlockEntity servo))
            return InteractionResult.PASS;
        ServoAxis axis = servo.getDetectedAxis();
        if (axis != ServoAxis.PITCH && axis != ServoAxis.YAW && axis != ServoAxis.SWIVEL)
            return InteractionResult.PASS;
        // Through the client-only executor, so the screen class is only ever reached on a client.
        if (level.isClientSide)
            CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> ServoLimitsScreen.open(servo));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public BlockEntityType<? extends CannonServoOutputBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.CANNON_SERVO_OUTPUT.get();
    }

    @Override
    public Class<CannonServoOutputBlockEntity> getBlockEntityClass() {
        return CannonServoOutputBlockEntity.class;
    }
}