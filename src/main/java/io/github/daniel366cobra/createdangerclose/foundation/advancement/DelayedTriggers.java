package io.github.daniel366cobra.createdangerclose.foundation.advancement;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Triggers that fire some ticks later, as a salvo's lands one time of flight after the order. Not saved:
 * a restart or logout in between loses them.
 */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class DelayedTriggers {

    private record Pending(UUID player, Supplier<SimpleTrigger> trigger, long dueTick) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long serverTick;

    private DelayedTriggers() {}

    public static void schedule(ServerPlayer player, Supplier<SimpleTrigger> trigger, int delayTicks) {
        PENDING.add(new Pending(player.getUUID(), trigger, serverTick + Math.max(0, delayTicks)));
    }

    /** Single player: a world left before its trigger was due must not award it in the next one. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTick++;
        if (PENDING.isEmpty())
            return;
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            if (pending.dueTick() > serverTick)
                continue;
            it.remove();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(pending.player());
            if (player != null)
                pending.trigger().get().trigger(player);
        }
    }
}