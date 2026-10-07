package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.content.kinetics.gauge.StressGaugeBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Ponder scenes for the Kinetic Network Coupler. */
public final class KineticNetworkCouplerScenes {

    private KineticNetworkCouplerScenes() {}

    // The structure was saved at 32 RPM, the default floor speed: the scene runs at twice
    // that, so the coupler has room to gear down before it overstresses.
    private static final float SCENE_SPEED_FACTOR = 2;
    private static final float FLOOR_RPM = 32;
    // Stressometer readings (share of capacity), and the overstressed reading.
    private static final float BASE_ONLY = 0.3f, BASE_ONE_WHEEL = 0.55f, BASE_TWO_WHEELS = 0.85f;
    private static final float ISOLATED_ONE_WHEEL = 0.5f, ISOLATED_TWO_WHEELS = 0.9f;
    private static final float OVERSTRESSED = 1.125f;

    /**
     * Structure {@code kinetic_network_coupler.nbt}: an 8×8 floor (the bottom layer), a
     * cogwheel column at (8, 0..1, 2) bringing power from off the scene into a gearbox at
     * (7, 1, 2). The base network runs south from it and up to two Mechanical Presses over
     * depots, with a Stressometer at (3, 3, 5); west, a shaft feeds the coupler's input
     * half at (5, 1, 2), facing west, with its output half at (4, 1, 2). The isolated
     * network behind it has its own Stressometer at (3, 1, 2) and gearboxes up to a line of
     * three crushing wheels at (1, 2, 3..5).
     */
    public static void kineticNetworkCoupler(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("kinetic_network_coupler", "Running separate networks with the Kinetic Network Coupler");
        scene.configureBasePlate(0, 0, 8);

        BlockPos couplerInput = util.grid().at(5, 1, 2);
        BlockPos couplerOutput = util.grid().at(4, 1, 2);
        BlockPos baseGauge = util.grid().at(3, 3, 5);
        BlockPos isolatedGauge = util.grid().at(3, 1, 2);
        BlockPos secondWheel = util.grid().at(1, 2, 4);
        BlockPos thirdWheel = util.grid().at(1, 2, 5);

        Selection floor = util.select().fromTo(0, 0, 0, 7, 0, 7);
        Selection drive = util.select().fromTo(8, 0, 2, 8, 1, 2);
        Selection base = util.select().fromTo(7, 1, 2, 7, 1, 5)
                .add(util.select().position(7, 2, 5))
                .add(util.select().fromTo(3, 3, 5, 7, 3, 5))
                .add(util.select().fromTo(4, 1, 5, 5, 1, 5));
        Selection feed = util.select().position(6, 1, 2);
        Selection coupler = util.select().position(couplerInput).add(util.select().position(couplerOutput));
        Selection isolatedLine = util.select().fromTo(1, 1, 2, 3, 1, 2)
                .add(util.select().position(1, 2, 2))
                .add(util.select().position(1, 2, 3));
        // The isolated network's two halves, by sense of rotation (as saved).
        Selection isolatedBackward = util.select().fromTo(1, 1, 2, 4, 1, 2);
        Selection isolatedForward = util.select().fromTo(1, 2, 2, 1, 2, 5);
        Selection baseNetwork = base.copy().add(feed).add(util.select().position(couplerInput));

        scene.world().multiplyKineticSpeed(util.select().everywhere(), SCENE_SPEED_FACTOR);
        setGauge(scene, util, baseGauge, BASE_ONLY);
        setGauge(scene, util, isolatedGauge, ISOLATED_ONE_WHEEL);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(base, Direction.DOWN);
        scene.idle(15);
        scene.world().showSection(feed, Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(couplerInput), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().position(couplerOutput), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(couplerInput, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Kinetic Network Coupler runs a separate network off another one's rotation");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.world().showSection(isolatedLine, Direction.EAST);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.BLUE, "base", baseNetwork, PonderTiming.LONG_TEXT);
        scene.overlay().showOutline(PonderPalette.GREEN, "isolated", isolatedLine.copy().add(coupler), PonderTiming.LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(couplerOutput))
                .placeNearTarget()
                .text("Its output half turns a network of its own at the same speed: the two never join");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // What the output draws is charged to the input side.
        setGauge(scene, util, baseGauge, BASE_ONE_WHEEL);
        scene.effects().indicateRedstone(baseGauge);
        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(baseGauge, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("What the output network draws is charged to the input side as stress");
        scene.idle(PonderTiming.AFTER_TEXT);

        // More load on the output side than the input side can spare: geared down.
        scene.world().showSection(util.select().position(secondWheel), Direction.DOWN);
        scene.idle(10);
        setGauge(scene, util, isolatedGauge, ISOLATED_TWO_WHEELS);
        setGauge(scene, util, baseGauge, BASE_TWO_WHEELS);
        setIsolatedSpeed(scene, isolatedBackward, isolatedForward, FLOOR_RPM);
        scene.effects().rotationSpeedIndicator(couplerOutput);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().topOf(couplerOutput))
                .placeNearTarget()
                .attachKeyFrame()
                .text("When the input side cannot spare enough, it gears the output network down to what it can afford");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Still more: overstressed, while the input side runs on.
        scene.world().showSection(util.select().position(thirdWheel), Direction.DOWN);
        scene.idle(10);
        setIsolatedSpeed(scene, isolatedBackward, isolatedForward, 0);
        setGauge(scene, util, isolatedGauge, OVERSTRESSED);
        setGauge(scene, util, baseGauge, BASE_ONLY);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().blockSurface(isolatedGauge, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Short of even its floor speed, the output network is overstressed and decoupled, as the input side runs on");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Load taken off: it comes back by itself.
        scene.world().hideSection(util.select().position(thirdWheel), Direction.UP);
        scene.idle(15);
        setIsolatedSpeed(scene, isolatedBackward, isolatedForward, FLOOR_RPM);
        setGauge(scene, util, isolatedGauge, ISOLATED_TWO_WHEELS);
        setGauge(scene, util, baseGauge, BASE_TWO_WHEELS);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(couplerOutput))
                .placeNearTarget()
                .text("Once there is power to spare again, the output restarts by itself");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The floor speed.
        Vec3 valueBox = util.vector().blockSurface(couplerInput, Direction.SOUTH);
        scene.overlay().showFilterSlotInput(valueBox, Direction.SOUTH, PonderTiming.LONG_TEXT);
        scene.overlay().showControls(valueBox, Pointing.DOWN, 60).scroll();
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(valueBox)
                .placeNearTarget()
                .attachKeyFrame()
                .text("The value box on the input half's side sets the floor speed");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(7, 1, 2))
                .placeNearTarget()
                .text("Couplers on the same network split its spare power equally");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().topOf(couplerOutput))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The coupler can be used to isolate battle networks from each other and the rest of the ship");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    private static void setGauge(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos gauge, float value) {
        scene.world().modifyBlockEntityNBT(util.select().position(gauge), StressGaugeBlockEntity.class,
                nbt -> nbt.putFloat("Value", value));
    }

    /** Sets the isolated network turning at this speed, each part its own way round as built. */
    private static void setIsolatedSpeed(CreateSceneBuilder scene, Selection backward, Selection forward, float rpm) {
        scene.world().setKineticSpeed(backward, -rpm);
        scene.world().setKineticSpeed(forward, rpm);
    }
}
