package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.foundation.block.IBE;
import io.github.daniel366cobra.createdangerclose.block.HorizontalFacedBlock;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.createmod.catnip.math.VoxelShaper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Deck controls left to right, seen from the front: fuze knob, trajectory lever, fire button. */
public class FireMissionConsoleBlock extends HorizontalFacedBlock implements IBE<FireMissionConsoleBlockEntity> {

    public static final MapCodec<FireMissionConsoleBlock> CODEC = simpleCodec(FireMissionConsoleBlock::new);

    // The desk and its backboard (rising into the space above, like a fence), facing north.
    private static final VoxelShaper SHAPE = VoxelShaper.forHorizontal(Shapes.or(
            Block.box(0, 0, 0, 16, 16, 16),
            Block.box(0, 16, 12, 16, 20, 16)), Direction.NORTH);

    /** Left to right from in front. */
    public enum Control { FUZE_KNOB, TRAJECTORY_LEVER, FIRE_BUTTON }

    public FireMissionConsoleBlock(Properties properties) {
        super(properties);
    }

    /** Under a point on the deck's top: thirds across from in front. Null elsewhere. */
    @Nullable
    public static Control controlAt(BlockState state, BlockPos pos, BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP || !(state.getBlock() instanceof FireMissionConsoleBlock))
            return null;
        Direction playersRight = state.getValue(FACING).getCounterClockWise();
        Vec3 fromCenter = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        double across = fromCenter.x * playersRight.getStepX() + fromCenter.z * playersRight.getStepZ() + 0.5;
        if (across < 1 / 3.0)
            return Control.FUZE_KNOB;
        return across < 2 / 3.0 ? Control.TRAJECTORY_LEVER : Control.FIRE_BUTTON;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        Control control = controlAt(state, pos, hitResult);
        if (control == null)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        FireMissionConsoleBlockEntity console = getBlockEntity(level, pos);
        if (console == null)
            return InteractionResult.SUCCESS;

        if (control == Control.FUZE_KNOB) {
            console.setFuzeOffset(0);
            level.playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, 0.3f, 1.2f);
            return InteractionResult.SUCCESS;
        }

        BallisticComputerBlockEntity computer = console.getComputer();
        if (!console.isLinked()) {
            status(player, "not_connected");
            return InteractionResult.SUCCESS;
        }
        if (computer == null) {
            status(player, "no_computer");
            return InteractionResult.SUCCESS;
        }

        if (control == Control.FIRE_BUTTON) {
            pressFireButton(level, pos, player, console, computer);
        } else {
            throwLever(level, pos, player, console, computer);
        }
        console.refreshFromComputer();
        return InteractionResult.SUCCESS;
    }

    /** A salvo from every big cannon on target. */
    protected void pressFireButton(Level level, BlockPos pos, Player player, FireMissionConsoleBlockEntity console,
                                   BallisticComputerBlockEntity computer) {
        level.blockEvent(pos, this, FireMissionConsoleBlockEntity.BUTTON_PRESS_EVENT, 0);
        BallisticComputerBlockEntity.SalvoResult salvo = computer.fireSalvo(player);
        level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.6f, 1f);
        if (salvo.awaitingConfirmation()) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1f, 0.5f);
            boolean safeZone = computer.dangerOnTarget(false) == BallisticComputerBlockEntity.Danger.SAFE_ZONE;
            player.displayClientMessage(Component.translatable(safeZone
                            ? "createdangerclose.fire_mission_console.safe_zone"
                            : "createdangerclose.fire_mission_console.danger_close",
                    Math.round(CDCConfig.server().dangerClose.confirmSeconds.get())).withStyle(ChatFormatting.RED), true);
        } else if (salvo.fired() + salvo.held() == 0)
            status(player, "no_guns");
        else if (!salvo.heldNames().isEmpty())
            player.displayClientMessage(Component.translatable("createdangerclose.fire_mission_console.salvo_named",
                    salvo.fired(), salvo.held(), salvo.heldNames()), true);
        else
            player.displayClientMessage(Component.translatable("createdangerclose.fire_mission_console.salvo",
                    salvo.fired(), salvo.held()), true);
    }

    /** For the whole computer. */
    protected void throwLever(Level level, BlockPos pos, Player player, FireMissionConsoleBlockEntity console,
                              BallisticComputerBlockEntity computer) {
        boolean plunging = !computer.isPlungingFire();
        computer.setPlungingFire(plunging);
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, plunging ? 0.6f : 0.5f);
        status(player, plunging ? "mode.plunging" : "mode.direct");
    }

    /** Reach the block entity, and go out to clients from the server. */
    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        FireMissionConsoleBlockEntity console = getBlockEntity(level, pos);
        return console != null && console.triggerEvent(id, param);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
    }

    protected static void status(Player player, String key) {
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