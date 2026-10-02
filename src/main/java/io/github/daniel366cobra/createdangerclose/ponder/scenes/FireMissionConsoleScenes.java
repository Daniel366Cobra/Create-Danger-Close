package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.effects.particles.plumes.BigCannonPlumeParticleData;
import rbasamoyai.createbigcannons.ponder.CBCAnimateBlockEntityInstruction;

/** Ponder scenes for the Fire Mission Console. */
public final class FireMissionConsoleScenes {

    private FireMissionConsoleScenes() {}

    // The first solution: traverse to the left (north-east) and elevation, in
    // degrees, for direct fire; plunging fire on the same target is this steep.
    private static final float TRAVERSE_DEG = 40, DIRECT_DEG = 15, PLUNGING_DEG = 60;
    private static final int TRAINING_TICKS = 40;
    // The target then moves: the gun traverses back by this much, slowly
    // enough for an order to fire to come while it is still moving.
    private static final float RETRAIN_DEG = -30;
    private static final int RETRAIN_TICKS = 80;
    // Output network speed while training, as a multiple of its 1 RPM keep-alive.
    private static final float TRAINING_SPEED_FACTOR = 32;

    /**
     * Structure {@code fire_mission_console.nbt}: a 6×6 floor (the bottom
     * layer), a cogwheel column beside it bringing power from off the scene
     * to the battle network along z = 5. At its west end a gearbox feeds an
     * interface facing up into the Ballistic Computer at (0, 3, 4), and a
     * branch north to an interface facing the Fire Mission Console at
     * (0, 1, 1). The gun is set up as in the Cannon Servo scene: servos at
     * x = 2 (traverse, through gearboxes in the floor) and x = 4 (elevation),
     * a cannon mount at (4, 1, 0) facing east with its cannon two blocks up,
     * and a lever on its west side.
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
        // The cannon networks, between the servo outputs and the mount (the
        // lower gearbox, encased shaft and gearbox under it sit in the floor layer).
        Selection cannonNetworks = util.select().fromTo(2, 1, 0, 2, 1, 2)
                .add(util.select().fromTo(2, 0, 0, 4, 0, 0))
                .add(util.select().fromTo(4, 1, 1, 4, 1, 2));

        // Clicks on the console's top. It faces north, so its user looks
        // south: their left half is the east one, the trajectory switch.
        Vec3 consoleTop = util.vector().topOf(console);
        Vec3 trajectorySwitch = consoleTop.add(0.25, 0, 0);
        Vec3 fireButton = consoleTop.add(-0.25, 0, 0);

        // The gun comes already assembled.
        scene.world().modifyBlock(lever, state -> state.setValue(BlockStateProperties.POWERED, true), false);
        scene.world().modifyBlock(mount, state -> withBoolean(state, "assembly_powered", true), false);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.world().showSection(computerStack, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(guns, Direction.DOWN);
        ElementLink<WorldSectionElement> cannon =
                scene.world().showIndependentSection(util.select().fromTo(4, 3, 0, 6, 3, 0), Direction.DOWN);
        // The cannon pivots where it sits on the mount.
        scene.world().configureCenterOfRotation(cannon, util.vector().centerOf(4, 3, 0));
        CannonAim aim = new CannonAim(scene, cannon);
        scene.idle(20);

        scene.world().showSection(consoleBranch, Direction.SOUTH);
        scene.idle(10);
        scene.world().showSection(util.select().position(console), Direction.DOWN);
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
        train(scene, cannonNetworks, mount, aim, TRAVERSE_DEG, DIRECT_DEG, TRAINING_TICKS);
        scene.idle(PonderTiming.AFTER_TEXT - 20 - TRAINING_TICKS);

        // Trajectory switch: plunging fire, then back to direct.
        scene.overlay().showControls(trajectorySwitch, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(trajectorySwitch)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the left half of its top to switch the trajectory");
        scene.idle(PonderTiming.AFTER_TEXT);

        train(scene, cannonNetworks, mount, aim, 0, PLUNGING_DEG - DIRECT_DEG, TRAINING_TICKS);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(muzzle(util, TRAVERSE_DEG, PLUNGING_DEG))
                .placeNearTarget()
                .text("Plunging fire: shells drop steeply, over cover and onto decks");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showControls(trajectorySwitch, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        train(scene, cannonNetworks, mount, aim, 0, DIRECT_DEG - PLUNGING_DEG, TRAINING_TICKS);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(muzzle(util, TRAVERSE_DEG, DIRECT_DEG))
                .placeNearTarget()
                .text("Direct fire: a flatter, faster path to targets in plain sight");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Fire.
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        fire(scene, util, TRAVERSE_DEG, DIRECT_DEG);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(fireButton)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the right half to fire every gun that is on target");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The target moves; an order given while the gun is still training is refused.
        scene.world().multiplyKineticSpeed(cannonNetworks, TRAINING_SPEED_FACTOR);
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -RETRAIN_DEG, RETRAIN_TICKS));
        aim.turn(RETRAIN_DEG * 30 / RETRAIN_TICKS, 0, 30);
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        aim.turn(RETRAIN_DEG * 10 / RETRAIN_TICKS, 0, 10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(mount))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Guns still training onto their solution hold their fire, so no shell is wasted");
        aim.turn(RETRAIN_DEG * (RETRAIN_TICKS - 40) / RETRAIN_TICKS, 0, RETRAIN_TICKS - 40);
        scene.world().multiplyKineticSpeed(cannonNetworks, 1 / TRAINING_SPEED_FACTOR);
        scene.idle(PonderTiming.AFTER_LONG_TEXT - (RETRAIN_TICKS - 40));

        // On target again: now it fires.
        scene.overlay().showControls(fireButton, Pointing.DOWN, 30).rightClick();
        scene.idle(10);
        fire(scene, util, TRAVERSE_DEG + RETRAIN_DEG, DIRECT_DEG);
        scene.idle(30);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(console))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Look at the console with Goggles to see the target, the trajectory and each gun's solution");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /**
     * Trains the gun by these changes in traverse (to the left) and elevation,
     * running the cannon networks fast meanwhile as the servos do. Takes
     * {@code ticks}; texts shown just before it stay up while it moves.
     */
    private static void train(CreateSceneBuilder scene, Selection cannonNetworks, BlockPos mount,
                              CannonAim aim,
                              float traverseDeg, float elevationDeg, int ticks) {
        scene.world().multiplyKineticSpeed(cannonNetworks, TRAINING_SPEED_FACTOR);
        // CBC's mount yaw grows the other way round to Ponder's rotation (east is 270, north 180).
        if (traverseDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -traverseDeg, ticks));
        if (elevationDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountPitch(mount, elevationDeg, ticks));
        aim.turn(traverseDeg, elevationDeg, ticks);
        scene.world().multiplyKineticSpeed(cannonNetworks, 1 / TRAINING_SPEED_FACTOR);
    }

    private static void fire(CreateSceneBuilder scene, SceneBuildingUtil util, float traverseDeg, float elevationDeg) {
        Vec3 barrel = barrelDirection(traverseDeg, elevationDeg);
        scene.effects().emitParticles(muzzle(util, traverseDeg, elevationDeg),
                scene.effects().particleEmitterWithinBlockSpace(new BigCannonPlumeParticleData(4f), barrel.scale(0.5)),
                1, 1);
    }

    private static Vec3 muzzle(SceneBuildingUtil util, float traverseDeg, float elevationDeg) {
        return util.vector().centerOf(4, 3, 0).add(barrelDirection(traverseDeg, elevationDeg).scale(2.5));
    }

    /** The barrel's direction: elevated, then turned to the left (towards -z). */
    private static Vec3 barrelDirection(float traverseDeg, float elevationDeg) {
        double pitch = Math.toRadians(elevationDeg), yaw = Math.toRadians(traverseDeg);
        return new Vec3(Math.cos(pitch) * Math.cos(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.sin(yaw));
    }

    /** Sets a boolean block state property by name, if the block has it. */
    private static BlockState withBoolean(BlockState state, String name, boolean value) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        return property instanceof BooleanProperty bool ? state.setValue(bool, value) : state;
    }
}
