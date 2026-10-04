package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensBlock;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Ponder scenes for the rangefinder. */
public final class RangefinderScenes {

    private RangefinderScenes() {}

    // The showcase turn: a full circle, at the pace of a bearing on a fast network.
    private static final int SHOWCASE_TICKS = 160;
    // How long the Super Glue selection box takes to grow over the tube.
    private static final int GLUE_TICKS = 40;
    // Before the damage part: turn the lenses to face the viewer.
    private static final float LENS_TURN_DEG = 180;
    private static final int LENS_TURN_TICKS = 60;

    /**
     * Structure {@code rangefinder.nbt}: a 6×6 floor (the bottom layer), a
     * cogwheel column beside it bringing power from off the scene to the
     * battle network, which runs up into a Rangefinder Bearing at (2, 1, 4)
     * facing up and north to an interface and a Ballistic Computer (with an
     * integrator and a differentiator on top). On the bearing stands the
     * tube: an industrial iron mast, girders out to two lenses looking south
     * (a baseline of 4) and the eyepiece bolted to the mast's north side.
     */
    public static void rangefinder(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("rangefinder", "Measuring range with a Rangefinder");
        scene.configureBasePlate(0, 0, 6);

        BlockPos bearing = util.grid().at(2, 2, 4);
        BlockPos westLens = util.grid().at(0, 4, 4);
        BlockPos eastLens = util.grid().at(4, 4, 4);
        BlockPos eyepiece = util.grid().at(2, 3, 3);
        BlockPos computer = util.grid().at(4, 1, 0);

        Selection floor = util.select().fromTo(0, 0, 0, 5, 0, 5);
        Selection drive = util.select().fromTo(6, 0, 4, 6, 1, 4);
        Selection network = util.select().fromTo(2, 1, 4, 5, 1, 4);
        Selection computerBranch = util.select().fromTo(4, 1, 0, 4, 1, 3).add(util.select().fromTo(4, 2, 0, 4, 2, 1));
        Selection mast = util.select().fromTo(2, 3, 4, 2, 4, 4);
        Selection girders = util.select().position(1, 4, 4).add(util.select().position(3, 4, 4));
        Selection lenses = util.select().position(westLens).add(util.select().position(eastLens));

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.world().showSection(computerBranch, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(bearing), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(bearing, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Rangefinder Bearing carries a rangefinder tube and trains it");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The tube is one moving section from here on.
        ElementLink<WorldSectionElement> tube = scene.world().showIndependentSection(mast, Direction.DOWN);
        scene.world().configureCenterOfRotation(tube, util.vector().centerOf(2, 3, 4));
        scene.idle(10);
        scene.world().showSectionAndMerge(girders, Direction.DOWN, tube);
        scene.idle(10);
        scene.world().showSectionAndMerge(lenses, Direction.DOWN, tube);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(westLens, Direction.SOUTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Two Rangefinder Lenses go at the ends of a bar across their line of sight, looking the same way");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(eastLens, Direction.SOUTH))
                .placeNearTarget()
                .text("The further apart they are, the more accurate the range");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().showSectionAndMerge(util.select().position(eyepiece), Direction.SOUTH, tube);
        scene.idle(15);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(eyepiece, Direction.NORTH))
                .placeNearTarget()
                .text("The Rangefinder Eyepiece bolts onto the back of the tube, centred between the lenses");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Glue the tube together, as Create's bearing scenes do: the selection
        // box grows from one lens to cover the whole tube, then the joints glue.
        AABB glueStart = new AABB(westLens);
        AABB glueBox = new AABB(Vec3.atLowerCornerOf(util.grid().at(0, 3, 3)),
                Vec3.atLowerCornerOf(util.grid().at(5, 5, 5)));
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, glueStart, glueStart, 1);
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, glueStart, glueBox, GLUE_TICKS);
        scene.overlay().showControls(util.vector().centerOf(westLens), Pointing.LEFT, 40)
                .withItem(AllItems.SUPER_GLUE.asStack());
        scene.idle(15);
        scene.effects().superGlue(util.grid().at(2, 4, 4), Direction.WEST, true);
        scene.effects().superGlue(util.grid().at(2, 4, 4), Direction.EAST, true);
        scene.effects().superGlue(util.grid().at(2, 3, 4), Direction.UP, true);
        scene.effects().superGlue(eyepiece, Direction.SOUTH, false);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(2, 4, 4))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Glue the tube together with Super Glue");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Assemble, then show the tube all the way round.
        scene.overlay().showControls(util.vector().blockSurface(bearing, Direction.WEST), Pointing.LEFT, 30)
                .rightClick();
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(bearing, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the bearing to assemble the tube");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().rotateBearing(bearing, 360, SHOWCASE_TICKS);
        scene.world().rotateSection(tube, 0, 360, 0, SHOWCASE_TICKS);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(bearing))
                .placeNearTarget()
                .text("It turns faster the faster its network runs");
        scene.idle(SHOWCASE_TICKS + PonderTiming.GAP);

        scene.overlay().showControls(util.vector().blockSurface(eyepiece, Direction.NORTH), Pointing.DOWN, 30)
                .rightClick();
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(eyepiece, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the eyepiece to take the seat: the tube follows where you look");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(eyepiece, Direction.NORTH))
                .placeNearTarget()
                .text("Hold right-click on a target to range it: once the tube is steady, the two markers merge");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("On the Battle Network, its target goes to the Ballistic Computer");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(eastLens, Direction.SOUTH))
                .placeNearTarget()
                .text("Map Tables on the network chart the ground around what it ranges onto their maps");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Damage and repair, on the assembled tube: first turn it so the lenses
        // face the viewer. The pointers then follow the lens to where it turned.
        scene.world().rotateBearing(bearing, LENS_TURN_DEG, LENS_TURN_TICKS);
        scene.world().rotateSection(tube, 0, LENS_TURN_DEG, 0, LENS_TURN_TICKS);
        scene.idle(LENS_TURN_TICKS + PonderTiming.GAP);

        Vec3 pivot = util.vector().centerOf(2, 3, 4);
        Vec3 lensCenter = turned(util.vector().centerOf(eastLens), pivot, LENS_TURN_DEG);
        // The lens looks south when built; after the turn, along this.
        Vec3 lensFacing = turned(new Vec3(0, 0, 1), Vec3.ZERO, LENS_TURN_DEG);
        Vec3 lensFront = lensCenter.add(lensFacing.scale(0.5));
        Vec3 lensTop = lensCenter.add(0, 0.5, 0);

        scene.world().modifyBlock(eastLens, state -> state.setValue(RangefinderLensBlock.CRACKED, true), false);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(lensFront)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Blasts and hits crack lenses, which makes the range far less accurate");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showControls(lensTop, Pointing.DOWN, 40)
                .rightClick()
                .withItem(new ItemStack(Items.GLASS_PANE));
        scene.idle(20);
        scene.world().modifyBlock(eastLens, state -> state.setValue(RangefinderLensBlock.CRACKED, false), false);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(lensFront)
                .placeNearTarget()
                .text("Right-click a cracked lens with a Glass Pane to repair it");
        scene.idle(PonderTiming.AFTER_TEXT);
    }

    /**
     * Where a point ends up after turning the tube by this many degrees about
     * the vertical axis through the pivot, the same way {@code rotateSection}
     * turns it (right-handed about +Y: east swings towards north).
     */
    private static Vec3 turned(Vec3 point, Vec3 pivot, double degrees) {
        double a = Math.toRadians(degrees);
        double x = point.x - pivot.x, z = point.z - pivot.z;
        return new Vec3(pivot.x + x * Math.cos(a) + z * Math.sin(a), point.y,
                pivot.z - x * Math.sin(a) + z * Math.cos(a));
    }
}
