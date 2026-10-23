package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlock;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlock;
import dev.simulated_team.simulated.index.SimBlocks;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.effects.particles.plumes.BigCannonPlumeParticleData;
import rbasamoyai.createbigcannons.ponder.CBCAnimateBlockEntityInstruction;

public final class CannonServoScenes {

    private CannonServoScenes() {}

    // Traverse left (north-east) and elevation.
    private static final float TRAVERSE_DEG = 40, ELEVATION_DEG = 20;
    private static final int TRAINING_TICKS = 40;

    /**
     * Structure {@code cannon_servo.nbt}: 6×6 floor, battle network along z = 5, a computer with its
     * interface on the west. Servos at x = 4 (elevation, shaft at (4, 1, 1)) and x = 2 (traverse, gearboxes
     * down under the mount). Mount (4, 1, 0) facing east, cannon unassembled, lever west.
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
        // Servo outputs to the mount (traverse gearing in the floor layer), saved still.
        Selection mountSelection = util.select().position(mount);
        ServoDrive yawDrive = ServoDrive.yaw(util.select().fromTo(3, 0, 0, 4, 0, 0),
                util.select().fromTo(2, 1, 0, 2, 1, 2).add(util.select().position(2, 0, 0)), mountSelection);
        ServoDrive pitchDrive = ServoDrive.pitch(util.select().fromTo(4, 1, 1, 4, 1, 2), null, mountSelection);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(battleNetwork, Direction.DOWN);
        scene.world().showSection(computerBranch, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(util.select().position(mount).add(util.select().position(lever)), Direction.DOWN);
        ElementLink<WorldSectionElement> cannon =
                scene.world().showIndependentSection(util.select().fromTo(4, 3, 0, 6, 3, 0), Direction.DOWN);
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

        // The first text fades before the second appears.
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
        yawDrive.lay(scene, TRAVERSE_DEG);
        pitchDrive.lay(scene, ELEVATION_DEG);
        // CBC's mount yaw runs opposite to Ponder's rotation (east 270, north 180).
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -TRAVERSE_DEG, TRAINING_TICKS));
        scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountPitch(mount, ELEVATION_DEG, TRAINING_TICKS));
        new CannonAim(scene, cannon).turn(TRAVERSE_DEG, ELEVATION_DEG, TRAINING_TICKS);
        yawDrive.stop(scene);
        pitchDrive.stop(scene);
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
        // The rest of the fire text's time, plus the usual gap.
        scene.idle(PonderTiming.AFTER_TEXT - 40);

        // Naming: last, so the earlier texts keep their numbers.
        scene.overlay().showControls(util.vector().topOf(pitchInput), Pointing.DOWN, 30).rightClick();
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(pitchInput))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click a servo's input with an empty hand to name the guns it lays");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .text("Consoles report by these names, and map tables label the guns. Name a turret's bearing servo to name all its guns");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Arc limits, likewise appended.
        scene.overlay().showControls(util.vector().topOf(yawOutput), Pointing.DOWN, 30).rightClick();
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(yawOutput))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click a servo's output to set its arc limits. The gun never turns past them");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .text("It holds fire on targets outside them, and map tables draw its reach as a sector");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    // Traverse right (north-east, away from the viewer) and elevation.
    private static final float TURRET_TRAVERSE_DEG = -35, TURRET_ELEVATION_DEG = 15;
    // Out of sight: where the bearings' plates are kept until assembly moves them onto their bearings.
    private static final BlockPos YAW_PLATE_STORE = new BlockPos(7, 4, 0), PITCH_PLATE_STORE = new BlockPos(7, 3, 0);

    /**
     * Structure {@code cannon_servo_swivel.nbt}: 8×8 floor, battle network in at (8, 0, 2) and along x = 7. The yaw
     * servo (4, 1, 2) turns the swivel bearing (2, 2, 4), facing up, by the cog (2, 2, 3). Yaw cradle: the gearbox
     * (2, 3, 4), the pitch servo (2, 4, 5) and the swivel bearing (1, 3, 4), facing west, turned by (2, 3, 5) and the
     * cog (1, 3, 5). Pitch cradle: from (0, 3, 4) round to x = 4, fixed mounts at z = 7 read by interfaces, cannons
     * unassembled on top facing north. Both axes cross at (2.5, 3.5, 4.5). Saved with the cannon side still.
     */
    public static void swivelTurret(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("cannon_servo_swivel", "Building Turrets with Swivel Bearings");
        scene.configureBasePlate(0, 0, 8);
        scene.world().setBlock(YAW_PLATE_STORE, plate(Direction.UP), false);
        scene.world().setBlock(PITCH_PLATE_STORE, plate(Direction.WEST), false);

        BlockPos yawOutput = util.grid().at(4, 1, 2);
        BlockPos yawBearing = util.grid().at(2, 2, 4);
        BlockPos pitchInput = util.grid().at(2, 4, 5);
        BlockPos pitchBearing = util.grid().at(1, 3, 4);
        BlockPos westInterface = util.grid().at(0, 3, 6);
        BlockPos westMount = util.grid().at(0, 3, 7), eastMount = util.grid().at(4, 3, 7);
        BlockPos westLever = util.grid().at(1, 3, 7), eastLever = util.grid().at(3, 3, 7);
        BlockPos computer = util.grid().at(5, 1, 6);
        Vec3 pivot = util.vector().of(2.5, 3.5, 4.5);

        Selection floor = util.select().fromTo(0, 0, 0, 7, 0, 7);
        Selection drive = util.select().fromTo(8, 0, 2, 8, 1, 2);
        Selection battleNetwork = util.select().fromTo(7, 1, 2, 7, 1, 6)
                .add(util.select().fromTo(2, 1, 4, 6, 1, 4))
                .add(util.select().position(6, 1, 2));
        Selection computerBranch = util.select().fromTo(5, 1, 6, 6, 1, 6);
        Selection yawServo = util.select().fromTo(4, 1, 2, 5, 1, 2);
        Selection yawLinkage = util.select().fromTo(2, 1, 2, 3, 1, 2)
                .add(util.select().position(2, 1, 3))
                .add(util.select().position(2, 2, 3));
        // The cannon sides, saved still: each turns one way throughout (see the scene's Sources).
        Selection yawDrive = util.select().position(yawOutput).add(yawLinkage);
        Selection pitchDrive = util.select().fromTo(2, 4, 6, 2, 4, 7)
                .add(util.select().fromTo(2, 3, 5, 2, 3, 7))
                .add(util.select().position(1, 3, 5));
        Selection yawCradle = util.select().position(2, 3, 4)
                .add(util.select().position(pitchBearing))
                .add(util.select().fromTo(1, 3, 5, 2, 3, 5))
                .add(util.select().fromTo(2, 3, 6, 2, 3, 7))
                .add(util.select().fromTo(2, 4, 4, 2, 4, 7));
        Selection pitchCradle = util.select().fromTo(0, 3, 4, 0, 3, 7)
                .add(util.select().fromTo(0, 2, 5, 4, 2, 5))
                .add(util.select().fromTo(3, 3, 4, 4, 3, 4))
                .add(util.select().fromTo(4, 3, 5, 4, 3, 7))
                .add(util.select().position(westLever))
                .add(util.select().position(eastLever));
        Selection cannons = util.select().fromTo(0, 4, 1, 0, 4, 7)
                .add(util.select().fromTo(4, 4, 1, 4, 4, 7));

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(battleNetwork, Direction.DOWN);
        scene.world().showSection(computerBranch, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(yawServo, Direction.DOWN);
        scene.world().showSection(yawLinkage, Direction.DOWN);
        scene.world().showSection(util.select().position(yawBearing), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(yawBearing, Direction.EAST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Cannon Servo can also turn a Swivel Bearing");
        scene.idle(PonderTiming.AFTER_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(2, 2, 3))
                .placeNearTarget()
                .text("Its output turns the bearing's cog, and the turret on the bearing trains with it");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        ElementLink<WorldSectionElement> turret = scene.world().showIndependentSection(yawCradle, Direction.DOWN);
        scene.world().configureCenterOfRotation(turret, pivot);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, "turret", yawCradle, PonderTiming.TEXT);
        scene.overlay().showControls(util.vector().topOf(2, 4, 4), Pointing.DOWN, 40)
                .withItem(AllItems.SUPER_GLUE.asStack());
        scene.idle(15);
        scene.effects().superGlue(util.grid().at(2, 3, 4), Direction.UP, true);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(2, 4, 4))
                .placeNearTarget()
                .text("Glue the turret together on top of the bearing");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(pitchBearing, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A second Swivel Bearing on the turret, turned by its own servo, tilts a cradle");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        ElementLink<WorldSectionElement> cradle = scene.world().showIndependentSection(pitchCradle, Direction.DOWN);
        scene.world().configureCenterOfRotation(cradle, pivot);
        scene.idle(10);
        scene.world().showSectionAndMerge(cannons, Direction.DOWN, cradle);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, "cradle", pitchCradle.copy().add(cannons), PonderTiming.LONG_TEXT);
        scene.overlay().showControls(util.vector().topOf(0, 3, 4), Pointing.DOWN, 40)
                .withItem(AllItems.SUPER_GLUE.asStack());
        scene.idle(15);
        scene.effects().superGlue(util.grid().at(0, 3, 4), Direction.WEST, true);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(0, 3, 5))
                .placeNearTarget()
                .text("Glue the cradle to it. It can carry several guns, all laid together");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().topOf(2, 4, 4))
                .placeNearTarget()
                .text("The Battle Network passes through each bearing to the servo and interfaces it carries");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(westInterface, Direction.WEST))
                .placeNearTarget()
                .text("A Network Interface facing each Fixed Cannon Mount reads its gun for the computer");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(pitchInput))
                .placeNearTarget()
                .text("Each servo drives one bearing alone: a second bearing on its network is an error");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        for (BlockPos lever : new BlockPos[] {westLever, eastLever}) {
            scene.world().modifyBlock(lever, state -> state.setValue(BlockStateProperties.POWERED, true), false);
            scene.effects().createRedstoneParticles(lever, 0xFF0000, 10);
        }
        scene.world().modifyBlock(westMount, CannonAim::assembled, false);
        scene.world().modifyBlock(eastMount, CannonAim::assembled, false);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(westMount, Direction.WEST))
                .placeNearTarget()
                .text("Assemble the cannons as usual");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The computer finds a solution: the turret trains and the cradle elevates together.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("With a firing solution, one servo trains the turret and the other elevates the cradle");
        scene.idle(30);
        // The first turn of their cogs assembles the bearings: each plate now rides with what it carries.
        ElementLink<WorldSectionElement> yawPlate = assembleSwivel(scene, util, yawBearing, YAW_PLATE_STORE, pivot, -ServoDrive.TRAINING_RPM);
        ElementLink<WorldSectionElement> pitchPlate = assembleSwivel(scene, util, pitchBearing, PITCH_PLATE_STORE, pivot, ServoDrive.TRAINING_RPM);
        // Left (anticlockwise from above) winds the yaw cog forward; up winds the pitch cog forward, facing west.
        float yawSense = Math.signum(TURRET_TRAVERSE_DEG);
        scene.world().setKineticSpeed(yawDrive, -yawSense * ServoDrive.TRAINING_RPM);
        setSwivelCogSpeed(scene, util, yawBearing, yawSense * ServoDrive.TRAINING_RPM);
        scene.world().setKineticSpeed(pitchDrive, -ServoDrive.TRAINING_RPM);
        setSwivelCogSpeed(scene, util, pitchBearing, ServoDrive.TRAINING_RPM);
        new CannonAim(scene, cradle, pitchPlate).facingNorth().withTurret(turret, yawPlate)
                .turn(TURRET_TRAVERSE_DEG, TURRET_ELEVATION_DEG, TRAINING_TICKS);
        scene.world().setKineticSpeed(yawDrive, 0);
        setSwivelCogSpeed(scene, util, yawBearing, 0);
        scene.world().setKineticSpeed(pitchDrive, 0);
        setSwivelCogSpeed(scene, util, pitchBearing, 0);
        scene.idle(40);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(yawOutput))
                .placeNearTarget()
                .text("A turret trains on the target even with its guns empty. The cradle waits for a loaded gun");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Fire.
        Vec3 barrel = CannonAim.turnedFacingNorth(Vec3.ZERO, new Vec3(0, 0, -1), TURRET_TRAVERSE_DEG, TURRET_ELEVATION_DEG);
        Vec3 westMuzzle = CannonAim.turnedFacingNorth(pivot, util.vector().blockSurface(util.grid().at(0, 4, 1), Direction.NORTH),
                TURRET_TRAVERSE_DEG, TURRET_ELEVATION_DEG);
        Vec3 eastMuzzle = CannonAim.turnedFacingNorth(pivot, util.vector().blockSurface(util.grid().at(4, 4, 1), Direction.NORTH),
                TURRET_TRAVERSE_DEG, TURRET_ELEVATION_DEG);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(westMuzzle)
                .placeNearTarget()
                .attachKeyFrame()
                .text("The guns on the cradle fire together as one salvo, given their shots land close enough");
        scene.idle(40);
        for (Vec3 muzzle : new Vec3[] {westMuzzle, eastMuzzle})
            scene.effects().emitParticles(muzzle.add(barrel.scale(0.5)),
                    scene.effects().particleEmitterWithinBlockSpace(new BigCannonPlumeParticleData(4f), barrel.scale(0.5)),
                    1, 1);
        scene.idle(PonderTiming.AFTER_LONG_TEXT - 40);
    }

    private static BlockState plate(Direction facing) {
        return SimBlocks.SWIVEL_BEARING_LINK_BLOCK.getDefaultState().setValue(SwivelBearingPlateBlock.FACING, facing);
    }

    /**
     * As Simulated: the bearing keeps only its base, and its plate (stored out of sight) is moved over it, to turn
     * about {@code pivot}. Ponder rotates a section after moving it, so its center is the pivot less the move.
     * The plate's shaft runs at the bearing's pass-through {@code rpm}.
     */
    private static ElementLink<WorldSectionElement> assembleSwivel(CreateSceneBuilder scene, SceneBuildingUtil util,
                                                                   BlockPos bearing, BlockPos store, Vec3 pivot, float rpm) {
        scene.world().modifyBlock(bearing, state -> state.setValue(SwivelBearingBlock.ASSEMBLED, true), false);
        scene.world().setKineticSpeed(util.select().position(store), rpm);
        ElementLink<WorldSectionElement> plate = scene.world().showIndependentSectionImmediately(util.select().position(store));
        Vec3 move = Vec3.atLowerCornerOf(bearing.subtract(store));
        scene.world().moveSection(plate, move, 0);
        scene.world().configureCenterOfRotation(plate, pivot.subtract(move));
        return plate;
    }

    /** Ponder keeps the cog's speed apart from the bearing's own (its pass-through shaft). */
    private static void setSwivelCogSpeed(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos bearing, float rpm) {
        scene.world().modifyBlockEntityNBT(util.select().position(bearing), SwivelBearingBlockEntity.class, nbt -> {
            CompoundTag cog = nbt.getCompound("SwivelCog");
            cog.putFloat("Speed", rpm);
            nbt.put("SwivelCog", cog);
        });
    }
}