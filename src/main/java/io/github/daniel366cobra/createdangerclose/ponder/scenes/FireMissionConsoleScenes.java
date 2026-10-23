package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.FireMissionConsoleBlockEntity;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.effects.particles.plumes.BigCannonPlumeParticleData;
import rbasamoyai.createbigcannons.ponder.CBCAnimateBlockEntityInstruction;

public final class FireMissionConsoleScenes {

    private FireMissionConsoleScenes() {}

    // First solution: traverse left (north-east) and direct elevation; plunging on the same target.
    private static final float TRAVERSE_DEG = 40, DIRECT_DEG = 15, PLUNGING_DEG = 60;
    private static final int TRAINING_TICKS = 40;
    // The target moves: retrained slowly enough that an order comes mid-move.
    private static final float RETRAIN_DEG = -30;
    private static final int RETRAIN_TICKS = 80;
    private static final int FUZE_DEMO_NOTCHES = 4, FUZE_NOTCH_TICKS = 2;

    /**
     * Structure {@code fire_mission_console.nbt}: 6×6 floor, battle network along z = 5. Interfaces
     * into the Ballistic Computer (0, 3, 4) and the console (0, 1, 1). The gun as in the Cannon Servo
     * scene: servos x = 2 (traverse) and x = 4 (elevation), mount (4, 1, 0) facing east, lever west.
     */
    public static void fireMissionConsole(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("fire_mission_console", "Giving fire orders with the Fire Mission Console");
        scene.configureBasePlate(0, 0, 6);

        BlockPos console = util.grid().at(0, 1, 1);
        BlockPos consoleInterface = util.grid().at(0, 1, 2);
        BlockPos computer = util.grid().at(0, 3, 4);
        BlockPos mount = util.grid().at(4, 1, 0);
        BlockPos lever = util.grid().at(3, 1, 0);

        Selection floor = util.select().fromTo(0, 0, 0, 5, 0, 5);
        Selection drive = util.select().fromTo(6, 0, 5, 6, 1, 5);
        Selection network = util.select().fromTo(0, 1, 5, 5, 1, 5);
        Selection computerStack = util.select().fromTo(0, 1, 4, 0, 3, 4);
        Selection guns = util.select().fromTo(2, 1, 0, 2, 1, 4)
                .add(util.select().fromTo(4, 1, 1, 4, 1, 4))
                .add(util.select().position(mount))
                .add(util.select().position(lever));
        Selection consoleBranch = util.select().fromTo(0, 1, 2, 0, 1, 3);
        // Servo outputs to the mount (traverse gearing in the floor layer), saved still.
        Selection mountSelection = util.select().position(mount);
        ServoDrive yawDrive = ServoDrive.yaw(util.select().fromTo(3, 0, 0, 4, 0, 0),
                util.select().fromTo(2, 1, 0, 2, 1, 2).add(util.select().position(2, 0, 0)), mountSelection);
        ServoDrive pitchDrive = ServoDrive.pitch(util.select().fromTo(4, 1, 1, 4, 1, 2), null, mountSelection);

        // The console faces north: left to right (east to west) fuze knob, trajectory lever, fire button (model pixels).
        Vec3 fuzeKnob = onConsole(console, 12.5, 17, 7.5);
        Vec3 trajectorySwitch = onConsole(console, 8.5, 18, 7.5);
        Vec3 fireButton = onConsole(console, 3.5, 17, 7.5);

        scene.world().modifyBlock(lever, state -> state.setValue(BlockStateProperties.POWERED, true), false);
        scene.world().modifyBlock(mount, CannonAim::assembled, false);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.world().showSection(computerStack, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(guns, Direction.DOWN);
        ElementLink<WorldSectionElement> cannon =
                scene.world().showIndependentSection(util.select().fromTo(4, 3, 0, 6, 3, 0), Direction.DOWN);
        scene.world().configureCenterOfRotation(cannon, util.vector().centerOf(4, 3, 0));
        CannonAim aim = new CannonAim(scene, cannon);
        scene.idle(20);

        scene.world().showSection(consoleBranch, Direction.SOUTH);
        scene.idle(10);
        scene.world().showSection(util.select().position(console), Direction.DOWN);
        // One gun on the network, not yet on target.
        showGunsReady(scene, console, 0);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(console, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Fire Mission Console gives the orders to the guns on its network");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(consoleInterface))
                .placeNearTarget()
                .text("A Network Interface facing it connects it to the Ballistic Computer");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The computer has a solution: the gun trains onto it in direct fire.
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .text("The guns train onto the computer's firing solution");
        scene.idle(20);
        showGunsReady(scene, console, 0);
        train(scene, yawDrive, pitchDrive, mount, aim, TRAVERSE_DEG, DIRECT_DEG, TRAINING_TICKS);
        showGunsReady(scene, console, 1);
        scene.idle(PonderTiming.AFTER_TEXT - 20 - TRAINING_TICKS);

        // Trajectory switch: plunging fire, then back to direct.
        scene.overlay().showControls(trajectorySwitch, Pointing.DOWN, 30).rightClick();
        showPlunging(scene, console, true);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(trajectorySwitch)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the lever in the middle to switch the trajectory");
        scene.idle(PonderTiming.AFTER_TEXT);

        showGunsReady(scene, console, 0);
        train(scene, yawDrive, pitchDrive, mount, aim, 0, PLUNGING_DEG - DIRECT_DEG, TRAINING_TICKS);
        showGunsReady(scene, console, 1);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(muzzle(util, TRAVERSE_DEG, PLUNGING_DEG))
                .placeNearTarget()
                .text("Plunging fire: shells drop steeply, over cover and onto decks");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showControls(trajectorySwitch, Pointing.DOWN, 30).rightClick();
        showPlunging(scene, console, false);
        scene.idle(10);
        showGunsReady(scene, console, 0);
        train(scene, yawDrive, pitchDrive, mount, aim, 0, DIRECT_DEG - PLUNGING_DEG, TRAINING_TICKS);
        showGunsReady(scene, console, 1);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(muzzle(util, TRAVERSE_DEG, DIRECT_DEG))
                .placeNearTarget()
                .text("Direct fire: a flatter, faster path to targets in plain sight");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Fire.
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        pressFireButton(scene, console);
        scene.idle(10);
        fire(scene, util, TRAVERSE_DEG, DIRECT_DEG);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(fireButton)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the button on the right to fire every gun that is on target");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The target moves; an order given while the gun is still training is refused.
        showGunsReady(scene, console, 0);
        yawDrive.lay(scene, RETRAIN_DEG);
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -RETRAIN_DEG, RETRAIN_TICKS));
        aim.turn(RETRAIN_DEG * 30 / RETRAIN_TICKS, 0, 30);
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        pressFireButton(scene, console);
        aim.turn(RETRAIN_DEG * 10 / RETRAIN_TICKS, 0, 10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(mount))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Guns still training onto their solution hold their fire, so no shell is wasted");
        aim.turn(RETRAIN_DEG * (RETRAIN_TICKS - 40) / RETRAIN_TICKS, 0, RETRAIN_TICKS - 40);
        showGunsReady(scene, console, 1);
        yawDrive.stop(scene);
        scene.idle(PonderTiming.AFTER_LONG_TEXT - (RETRAIN_TICKS - 40));

        // On target again: now it fires.
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        pressFireButton(scene, console);
        scene.idle(10);
        fire(scene, util, TRAVERSE_DEG + RETRAIN_DEG, DIRECT_DEG);
        scene.idle(30);

        // Danger close: the first press only warns, the second fires.
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        pressFireButton(scene, console);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(fireButton)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Danger close: if a salvo would land near your own guns, spotters or ships, the first press only warns");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        pressFireButton(scene, console);
        scene.idle(10);
        fire(scene, util, TRAVERSE_DEG + RETRAIN_DEG, DIRECT_DEG);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(fireButton)
                .placeNearTarget()
                .text("Press again within a few seconds to confirm, and it fires");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The fuze correction.
        scene.overlay().showControls(fuzeKnob, Pointing.DOWN, 40).scroll();
        scene.idle(10);
        for (int notch = 1; notch <= FUZE_DEMO_NOTCHES; notch++) {
            showFuzeOffset(scene, console, -notch * FUZE_NOTCH_TICKS);
            scene.idle(5);
        }
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(fuzeKnob)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Scroll over the knob on the left to correct timed fuzes: earlier for an airburst, later to burst inside after piercing");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showControls(fuzeKnob, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        showFuzeOffset(scene, console, 0);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(fuzeKnob)
                .placeNearTarget()
                .text("Right-click the knob to zero it");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(console))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Look at the console with Goggles to see the target, the trajectory and each gun's solution");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /** Model pixels as it faces (north). */
    private static Vec3 onConsole(BlockPos console, double x, double y, double z) {
        return Vec3.atLowerCornerOf(console).add(x / 16, y / 16, z / 16);
    }

    private static void showPlunging(CreateSceneBuilder scene, BlockPos console, boolean plunging) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class, be -> be.showPlunging(plunging));
    }

    private static void pressFireButton(CreateSceneBuilder scene, BlockPos console) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class,
                FireMissionConsoleBlockEntity::animateButtonPress);
    }

    private static void showGunsReady(CreateSceneBuilder scene, BlockPos console, int ready) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class, be -> be.showGunsReady(ready, 1));
    }

    private static void showFuzeOffset(CreateSceneBuilder scene, BlockPos console, int ticks) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class, be -> be.showFuzeOffset(ticks));
    }

    /** Traverse (left) and elevate, running the moving axes' drives. Texts shown just before stay up meanwhile. */
    private static void train(CreateSceneBuilder scene, ServoDrive yawDrive, ServoDrive pitchDrive, BlockPos mount,
                              CannonAim aim,
                              float traverseDeg, float elevationDeg, int ticks) {
        yawDrive.lay(scene, traverseDeg);
        pitchDrive.lay(scene, elevationDeg);
        // CBC's mount yaw runs opposite to Ponder's rotation (east 270, north 180).
        if (traverseDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -traverseDeg, ticks));
        if (elevationDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountPitch(mount, elevationDeg, ticks));
        aim.turn(traverseDeg, elevationDeg, ticks);
        yawDrive.stop(scene);
        pitchDrive.stop(scene);
    }

    private static void fire(CreateSceneBuilder scene, SceneBuildingUtil util, float traverseDeg, float elevationDeg) {
        Vec3 barrel = CannonAim.barrelDirection(traverseDeg, elevationDeg);
        scene.effects().emitParticles(muzzle(util, traverseDeg, elevationDeg),
                scene.effects().particleEmitterWithinBlockSpace(new BigCannonPlumeParticleData(4f), barrel.scale(0.5)),
                1, 1);
    }

    private static Vec3 muzzle(SceneBuildingUtil util, float traverseDeg, float elevationDeg) {
        return util.vector().centerOf(4, 3, 0).add(CannonAim.barrelDirection(traverseDeg, elevationDeg).scale(2.5));
    }
}