package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Ponder scenes for the Ballistic Computer and its modules. */
public final class BallisticComputerScenes {

    private BallisticComputerScenes() {}

    /**
     * Structure {@code ballistic_computer.nbt}: a 5×5 floor (the bottom layer), a
     * cogwheel column beside it bringing power from off the scene to the shaft
     * line along z = 4, a branch north to an interface facing the computer at
     * (1, 1, 1), and four modules clustered
     * east of it: differentiator and correlator in a row, integrator and fuze
     * computer stacked on top of them.
     */
    public static void ballisticComputer(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("ballistic_computer", "Computing firing solutions with the Ballistic Computer");
        scene.configureBasePlate(0, 0, 5);

        BlockPos computer = util.grid().at(1, 1, 1);
        BlockPos computerInterface = util.grid().at(1, 1, 2);
        BlockPos differentiator = util.grid().at(2, 1, 1);
        BlockPos correlator = util.grid().at(3, 1, 1);
        BlockPos integrator = util.grid().at(2, 2, 1);
        BlockPos fuzeComputer = util.grid().at(3, 2, 1);

        Selection floor = util.select().fromTo(0, 0, 0, 4, 0, 4);
        Selection drive = util.select().fromTo(5, 0, 4, 5, 1, 4);
        Selection network = util.select().fromTo(0, 1, 4, 4, 1, 4);
        Selection branch = util.select().position(1, 1, 3).add(util.select().position(computerInterface));

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(computer), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(computer, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Ballistic Computer works out firing solutions for the guns on its Battle Network");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(branch, Direction.NORTH);
        scene.idle(15);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(computerInterface))
                .placeNearTarget()
                .text("It has no shaft of its own: a Network Interface facing it connects it and drives it");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("On its own, it can only aim at a fixed point. Modules add the rest");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().showSection(util.select().position(differentiator), Direction.WEST);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(differentiator, Direction.NORTH))
                .placeNearTarget()
                .text("Differentiator: the guns lead moving targets");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().showSection(util.select().position(correlator), Direction.WEST);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(correlator, Direction.NORTH))
                .placeNearTarget()
                .text("Correlator: several rangefinders on one target are merged into a better fix");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(util.select().position(integrator), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(integrator, Direction.NORTH))
                .placeNearTarget()
                .text("Integrator: the solution is held between readings, and the ship's own speed is corrected for");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(util.select().position(fuzeComputer), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(fuzeComputer, Direction.NORTH))
                .placeNearTarget()
                .text("Fuze Computer: timed fuzes are set to the time of flight");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(fuzeComputer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Modules count when they touch the computer, directly or through other modules, in any shape");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(differentiator))
                .placeNearTarget()
                .text("One of each kind is enough. Two computers must not share modules");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Look at the computer with Goggles to see which modules it has, and what is missing");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }
}
