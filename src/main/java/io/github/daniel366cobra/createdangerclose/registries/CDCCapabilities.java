package io.github.daniel366cobra.createdangerclose.registries;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.coupler.CouplerOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/** What other blocks can reach in ours: the lubricated equipment's oil reservoirs, for pipes. */
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID)
public final class CDCCapabilities {

    private CDCCapabilities() {}

    @SubscribeEvent
    static void register(RegisterCapabilitiesEvent event) {
        for (BlockEntityType<?> type : new BlockEntityType<?>[]{CDCBlockEntities.BALLISTIC_COMPUTER.get(),
                CDCBlockEntities.COMPUTER_MODULE.get(), CDCBlockEntities.NETWORK_INTERFACE.get(),
                CDCBlockEntities.CANNON_SERVO_OUTPUT.get(), CDCBlockEntities.KINETIC_NETWORK_COUPLER_OUTPUT.get()})
            event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, type, (be, side) -> reservoirOf(be));
        // A servo's reservoir is kept by its output half; a pipe may come into either.
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CDCBlockEntities.CANNON_SERVO_INPUT.get(),
                (input, side) -> {
                    CannonServoOutputBlockEntity output = input.getAdjacentOutput();
                    return output != null ? reservoirOf(output) : null;
                });
        // And a coupler's by its output half too.
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, CDCBlockEntities.KINETIC_NETWORK_COUPLER.get(),
                (input, side) -> {
                    CouplerOutputBlockEntity output = input.getAdjacentOutput();
                    return output != null ? reservoirOf(output) : null;
                });
    }

    @Nullable
    private static IFluidHandler reservoirOf(BlockEntity be) {
        EquipmentHealth health = EquipmentHealth.of(be);
        return health != null ? health.getReservoir() : null;
    }
}
