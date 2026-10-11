package io.github.daniel366cobra.createdangerclose.block.cannon_servo;

import io.github.daniel366cobra.createdangerclose.block.kinetic_pair.PairInputBlock;
import io.github.daniel366cobra.createdangerclose.client.ServoNameScreen;
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

/** The Cannon Servo: placed by its input half, on the battle network. */
public class CannonServoInputBlock extends PairInputBlock<CannonServoInputBlockEntity> {

    public CannonServoInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected Block outputBlock() {
        return CDCBlocks.CANNON_SERVO_OUTPUT.get();
    }

    /** Right-click with an empty hand: name the guns it lays, for the consoles. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof CannonServoInputBlockEntity servo))
            return InteractionResult.PASS;
        // Through the client-only executor, so the screen class is only ever reached on a client.
        if (level.isClientSide)
            CatnipServices.PLATFORM.executeOnClientOnly(() -> () -> ServoNameScreen.open(servo));
        return InteractionResult.sidedSuccess(level.isClientSide);
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