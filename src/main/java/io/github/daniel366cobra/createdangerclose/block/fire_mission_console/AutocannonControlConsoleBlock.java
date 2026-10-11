package io.github.daniel366cobra.createdangerclose.block.fire_mission_console;

import com.mojang.serialization.MapCodec;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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

/** The Fire Mission Console's desk with fuze knob, auto switch and trigger. */
public class AutocannonControlConsoleBlock extends FireMissionConsoleBlock {

    public static final MapCodec<AutocannonControlConsoleBlock> CODEC = simpleCodec(AutocannonControlConsoleBlock::new);

    public AutocannonControlConsoleBlock(Properties properties) {
        super(properties);
    }

    /** Held, not clicked: CONSUME on both sides so the hand does not swing. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        InteractionResult result = super.useWithoutItem(state, level, pos, player, hitResult);
        return result == InteractionResult.SUCCESS && controlAt(state, pos, hitResult) == Control.FIRE_BUTTON
                ? InteractionResult.CONSUME : result;
    }

    /** Fires while held (right-click repeats). */
    @Override
    protected void pressFireButton(Level level, BlockPos pos, Player player, FireMissionConsoleBlockEntity console,
                                   BallisticComputerBlockEntity computer) {
        if (!(console instanceof AutocannonControlConsoleBlockEntity autocannons))
            return;
        level.blockEvent(pos, this, FireMissionConsoleBlockEntity.BUTTON_PRESS_EVENT, 0);
        AutocannonControlConsoleBlockEntity.Pull pull = autocannons.pullTrigger(computer);
        if (pull == AutocannonControlConsoleBlockEntity.Pull.HELD)
            return;
        level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.6f, 1f);
        BallisticComputerBlockEntity.SalvoResult guns = computer.autocannonsOnTarget();
        if (pull == AutocannonControlConsoleBlockEntity.Pull.DANGER_CLOSE_WARNING) {
            level.playSound(null, pos, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 1f, 0.5f);
            boolean safeZone = computer.dangerOnTarget(true) == BallisticComputerBlockEntity.Danger.SAFE_ZONE;
            player.displayClientMessage(Component.translatable(safeZone
                            ? "createdangerclose.autocannon_control_console.safe_zone"
                            : "createdangerclose.autocannon_control_console.danger_close",
                    Math.round(CDCConfig.server().dangerClose.confirmSeconds.get())).withStyle(ChatFormatting.RED), true);
        } else if (guns.fired() + guns.held() == 0)
            message(player, "no_autocannons");
    }

    /** Weapons free, or trigger only. */
    @Override
    protected void throwLever(Level level, BlockPos pos, Player player, FireMissionConsoleBlockEntity console,
                              BallisticComputerBlockEntity computer) {
        if (!(console instanceof AutocannonControlConsoleBlockEntity autocannons))
            return;
        autocannons.toggleWeaponsFree();
        boolean free = autocannons.isWeaponsFree();
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5f, free ? 0.7f : 0.5f);
        message(player, free ? "mode.auto" : "mode.manual");
    }

    private static void message(Player player, String key) {
        player.displayClientMessage(Component.translatable("createdangerclose.autocannon_control_console." + key), true);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntityType<? extends FireMissionConsoleBlockEntity> getBlockEntityType() {
        return CDCBlockEntities.AUTOCANNON_CONTROL_CONSOLE.get();
    }
}