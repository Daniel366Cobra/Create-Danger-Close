package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Ponder scenes for the Navigation Console. */
public final class NavigationConsoleScenes {

    private NavigationConsoleScenes() {}

    /**
     * Structure {@code navigation_console.nbt}: a 6×6 floor (the bottom layer), a
     * cogwheel column beside it bringing power from off the scene to the
     * battle network along z = 4. Two gearboxes on it feed interfaces facing
     * up into a Gimbal Sensor at (1, 3, 4) and a Navigation Table at (2, 3, 4);
     * a branch north ends in an interface facing the Navigation Console at (4, 1, 1).
     */
    public static void navigationConsole(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("navigation_console", "Reading the ship's motion on a Navigation Console");
        scene.configureBasePlate(0, 0, 6);

        BlockPos console = util.grid().at(4, 1, 1);
        BlockPos consoleInterface = util.grid().at(4, 1, 2);
        BlockPos gimbal = util.grid().at(1, 3, 4);
        BlockPos navTable = util.grid().at(2, 3, 4);

        Selection floor = util.select().fromTo(0, 0, 0, 5, 0, 5);
        Selection drive = util.select().fromTo(6, 0, 4, 6, 1, 4);
        Selection network = util.select().fromTo(4, 1, 4, 5, 1, 4);
        // The shaft line west of the branch, out to the sensors' gearboxes.
        Selection sensorDrive = util.select().fromTo(1, 1, 4, 3, 1, 4);
        Selection sensorInterfaces = util.select().fromTo(1, 2, 4, 2, 2, 4);
        Selection sensors = util.select().fromTo(1, 3, 4, 2, 3, 4);
        Selection branch = util.select().fromTo(4, 1, 2, 4, 1, 3);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(console), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(console, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Navigation Console shows the ship's attitude, speed and altitude");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().showSection(branch, Direction.NORTH);
        scene.idle(15);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(consoleInterface))
                .placeNearTarget()
                .text("A Network Interface facing it connects it to the Battle Network");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(console))
                .placeNearTarget()
                .text("It has no instruments of its own: it shows what the sensors on the network read");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(sensorDrive, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(sensorInterfaces, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(sensors, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(gimbal, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Gimbal Sensor gives the ship's pitch and roll...");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(navTable, Direction.NORTH))
                .placeNearTarget()
                .text("...and a Navigation Table its heading");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(console))
                .placeNearTarget()
                .text("Velocity and Altitude Sensors on the network add the ship's speed and height");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(console))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Look at the console with Goggles to read it, and to see which sensors are missing");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }
}
