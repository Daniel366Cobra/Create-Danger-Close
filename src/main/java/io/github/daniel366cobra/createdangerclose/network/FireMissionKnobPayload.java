package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client to server: the fuze knob turned by this many ticks. */
public record FireMissionKnobPayload(BlockPos console, int deltaTicks) implements CustomPacketPayload {

    // Largest turn one scroll may make, ticks (a coarse notch).
    private static final int MAX_STEP_TICKS = 5;

    public static final Type<FireMissionKnobPayload> TYPE =
            new Type<>(CreateDangerClose.asResource("fire_mission_knob"));

    public static final StreamCodec<ByteBuf, FireMissionKnobPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FireMissionKnobPayload::console,
            ByteBufCodecs.VAR_INT, FireMissionKnobPayload::deltaTicks,
            FireMissionKnobPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FireMissionKnobPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Within reach, as for clicking it (Sable measures this across ships).
            if (!context.player().canInteractWithBlock(payload.console(), 1.0))
                return;
            if (context.player().level().getBlockEntity(payload.console()) instanceof FireMissionConsoleBlockEntity console)
                console.adjustFuzeOffset(Mth.clamp(payload.deltaTicks(), -MAX_STEP_TICKS, MAX_STEP_TICKS));
        });
    }
}