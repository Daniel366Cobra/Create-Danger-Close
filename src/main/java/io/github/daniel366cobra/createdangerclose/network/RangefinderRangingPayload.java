package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: ranging button pressed or released. */
public record RangefinderRangingPayload(boolean active) implements CustomPacketPayload {

    public static final Type<RangefinderRangingPayload> TYPE =
            new Type<>(CreateDangerClose.asResource("rangefinder_ranging"));

    public static final StreamCodec<ByteBuf, RangefinderRangingPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(RangefinderRangingPayload::new, RangefinderRangingPayload::active);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RangefinderRangingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            RangefinderBearingBlockEntity bearing = RangefinderOptics.findOperatedBearing(context.player());
            if (bearing != null)
                bearing.setRanging(context.player(), payload.active());
        });
    }
}