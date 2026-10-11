package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: a safe zone between two sheet fractions, or the one under (u0, v0) removed. */
public record MapTableSafeZonePayload(BlockPos table, boolean remove, float u0, float v0, float u1, float v1)
        implements CustomPacketPayload {

    public static final Type<MapTableSafeZonePayload> TYPE =
            new Type<>(CreateDangerClose.asResource("map_table_safe_zone"));

    public static final StreamCodec<ByteBuf, MapTableSafeZonePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MapTableSafeZonePayload::table,
            ByteBufCodecs.BOOL, MapTableSafeZonePayload::remove,
            ByteBufCodecs.FLOAT, MapTableSafeZonePayload::u0,
            ByteBufCodecs.FLOAT, MapTableSafeZonePayload::v0,
            ByteBufCodecs.FLOAT, MapTableSafeZonePayload::u1,
            ByteBufCodecs.FLOAT, MapTableSafeZonePayload::v1,
            MapTableSafeZonePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MapTableSafeZonePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!Float.isFinite(payload.u0()) || !Float.isFinite(payload.v0())
                    || !Float.isFinite(payload.u1()) || !Float.isFinite(payload.v1()))
                return;
            // Only the table whose screen the player has open.
            if (!(context.player().containerMenu instanceof MapTableMenu menu)
                    || !menu.stillValid(context.player()))
                return;
            MapTableBlockEntity controller = menu.getController();
            if (controller == null || !controller.getBlockPos().equals(payload.table()))
                return;
            if (payload.remove())
                controller.removeSafeZone(payload.u0(), payload.v0());
            else if (!controller.addSafeZone(payload.u0(), payload.v0(), payload.u1(), payload.v1()))
                context.player().displayClientMessage(Component.translatable("createdangerclose.map_table.safe_zone_limit",
                        CDCConfig.server().mapTable.maxSafeZones.get()).withStyle(ChatFormatting.RED), true);
        });
    }
}