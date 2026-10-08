package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class NetworkInterfaceScenes {

    private NetworkInterfaceScenes() {}

    /**
     * Structure {@code network_interface.nbt}: 5×5 floor, battle network along z = 4 with two sensor interfaces
     * (gimbal, altitude), and a branch north to an interface facing a computer bank.
     */
    public static void networkInterface(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("network_interface", "Connecting sensors to a Battle Network");
        scene.configureBasePlate(0, 0, 5);

        BlockPos gimbalInterface = util.grid().at(1, 2, 4);
        BlockPos altitudeInterface = util.grid().at(3, 2, 4);
        BlockPos gimbal = util.grid().at(1, 3, 4);
        BlockPos computerInterface = util.grid().at(2, 1, 2);
        BlockPos computer = util.grid().at(2, 1, 1);

        Selection floor = util.select().fromTo(0, 0, 0, 4, 0, 4);
        Selection drive = util.select().fromTo(5, 0, 4, 5, 1, 4);
        Selection network = util.select().fromTo(0, 1, 4, 4, 1, 4);
        Selection sensors = util.select().position(gimbalInterface).add(util.select().position(altitudeInterface))
                .add(util.select().position(gimbal)).add(util.select().position(3, 3, 4));
        Selection branch = util.select().position(2, 1, 3).add(util.select().position(computerInterface));
        Selection bank = util.select().fromTo(1, 1, 1, 3, 1, 1);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(2, 1, 4))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Fire control equipment shares one kinetic network: the Battle Network");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().showSection(util.select().position(gimbalInterface)
                .add(util.select().position(altitudeInterface)), Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(gimbal)
                .add(util.select().position(3, 3, 4)), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(gimbalInterface, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Network Interface reads the sensor it faces and shares the reading with the network");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().blockSurface(altitudeInterface, Direction.EAST))
                .placeNearTarget()
                .text("It needs at least 32 RPM, and polls its sensor more often the faster it turns");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(branch, Direction.SOUTH);
        scene.idle(10);
        scene.world().showSection(bank, Direction.SOUTH);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(computerInterface, Direction.UP))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Facing a Ballistic Computer, an interface connects the computer to the network and drives it");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .text("The computer then works with every sensor on the Battle Network");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(computerInterface))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Interfaces also connect Map Tables, Navigation Consoles and Fire Mission Consoles");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }
}