package io.github.daniel366cobra.createdangerclose.client;

import com.simibubi.create.AllSoundEvents;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlock;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.network.FireMissionKnobPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Scrolling over a Fire Mission Console's fuze knob turns it, instead of the hotbar. */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID, value = Dist.CLIENT)
public final class FireMissionConsoleClientEvents {

    private FireMissionConsoleClientEvents() {}

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()
                || !(mc.hitResult instanceof BlockHitResult hit) || event.getScrollDeltaY() == 0)
            return;
        if (FireMissionConsoleBlock.controlAt(mc.level.getBlockState(hit.getBlockPos()), hit.getBlockPos(), hit)
                != FireMissionConsoleBlock.Control.FUZE_KNOB)
            return;
        event.setCanceled(true);
        if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof FireMissionConsoleBlockEntity console))
            return;
        int step = (int) Math.signum(event.getScrollDeltaY()) * console.fuzeStepTicks(Screen.hasShiftDown());
        PacketDistributor.sendToServer(new FireMissionKnobPayload(hit.getBlockPos(), step));

        // Create's scroll-input click: pitch rising toward the latest setting, silent at the stop.
        int max = console.maxFuzeOffsetTicks();
        int turned = Mth.clamp(console.getFuzeOffsetTicks() + step, -max, max);
        if (turned == console.getFuzeOffsetTicks())
            return;
        float pitch = Mth.lerp((turned + max) / (2f * max), 1f, 1.5f);
        AllSoundEvents.SCROLL_VALUE.play(mc.level, mc.player, hit.getBlockPos(), 1, pitch);
    }
}