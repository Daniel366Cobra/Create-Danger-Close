package io.github.daniel366cobra.createdangerclose.events;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps a rangefinder operator seated across logout/login and world reload.
 *
 * <p>Create deliberately never saves a contraption as a player's root vehicle
 * ({@code AbstractContraptionEntity.hasExactlyOnePlayerPassenger()} is false,
 * so logging out cannot pull the contraption out of the world), which means
 * anyone seated on a contraption is dismounted on logout. We remember the
 * operator's tube and seat them again once it is loaded.
 */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class RangefinderOperatorPersistence {

    private static final String SEAT_TAG = CreateDangerClose.MOD_ID + ":rangefinder_seat";
    // The tube may load after the player (e.g. aboard a ship), so keep trying for a while.
    private static final int RESEAT_ATTEMPT_TICKS = 200;

    private static final class PendingReseat {
        final UUID contraptionId;
        int ticksLeft = RESEAT_ATTEMPT_TICKS;

        PendingReseat(UUID contraptionId) {
            this.contraptionId = contraptionId;
        }
    }

    private static final Map<UUID, PendingReseat> PENDING = new HashMap<>();

    private RangefinderOperatorPersistence() {}

    /** Fires before the player is saved and dismounted. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        PENDING.remove(player.getUUID());
        if (player.getVehicle() instanceof AbstractContraptionEntity contraptionEntity
                && contraptionEntity.getContraption() instanceof RangefinderContraption rc
                && rc.getEyepiecePos() != null
                && rc.getEyepiecePos().equals(rc.getSeatOf(player.getUUID()))) {
            player.getPersistentData().putUUID(SEAT_TAG, contraptionEntity.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        CompoundTag data = event.getEntity().getPersistentData();
        if (!data.hasUUID(SEAT_TAG))
            return;
        PENDING.put(event.getEntity().getUUID(), new PendingReseat(data.getUUID(SEAT_TAG)));
        data.remove(SEAT_TAG);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty())
            return;
        Iterator<Map.Entry<UUID, PendingReseat>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingReseat> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            PendingReseat pending = entry.getValue();
            if (player == null || tryReseat(player, pending.contraptionId) || --pending.ticksLeft <= 0)
                it.remove();
        }
    }

    /** @return true when done (seated, or no longer possible), false to retry. */
    private static boolean tryReseat(ServerPlayer player, UUID contraptionId) {
        if (player.isPassenger())
            return true;
        Entity entity = player.serverLevel().getEntity(contraptionId);
        if (entity == null)
            return false; // not loaded yet
        if (!(entity instanceof AbstractContraptionEntity contraptionEntity)
                || !(contraptionEntity.getContraption() instanceof RangefinderContraption rc))
            return true;

        BlockPos eyepiece = rc.getEyepiecePos();
        int seat = eyepiece != null ? rc.getSeats().indexOf(eyepiece) : -1;
        if (seat < 0 || rc.getSeatMapping().containsValue(seat))
            return true; // no eyepiece seat, or someone else took it

        contraptionEntity.addSittingPassenger(player, seat);
        return true;
    }
}
