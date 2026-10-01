package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.block.fire_control_solver.FireControlSolverBlockEntity;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Fire Mission Console. Its top carries the controls, seen from the
 * player standing at its face: the left half is the trajectory switch (direct
 * or plunging fire), the right half the fire button. A Battle Network
 * Interface facing it connects it to the Ballistic Computer.
 */
public class FireMissionConsoleBlock extends HorizontalFacedBlock implements IBE<FireMissionConsoleBlockEntity> {

    public static final MapCodec<FireMissionConsoleBlock> CODEC = simpleCodec(FireMissionConsoleBlock::new);

    public FireMissionConsoleBlock(Properties properties) {
        super(properties);
    }

    /** Clicks on the top: left half the trajectory switch, right half the fire button. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (hitResult.getDirection() != Direction.UP)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        boolean fireButton = isOnFireButtonSide(state, pos, hitResult.getLocation());
        FireMissionConsoleBlockEntity console = getBlockEntity(level, pos);
        FireControlSolverBlockEntity computer = console != null ? console.getComputer() : null;
        if (console == null || !console.isLinked()) {
            status(player, "not_connected");
            return InteractionResult.SUCCESS;
        }
        if (computer == null) {
            status(player, "no_computer");
            return InteractionResult.SUCCESS;
        }

        if (fireButton) {
            FireControlSolverBlockEntity.SalvoResult salvo = computer.fireSalvo();
            level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.6f, 1f);
            if (salvo.fired() + salvo.held() == 0)
                status(player, "no_guns");
            else
                player.displayClientMessage(Component.translatable("createdangerclose.fire_mission_console.salvo",
                        salvo.fired(), salvo.held()), true);
        } else {
            boolean plunging = !computer.isPlungingFire();
            computer.setPlungingFire(plunging);
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, plunging ? 0.6f : 0.5f);
            status(player, plunging ? "mode.plunging" : "mode.direct");
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether a point on the top lies on the fire button's half: the right
     * half, as seen by a player standing at the console's face (looking the
     * opposite way to it).
     */
    private static boolean isOnFireButtonSide(BlockState state, BlockPos pos, Vec3 hit) {
        Direction playersRight = state.getValue(FACING).getCounterClockWise();
        Vec3 fromCenter = hit.subtract(Vec3.atCenterOf(pos));
        return fromCenter.x * playersRight.getStepX() + fromCenter.z * playersRight.getStepZ() > 0;
    }

    private static void status(Player player, String key) {
        player.displayClientMessage(Component.translatable("createdangerclose.fire_mission_console." + key), true);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public Class<FireMissionConsoleBlockEntity> getBlockEntityClass() {
        return FireMissionConsoleBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FireMissionConsoleBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.FIRE_MISSION_CONSOLE.get();
    }
}
