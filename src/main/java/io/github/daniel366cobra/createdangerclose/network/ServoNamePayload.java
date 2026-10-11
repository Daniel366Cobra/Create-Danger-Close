package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoInputBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the name a player gave a cannon servo (empty clears it). */
public record ServoNamePayload(BlockPos servo, String name) implements CustomPacketPayload {

    public static final Type<ServoNamePayload> TYPE =
            new Type<>(CreateDangerClose.asResource("servo_name"));

    // Room for the longest name in any script, with slack; the servo cuts it to its limit.
    public static final StreamCodec<ByteBuf, ServoNamePayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ServoNamePayload::servo,
            ByteBufCodecs.stringUtf8(4 * CannonServoInputBlockEntity.MAX_NAME_LENGTH), ServoNamePayload::name,
            ServoNamePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServoNamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (!player.canInteractWithBlock(payload.servo(), 4.0)
                    || !(player.level().getBlockEntity(payload.servo()) instanceof CannonServoInputBlockEntity servo))
                return;
            servo.setServoName(payload.name());
        });
    }
}