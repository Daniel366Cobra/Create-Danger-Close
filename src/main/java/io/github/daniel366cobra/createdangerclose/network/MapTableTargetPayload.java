package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: the player right-clicked a map table's sheet, designating
 * the target at (u, v) (fractions of the sheet, north-west = 0, 0), or
 * clearing it. The server works out the world position from its own maps.
 */
public record MapTableTargetPayload(BlockPos table, boolean clear, float u, float v) implements CustomPacketPayload {

    public static final Type<MapTableTargetPayload> TYPE =
            new Type<>(CreateDangerClose.asResource("map_table_target"));

    public static final StreamCodec<ByteBuf, MapTableTargetPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MapTableTargetPayload::table,
            ByteBufCodecs.BOOL, MapTableTargetPayload::clear,
            ByteBufCodecs.FLOAT, MapTableTargetPayload::u,
            ByteBufCodecs.FLOAT, MapTableTargetPayload::v,
            MapTableTargetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MapTableTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // A malformed point (NaN survives clamping) would put the target nowhere.
            if (!payload.clear() && (!Float.isFinite(payload.u()) || !Float.isFinite(payload.v())))
                return;
            // Only the table whose screen the player has open.
            if (!(context.player().containerMenu instanceof MapTableMenu menu)
                    || !menu.stillValid(context.player()))
                return;
            MapTableBlockEntity controller = menu.getController();
            if (controller == null || !controller.getBlockPos().equals(payload.table()))
                return;
            if (payload.clear())
                controller.clearTarget();
            else
                controller.designateTarget(payload.u(), payload.v());
        });
    }
}
