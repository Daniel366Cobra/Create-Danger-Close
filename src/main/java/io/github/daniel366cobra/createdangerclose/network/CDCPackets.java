package io.github.daniel366cobra.createdangerclose.network;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Network payload registration (mod bus, routed automatically). */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class CDCPackets {

    private static final String PROTOCOL_VERSION = "1";

    private CDCPackets() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION)
                .playToServer(RangefinderRangingPayload.TYPE, RangefinderRangingPayload.STREAM_CODEC,
                        RangefinderRangingPayload::handle)
                .playToServer(MapTableTargetPayload.TYPE, MapTableTargetPayload.STREAM_CODEC,
                        MapTableTargetPayload::handle)
                .playToServer(FireMissionKnobPayload.TYPE, FireMissionKnobPayload.STREAM_CODEC,
                        FireMissionKnobPayload::handle)
                .playToServer(ServoLimitsPayload.TYPE, ServoLimitsPayload.STREAM_CODEC,
                        ServoLimitsPayload::handle);
    }
}