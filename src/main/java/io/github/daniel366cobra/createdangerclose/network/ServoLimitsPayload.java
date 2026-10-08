package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.foundation.sensor.ServoLimits;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the limits a player set on a cannon servo's protractor dial. */
public record ServoLimitsPayload(BlockPos servo, float start, float length) implements CustomPacketPayload {

    public static final Type<ServoLimitsPayload> TYPE =
            new Type<>(CreateDangerClose.asResource("servo_limits"));

    public static final StreamCodec<ByteBuf, ServoLimitsPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ServoLimitsPayload::servo,
            ByteBufCodecs.FLOAT, ServoLimitsPayload::start,
            ByteBufCodecs.FLOAT, ServoLimitsPayload::length,
            ServoLimitsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServoLimitsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Malformed angles would leave the servo unable to turn.
            if (!Float.isFinite(payload.start()) || !Float.isFinite(payload.length()))
                return;
            var player = context.player();
            if (!player.canInteractWithBlock(payload.servo(), 4.0)
                    || !(player.level().getBlockEntity(payload.servo()) instanceof CannonServoOutputBlockEntity servo))
                return;
            float start = Mth.wrapDegrees(payload.start());
            servo.setLimits(new ServoLimits(start, Mth.clamp(payload.length(), 1, 360)));
        });
    }
}