package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.content.contraptions.bearing.BearingRenderer;
import com.simibubi.create.content.contraptions.bearing.BearingVisual;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.BlockEntityEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.*;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableRenderer;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.navigation_console.NavigationConsoleBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceRenderer;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceVisual;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;

import java.util.ArrayList;

public class CDCBlockEntities {
    private static final CreateRegistrate REGISTRATE = CreateDangerClose.getRegistrate();

    public static final ArrayList<BlockEntityEntry> BLOCK_ENTITIES = new ArrayList<>();

    public static final BlockEntityEntry<NetworkInterfaceBlockEntity> NETWORK_INTERFACE = REGISTRATE
            .blockEntity("network_interface", NetworkInterfaceBlockEntity::new)
            .visual(() -> NetworkInterfaceVisual::new)
            .validBlocks(CDCBlocks.NETWORK_INTERFACE)
            .renderer(() -> NetworkInterfaceRenderer::new)
            .register();

    public static final BlockEntityEntry<BallisticComputerBlockEntity> BALLISTIC_COMPUTER = REGISTRATE
            .blockEntity("ballistic_computer", BallisticComputerBlockEntity::new)
            .validBlocks(CDCBlocks.BALLISTIC_COMPUTER)
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
            .register();

    public static final BlockEntityEntry<FireMissionConsoleBlockEntity> FIRE_MISSION_CONSOLE = REGISTRATE
            .blockEntity("fire_mission_console", FireMissionConsoleBlockEntity::new)
            .validBlocks(CDCBlocks.FIRE_MISSION_CONSOLE)
            .register();

    public static void register() {
        BLOCK_ENTITIES.add(NETWORK_INTERFACE);
        BLOCK_ENTITIES.add(BALLISTIC_COMPUTER);
        BLOCK_ENTITIES.add(CANNON_SERVO_INPUT);
        BLOCK_ENTITIES.add(CANNON_SERVO_OUTPUT);
        BLOCK_ENTITIES.add(RANGEFINDER_BEARING);
        BLOCK_ENTITIES.add(MAP_TABLE);
        BLOCK_ENTITIES.add(NAVIGATION_CONSOLE);
        BLOCK_ENTITIES.add(FIRE_MISSION_CONSOLE);
    }
}