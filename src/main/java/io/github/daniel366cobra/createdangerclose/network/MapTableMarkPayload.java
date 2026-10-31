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

/** Client to server: a multi-target mark added at sheet fractions (u, v), or the mark {@code id} removed or changed. */
public record MapTableMarkPayload(BlockPos table, int action, float u, float v, int id) implements CustomPacketPayload {

    public static final int ADD = 0, REMOVE = 1, MORE_ROUNDS = 2, FEWER_ROUNDS = 3;

    public static final Type<MapTableMarkPayload> TYPE =
            new Type<>(CreateDangerClose.asResource("map_table_mark"));

    public static final StreamCodec<ByteBuf, MapTableMarkPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MapTableMarkPayload::table,
            ByteBufCodecs.VAR_INT, MapTableMarkPayload::action,
            ByteBufCodecs.FLOAT, MapTableMarkPayload::u,
            ByteBufCodecs.FLOAT, MapTableMarkPayload::v,
            ByteBufCodecs.VAR_INT, MapTableMarkPayload::id,
            MapTableMarkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MapTableMarkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // A malformed point (NaN survives clamping) would put the mark nowhere.
            if (payload.action() == ADD && (!Float.isFinite(payload.u()) || !Float.isFinite(payload.v())))
                return;
            // Only the table whose screen the player has open.
            if (!(context.player().containerMenu instanceof MapTableMenu menu)
                    || !menu.stillValid(context.player()))
                return;
            MapTableBlockEntity controller = menu.getController();
            if (controller == null || !controller.getBlockPos().equals(payload.table()))
                return;
            switch (payload.action()) {
                case ADD -> controller.addMark(payload.u(), payload.v());
                case REMOVE -> controller.removeMark(payload.id());
                case MORE_ROUNDS -> controller.adjustMarkRounds(payload.id(), 1);
                case FEWER_ROUNDS -> controller.adjustMarkRounds(payload.id(), -1);
                default -> { }
            }
        });
    }
}