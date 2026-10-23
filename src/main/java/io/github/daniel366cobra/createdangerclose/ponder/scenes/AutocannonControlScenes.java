package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.block.fire_mission_console.AutocannonControlConsoleBlockEntity;
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
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.recoil_spring.AutocannonRecoilSpringBlockEntity;
import rbasamoyai.createbigcannons.effects.particles.plumes.AutocannonPlumeParticleData;
import rbasamoyai.createbigcannons.ponder.CBCAnimateBlockEntityInstruction;

public final class AutocannonControlScenes {

    private AutocannonControlScenes() {}

    // Laying onto the first target, south-east.
    private static final float RANGEFINDER_LAY_DEG = 45, GUN_LAY_TRAVERSE_DEG = -45, GUN_LAY_ELEVATION_DEG = 20;
    private static final int LAY_TICKS = 50;
    // Then both follow the target, each pass this far.
    private static final float FIRST_PASS_DEG = 35, FIRST_PASS_ELEVATION_DEG = 10;
    private static final int FIRST_PASS_TICKS = 70;
    private static final float AUTO_PASS_DEG = -70, AUTO_PASS_ELEVATION_DEG = -5;
    private static final int AUTO_PASS_TICKS = 120;
    private static final float CEASE_PASS_DEG = -20;
    private static final int CEASE_PASS_TICKS = 40;
    // The trigger repeats this fast while held.
    private static final int ROUND_TICKS = 4;
    // As CBC's autocannon scene: kick back in one tick, run out over a few; animation cut to one round.
    private static final int FIRE_TICKS = 1, RUN_OUT_TICKS = 3, RECOIL_ANIMATION_TICKS = ROUND_TICKS;
    private static final double RECOIL_BLOCKS = 0.5;
    private static final int TRIGGER_HOLD_TICKS = 40;
    private static final int FUZE_DEMO_NOTCHES = 3;

    private static final BlockPos CANNON_PIVOT = new BlockPos(5, 3, 0);
    // From the breech.
    private static final double MUZZLE_REACH = 2.5;

    /**
     * Structure {@code autocannon_control.nbt}: 7×6 floor, power column at (7, 0..1, 5), battle network
     * along z = 5. Rangefinder Bearing (2, 2, 5) looking south; servo inputs x = 0 (traverse) and x = 1
     * (elevation); computer interface (5, 1, 3) with Differentiator and Integrator; console interface
     * (3, 1, 2); autocannon mount (5, 1, 0), barrel east, hopper on the west.
     */
    public static void autocannonControl(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("autocannon_control", "Laying autocannons with the Autocannon Control Console");
        scene.configureBasePlate(0, 0, 7);

        BlockPos console = util.grid().at(3, 1, 2);
        BlockPos consoleInterface = util.grid().at(3, 1, 3);
        BlockPos computer = util.grid().at(5, 1, 3);
        BlockPos mount = util.grid().at(5, 1, 0);
        BlockPos bearing = util.grid().at(2, 2, 5);
        BlockPos eyepiece = util.grid().at(2, 3, 4);

        Selection floor = util.select().fromTo(0, 0, 0, 6, 0, 5);
        Selection drive = util.select().fromTo(7, 0, 5, 7, 1, 5);
        Selection network = util.select().fromTo(0, 1, 5, 6, 1, 5)
                .add(util.select().fromTo(0, 1, 4, 1, 1, 4))
                .add(util.select().position(3, 1, 4))
                .add(util.select().position(5, 1, 4));
        Selection computerStack = util.select().position(computer)
                .add(util.select().fromTo(5, 2, 3, 5, 2, 4));
        Selection servos = util.select().fromTo(0, 1, 2, 1, 1, 3);
        // Servo outputs to the mount (traverse gearing in the floor layer), saved still.
        Selection mountSelection = util.select().position(mount);
        Drives drives = new Drives(
                ServoDrive.yaw(util.select().fromTo(1, 0, 0, 5, 0, 0),
                        util.select().fromTo(0, 1, 0, 0, 1, 2).add(util.select().position(0, 0, 0)), mountSelection),
                ServoDrive.pitch(util.select().fromTo(1, 1, 1, 1, 1, 2),
                        util.select().fromTo(2, 1, 1, 5, 1, 1), mountSelection));
        Selection gunDrives = util.select().fromTo(0, 1, 0, 0, 1, 1)
                .add(util.select().fromTo(1, 1, 1, 5, 1, 1));
        Selection mountAndFeed = util.select().position(mount).add(util.select().position(4, 1, 0));
        Selection tubeBlocks = util.select().fromTo(2, 3, 5, 2, 4, 5)
                .add(util.select().fromTo(0, 4, 5, 4, 4, 5))
                .add(util.select().position(eyepiece));

        // The console faces north: left to right (east to west) fuze knob, auto switch, trigger (model pixels).
        Vec3 fuzeKnob = onConsole(console, 12.5, 17, 7.5);
        Vec3 autoSwitch = onConsole(console, 8.5, 18, 7.5);
        Vec3 trigger = onConsole(console, 3.5, 19.5, 5.5);

        scene.world().modifyBlock(mount, CannonAim::assembled, false);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.world().showSection(computerStack, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(servos, Direction.DOWN);
        scene.world().showSection(gunDrives, Direction.DOWN);
        scene.world().showSection(mountAndFeed, Direction.DOWN);
        // The barrel apart, so it can recoil into the spring.
        ElementLink<WorldSectionElement> cannon =
                scene.world().showIndependentSection(util.select().fromTo(5, 3, 0, 6, 3, 0), Direction.DOWN);
        ElementLink<WorldSectionElement> barrel =
                scene.world().showIndependentSection(util.select().position(7, 3, 0), Direction.DOWN);
        scene.world().configureCenterOfRotation(cannon, util.vector().centerOf(CANNON_PIVOT));
        scene.world().configureCenterOfRotation(barrel, util.vector().centerOf(CANNON_PIVOT));
        Gun gun = new Gun(new CannonAim(scene, cannon, barrel), barrel);
        CannonAim aim = gun.aim();
        scene.idle(10);
        scene.world().showSection(util.select().position(bearing), Direction.DOWN);
        ElementLink<WorldSectionElement> tube = scene.world().showIndependentSection(tubeBlocks, Direction.DOWN);
        scene.world().configureCenterOfRotation(tube, util.vector().centerOf(2, 3, 5));
        scene.idle(15);

        scene.world().showSection(util.select().position(consoleInterface), Direction.SOUTH);
        scene.idle(10);
        scene.world().showSection(util.select().position(console), Direction.DOWN);
        // One autocannon on the network, not yet on target; the trigger only.
        showGunsReady(scene, console, 0);
        showWeaponsFree(scene, console, false);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(console, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Autocannon Control Console gives the orders to the autocannons on its network");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // The rangefinder picks up a target; the gun lays onto it.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(eyepiece, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Their servos lay them onto the target the rangefinder is ranging, like the big guns");
        scene.idle(20);
        scene.world().rotateBearing(bearing, RANGEFINDER_LAY_DEG, LAY_TICKS);
        scene.world().rotateSection(tube, 0, RANGEFINDER_LAY_DEG, 0, LAY_TICKS);
        train(scene, drives, mount, gun, GUN_LAY_TRAVERSE_DEG, GUN_LAY_ELEVATION_DEG, LAY_TICKS, null);
        showGunsReady(scene, console, 1);
        scene.idle(PonderTiming.AFTER_LONG_TEXT - 20 - LAY_TICKS);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(bearing))
                .placeNearTarget()
                .text("As the target moves, the guns follow it with the rangefinder");
        track(scene, bearing, tube, drives, mount, gun, FIRST_PASS_DEG, FIRST_PASS_ELEVATION_DEG,
                FIRST_PASS_TICKS, null);
        scene.idle(PonderTiming.AFTER_TEXT - FIRST_PASS_TICKS);

        // The trigger, held.
        scene.overlay().showControls(trigger, Pointing.DOWN, TRIGGER_HOLD_TICKS).rightClick();
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(trigger)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Hold right-click on the trigger to fire every autocannon on target");
        for (int t = 0; t < TRIGGER_HOLD_TICKS; t += ROUND_TICKS) {
            pullTrigger(scene, console);
            fire(scene, util, gun);
            scene.idle(ROUND_TICKS - FIRE_TICKS);
        }
        scene.idle(PonderTiming.AFTER_TEXT - TRIGGER_HOLD_TICKS);

        // Weapons free.
        scene.overlay().showControls(autoSwitch, Pointing.DOWN, 30).rightClick();
        showWeaponsFree(scene, console, true);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(autoSwitch)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click the switch in the middle for AUTO: weapons free");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(muzzle(util, aim))
                .placeNearTarget()
                .text("In AUTO, the autocannons fire by themselves whenever they are on a target being ranged");
        track(scene, bearing, tube, drives, mount, gun, AUTO_PASS_DEG, AUTO_PASS_ELEVATION_DEG,
                AUTO_PASS_TICKS, util);
        scene.idle(Math.max(0, PonderTiming.AFTER_LONG_TEXT - AUTO_PASS_TICKS));

        // Ranging stops: the computer only holds the target, and the guns cease fire.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(eyepiece, Direction.NORTH))
                .placeNearTarget()
                .text("Once the rangefinder stops ranging, they hold their fire: a held target drifts");
        track(scene, bearing, tube, drives, mount, gun, CEASE_PASS_DEG, 0, CEASE_PASS_TICKS, null);
        scene.idle(PonderTiming.AFTER_LONG_TEXT - CEASE_PASS_TICKS);

        // Map table targets count as live: they fire on those too.
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(computer.above()))
                .placeNearTarget()
                .attachKeyFrame()
                .text("They also fire on a target marked on a Map Table on the network");
        scene.idle(20);
        for (int t = 0; t < TRIGGER_HOLD_TICKS; t += ROUND_TICKS) {
            fire(scene, util, gun);
            scene.idle(ROUND_TICKS - FIRE_TICKS);
        }
        scene.idle(PonderTiming.AFTER_TEXT - 20 - TRIGGER_HOLD_TICKS);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(muzzle(util, aim))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Autocannons always fire direct, even when a Fire Mission Console orders plunging fire");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(trigger)
                .placeNearTarget()
                .text("Danger close is never fired by itself: let go of the trigger and pull it again to confirm");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // The fuze correction.
        scene.overlay().showControls(fuzeKnob, Pointing.DOWN, 40).scroll();
        scene.idle(10);
        for (int notch = 1; notch <= FUZE_DEMO_NOTCHES; notch++) {
            showFuzeOffset(scene, console, -notch);
            scene.idle(5);
        }
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(fuzeKnob)
                .placeNearTarget()
                .attachKeyFrame()
                .text("Scroll over the knob on the left to correct the rounds' timed fuzes, a tick at a time");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /** Model pixels as it faces (north). */
    private static Vec3 onConsole(BlockPos console, double x, double y, double z) {
        return Vec3.atLowerCornerOf(console).add(x / 16, y / 16, z / 16);
    }

    private static void showGunsReady(CreateSceneBuilder scene, BlockPos console, int ready) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class, be -> be.showGunsReady(ready, 1));
    }

    private static void showWeaponsFree(CreateSceneBuilder scene, BlockPos console, boolean free) {
        scene.world().modifyBlockEntity(console, AutocannonControlConsoleBlockEntity.class, be -> be.showWeaponsFree(free));
    }

    private static void showFuzeOffset(CreateSceneBuilder scene, BlockPos console, int ticks) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class, be -> be.showFuzeOffset(ticks));
    }

    private static void pullTrigger(CreateSceneBuilder scene, BlockPos console) {
        scene.world().modifyBlockEntity(console, FireMissionConsoleBlockEntity.class,
                FireMissionConsoleBlockEntity::animateButtonPress);
    }

    /** Turns rangefinder and gun together (left, seen from above), firing if {@code firingUtil} is given. */
    private static void track(CreateSceneBuilder scene, BlockPos bearing, ElementLink<WorldSectionElement> tube,
                              Drives drives, BlockPos mount, Gun gun,
                              float turnDeg, float elevationDeg, int ticks,
                              SceneBuildingUtil firingUtil) {
        scene.world().rotateBearing(bearing, turnDeg, ticks);
        scene.world().rotateSection(tube, 0, turnDeg, 0, ticks);
        train(scene, drives, mount, gun, turnDeg, elevationDeg, ticks, firingUtil);
    }

    /** Traverse (left) and elevate, running the moving axes' drives; fires every {@link #ROUND_TICKS} if {@code firingUtil} is given. */
    private static void train(CreateSceneBuilder scene, Drives drives, BlockPos mount, Gun gun,
                              float traverseDeg, float elevationDeg, int ticks,
                              SceneBuildingUtil firingUtil) {
        CannonAim aim = gun.aim();
        drives.yaw().lay(scene, traverseDeg);
        drives.pitch().lay(scene, elevationDeg);
        // CBC's mount yaw grows the other way round to Ponder's rotation.
        if (traverseDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountYaw(mount, -traverseDeg, ticks));
        if (elevationDeg != 0)
            scene.addInstruction(CBCAnimateBlockEntityInstruction.cannonMountPitch(mount, elevationDeg, ticks));
        if (firingUtil == null) {
            aim.turn(traverseDeg, elevationDeg, ticks);
        } else {
            // Each round holds the gun still for the tick of its recoil, then turns on.
            for (int done = 0; done < ticks; done += ROUND_TICKS) {
                int step = Math.min(ROUND_TICKS, ticks - done);
                fire(scene, firingUtil, gun);
                if (step > FIRE_TICKS)
                    aim.turn(traverseDeg * step / ticks, elevationDeg * step / ticks, step - FIRE_TICKS);
            }
        }
        drives.yaw().stop(scene);
        drives.pitch().stop(scene);
    }

    private record Drives(ServoDrive yaw, ServoDrive pitch) {}

    private record Gun(CannonAim aim, ElementLink<WorldSectionElement> barrel) {}

    /** One round as CBC's own scene plays it. Takes {@link #FIRE_TICKS}; the barrel runs out over the next few. */
    private static void fire(CreateSceneBuilder scene, SceneBuildingUtil util, Gun gun) {
        CannonAim aim = gun.aim();
        Vec3 barrel = CannonAim.barrelDirection(aim.yaw(), aim.pitch());
        scene.effects().emitParticles(muzzle(util, aim),
                scene.effects().particleEmitterWithinBlockSpace(new AutocannonPlumeParticleData(1f), barrel.scale(0.5)),
                1, 1);
        // Section offsets are in world space, outside the section's rotation: back along the barrel.
        Vec3 kick = barrel.scale(-RECOIL_BLOCKS);
        scene.world().moveSection(gun.barrel(), kick, 1);
        BlockPos breech = util.grid().at(5, 3, 0), spring = util.grid().at(6, 3, 0);
        scene.world().modifyBlockEntityNBT(util.select().position(breech), AbstractAutocannonBreechBlockEntity.class,
                nbt -> nbt.putInt("AnimateTicks", 0));
        scene.world().modifyBlockEntityNBT(util.select().position(spring), AutocannonRecoilSpringBlockEntity.class,
                nbt -> nbt.putInt("AnimateTicks", 0));
        scene.addInstruction(CBCAnimateBlockEntityInstruction.autocannon(breech, RECOIL_ANIMATION_TICKS));
        scene.addInstruction(CBCAnimateBlockEntityInstruction.autocannon(spring, RECOIL_ANIMATION_TICKS));
        scene.idle(FIRE_TICKS);
        scene.world().moveSection(gun.barrel(), kick.reverse(), RUN_OUT_TICKS);
    }

    private static Vec3 muzzle(SceneBuildingUtil util, CannonAim aim) {
        return util.vector().centerOf(CANNON_PIVOT)
                .add(CannonAim.barrelDirection(aim.yaw(), aim.pitch()).scale(MUZZLE_REACH));
    }
}