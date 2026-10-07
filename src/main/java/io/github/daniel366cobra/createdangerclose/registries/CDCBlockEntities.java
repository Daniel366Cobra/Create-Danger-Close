package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.content.contraptions.bearing.BearingRenderer;
import com.simibubi.create.content.contraptions.bearing.BearingVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerRenderer;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.ComputerModuleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.ComputerModuleRenderer;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.*;
import io.github.daniel366cobra.createdangerclose.block.coupler.CouplerInputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.coupler.CouplerOutputBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.AutocannonControlConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleRenderer;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableRenderer;
import io.github.daniel366cobra.createdangerclose.block.navigation_console.NavigationConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.navigation_console.NavigationConsoleRenderer;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceRenderer;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceVisual;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;


public class CDCBlockEntities {
    private static final CreateRegistrate REGISTRATE = CreateDangerClose.getRegistrate();

    public static final BlockEntityEntry<NetworkInterfaceBlockEntity> NETWORK_INTERFACE = REGISTRATE
            .blockEntity("network_interface", NetworkInterfaceBlockEntity::new)
            .visual(() -> NetworkInterfaceVisual::new)
            .validBlocks(CDCBlocks.NETWORK_INTERFACE)
            .renderer(() -> NetworkInterfaceRenderer::new)
            .register();

    public static final BlockEntityEntry<BallisticComputerBlockEntity> BALLISTIC_COMPUTER = REGISTRATE
            .blockEntity("ballistic_computer", BallisticComputerBlockEntity::new)
            .validBlocks(CDCBlocks.BALLISTIC_COMPUTER)
            .renderer(() -> BallisticComputerRenderer::new)
            .register();

    public static final BlockEntityEntry<ComputerModuleBlockEntity> COMPUTER_MODULE = REGISTRATE
            .blockEntity("computer_module", ComputerModuleBlockEntity::new)
            .validBlocks(CDCBlocks.DIFFERENTIATOR, CDCBlocks.INTEGRATOR, CDCBlocks.CORRELATOR, CDCBlocks.FUZE_COMPUTER)
            .renderer(() -> ComputerModuleRenderer::new)
            .register();

    public static final BlockEntityEntry<CannonServoInputBlockEntity> CANNON_SERVO_INPUT =
            REGISTRATE.blockEntity("cannon_servo_input", CannonServoInputBlockEntity::new)
                    .visual(() -> (ctx, blockEntity, partialTick) -> new CannonServoVisual(ctx, blockEntity, partialTick, false), false)
                    .validBlocks(CDCBlocks.CANNON_SERVO_INPUT)
                    .renderer(() -> CannonServoRenderer::input)
                    .register();

    public static final BlockEntityEntry<CannonServoOutputBlockEntity> CANNON_SERVO_OUTPUT =
            REGISTRATE.blockEntity("cannon_servo_output", CannonServoOutputBlockEntity::new)
                    .visual(() -> (ctx, blockEntity, partialTick) -> new CannonServoVisual(ctx, blockEntity, partialTick, true), false)
                    .validBlocks(CDCBlocks.CANNON_SERVO_OUTPUT)
                    .renderer(() -> CannonServoRenderer::output)
                    .register();

    public static final BlockEntityEntry<CouplerInputBlockEntity> KINETIC_NETWORK_COUPLER =
            REGISTRATE.blockEntity("kinetic_network_coupler", CouplerInputBlockEntity::new)
                    .visual(() -> (ctx, blockEntity, partialTick) -> new CannonServoVisual(ctx, blockEntity, partialTick, false,
                            CDCPartialModels.COUPLER_INPUT_COUPLER, CDCPartialModels.COUPLER_OUTPUT_COUPLER,
                            CDCPartialModels.COUPLER_TRANSFER_COUPLER), false)
                    .validBlocks(CDCBlocks.KINETIC_NETWORK_COUPLER)
                    .renderer(() -> CannonServoRenderer::couplerInput)
                    .register();

    public static final BlockEntityEntry<CouplerOutputBlockEntity> KINETIC_NETWORK_COUPLER_OUTPUT =
            REGISTRATE.blockEntity("kinetic_network_coupler_output", CouplerOutputBlockEntity::new)
                    .visual(() -> (ctx, blockEntity, partialTick) -> new CannonServoVisual(ctx, blockEntity, partialTick, true,
                            CDCPartialModels.COUPLER_INPUT_COUPLER, CDCPartialModels.COUPLER_OUTPUT_COUPLER,
                            CDCPartialModels.COUPLER_TRANSFER_COUPLER), false)
                    .validBlocks(CDCBlocks.KINETIC_NETWORK_COUPLER_OUTPUT)
                    .renderer(() -> CannonServoRenderer::couplerOutput)
                    .register();

    public static final BlockEntityEntry<RangefinderBearingBlockEntity> RANGEFINDER_BEARING = REGISTRATE
            .blockEntity("rangefinder_bearing", RangefinderBearingBlockEntity::new)
            .visual(() -> BearingVisual::new)
            .validBlocks(CDCBlocks.RANGEFINDER_BEARING)
            .renderer(() -> BearingRenderer::new)
            .register();

    public static final BlockEntityEntry<MapTableBlockEntity> MAP_TABLE = REGISTRATE
            .blockEntity("map_table", MapTableBlockEntity::new)
            .validBlocks(CDCBlocks.MAP_TABLE)
            .renderer(() -> MapTableRenderer::new)
            .register();

    public static final BlockEntityEntry<NavigationConsoleBlockEntity> NAVIGATION_CONSOLE = REGISTRATE
            .blockEntity("navigation_console", NavigationConsoleBlockEntity::new)
            .validBlocks(CDCBlocks.NAVIGATION_CONSOLE)
            .renderer(() -> NavigationConsoleRenderer::new)
            .register();

    public static final BlockEntityEntry<FireMissionConsoleBlockEntity> FIRE_MISSION_CONSOLE = REGISTRATE
            .blockEntity("fire_mission_console", FireMissionConsoleBlockEntity::new)
            .validBlocks(CDCBlocks.FIRE_MISSION_CONSOLE)
            .renderer(() -> FireMissionConsoleRenderer::new)
            .register();

    public static final BlockEntityEntry<AutocannonControlConsoleBlockEntity> AUTOCANNON_CONTROL_CONSOLE = REGISTRATE
            .blockEntity("autocannon_control_console", AutocannonControlConsoleBlockEntity::new)
            .validBlocks(CDCBlocks.AUTOCANNON_CONTROL_CONSOLE)
            .renderer(() -> FireMissionConsoleRenderer::autocannon)
            .register();

    /** Loads this class, which registers its entries. */
    public static void register() {}
}