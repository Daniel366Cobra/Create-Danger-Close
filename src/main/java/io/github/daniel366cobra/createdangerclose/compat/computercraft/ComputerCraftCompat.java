package io.github.daniel366cobra.createdangerclose.compat.computercraft;

import dan200.computercraft.api.peripheral.PeripheralCapability;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Only loaded with CC: Tweaked present. */
public final class ComputerCraftCompat {

    private ComputerCraftCompat() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(ComputerCraftCompat::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(PeripheralCapability.get(), CDCBlockEntities.BALLISTIC_COMPUTER.get(),
                (computer, side) -> new BallisticComputerPeripheral(computer));
    }
}