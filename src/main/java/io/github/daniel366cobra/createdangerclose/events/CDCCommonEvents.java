package io.github.daniel366cobra.createdangerclose.events;

import io.github.daniel366cobra.createdangerclose.CDCStats;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public class CDCCommonEvents {

    // --- MOD LIFECYCLE EVENTS (Registered to modEventBus) ---
    public static class ModBusEvents {
        @SubscribeEvent
        public static void postRegister(final FMLLoadCompleteEvent event) {
            CDCStats.bootstrap();
        }

        @SubscribeEvent
        public static void commonSetup(final FMLCommonSetupEvent event) {
            // Any common setup logic
        }
    }

    // --- RUNTIME EVENTS (Registered to NeoForge.EVENT_BUS) ---
    @SubscribeEvent
    public static void onServerStarting(final ServerStartingEvent event) {
        // Any server startup logic
    }
}