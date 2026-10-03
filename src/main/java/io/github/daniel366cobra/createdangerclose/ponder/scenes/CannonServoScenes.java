package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
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

/** Ponder scenes for the Cannon Servo. */
public final class CannonServoScenes {

    private CannonServoScenes() {}

    // How the demonstration gun trains onto its target: traverse to the left
    // (north-east) and elevation, in degrees, over this many ticks.
    private static final float TRAVERSE_DEG = 40, ELEVATION_DEG = 20;
    private static final int TRAINING_TICKS = 40;
    // Output network speed while training, as a multiple of its 1 RPM keep-alive.
    private static final float TRAINING_SPEED_FACTOR = 32;

    /**
     * Structure {@code cannon_servo.nbt}: a 6×6 floor (the bottom layer), a
     * cogwheel column beside it bringing power from off the scene to the
     * battle network along z = 5, a Ballistic Computer
     * with its interface on the west side, and two servo pairs: the one at
     * x = 4 drives the mount's elevation through the shaft at (4, 1, 1), the
     * one at x = 2 its traverse through two stacked gearboxes down to the gearbox
     * under the mount. The cannon mount at (4, 1, 0) faces east with its
     * (unassembled) cannon two blocks up, and a lever on its west side.
     */
    public static void cannonServo(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("cannon_servo", "Aiming Cannon Mounts with Cannon Servos");
        scene.configureBasePlate(0, 0, 6);

        BlockPos mount = util.grid().at(4, 1, 0);
        BlockPos lever = util.grid().at(3, 1, 0);
        BlockPos pitchInput = util.grid().at(4, 1, 3);
        BlockPos pitchOutput = util.grid().at(4, 1, 2);
        BlockPos yawOutput = util.grid().at(2, 1, 2);
        BlockPos computer = util.grid().at(0, 1, 2);

        Selection floor = util.select().fromTo(0, 0, 0, 5, 0, 5);
        Selection drive = util.select().fromTo(6, 0, 5, 6, 1, 5);
        Selection battleNetwork = util.select().fromTo(0, 1, 5, 5, 1, 5);
        Selection computerBranch = util.select().fromTo(0, 1, 2, 0, 1, 4);
        Selection servos = util.select().fromTo(2, 1, 2, 2, 1, 4).add(util.select().fromTo(4, 1, 2, 4, 1, 4));
        Selection pitchLinkage = util.select().position(4, 1, 1);
        Selection yawLinkage = util.select().fromTo(2, 1, 0, 2, 1, 1);
        // The cannon networks, between the servo outputs and the mount (the
        // lower gearbox, encased shaft and gearbox under it sit in the floor layer).
        Selection cannonNetworks = util.select().fromTo(2, 1, 0, 2, 1, 2)
                .add(util.select().fromTo(2, 0, 0, 4, 0, 0))
                .add(util.select().fromTo(4, 1, 1, 4, 1, 2));

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(battleNetwork, Direction.DOWN);
        scene.world().showSection(computerBranch, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(mount).add(util.select().position(lever)), Direction.DOWN);
        ElementLink<WorldSectionElement> cannon =
                scene.world().showIndependentSection(util.select().fromTo(4, 3, 0, 6, 3, 0), Direction.DOWN);
        // The cannon pivots where it sits on the mount.
        scene.world().configureCenterOfRotation(cannon, util.vector().centerOf(4, 3, 0));
        scene.idle(20);

        scene.world().showSection(servos, Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(pitchInput, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Cannon Servo turns one axis of a Cannon Mount for the Ballistic Computer");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Input, then output: one after the other, the first fading out before the second appears.
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().topOf(pitchInput))
                .placeNearTarget()
                .text("Its input sits on the Battle Network...");
        scene.idle(PonderTiming.AFTER_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(pitchOutput))
                .placeNearTarget()
                .text("...and its output drives the cannon's own network. The two networks never join");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(pitchLinkage, Direction.SOUTH);
        scene.world().showSection(yawLinkage, Direction.SOUTH);
        scene.idle(15);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(4, 1, 1))
                .placeNearTarget()
                .attachKeyFrame()
                .text("One servo drives the mount's elevation...");
        scene.idle(PonderTiming.AFTER_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(2, 1, 0))
                .placeNearTarget()
                .text("...the other its traverse. Each finds out which one it drives on its own");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Assemble the cannon with the lever.
        scene.world().modifyBlock(lever, state -> state.setValue(BlockStateProperties.POWERED, true), false);
        scene.world().modifyBlock(mount, CannonAim::assembled, false);
        scene.effects().createRedstoneParticles(lever, 0xFF0000, 10);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(mount, Direction.WEST))
                .placeNearTarget()
                .text("Assemble the cannon as usual");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The computer finds a solution, and the servos train the gun onto it.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("When the computer has a firing solution, the servos train the gun onto it");
        scene.idle(30);
        scene.world().multiplyKineticSpeed(cannonNetworks, TRAINING_SPEED_FACTOR);
        // CBC's mount yaw grows the other way round to Ponder's rotation (east is 270, north 180).
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -TRAVERSE_DEG, TRAINING_TICKS));
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountPitch(mount, ELEVATION_DEG, TRAINING_TICKS));
        new CannonAim(scene, cannon).turn(TRAVERSE_DEG, ELEVATION_DEG, TRAINING_TICKS);
        scene.world().multiplyKineticSpeed(cannonNetworks, 1 / TRAINING_SPEED_FACTOR);
        scene.idle(40);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(yawOutput))
                .placeNearTarget()
                .text("On target, the servos hold it there, and keep following a moving solution");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Fire.
        Vec3 barrel = CannonAim.barrelDirection(TRAVERSE_DEG, ELEVATION_DEG);
        Vec3 muzzle = util.vector().centerOf(4, 3, 0).add(barrel.scale(2.5));
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(muzzle)
                .placeNearTarget()
                .attachKeyFrame()
                .text("The cannon may be fired as usual or from the Fire Mission Console");
        scene.idle(40);
        scene.effects().emitParticles(muzzle,
                scene.effects().particleEmitterWithinBlockSpace(new BigCannonPlumeParticleData(4f), barrel.scale(0.5)),
                1, 1);
        scene.idle(80);
    }
}
