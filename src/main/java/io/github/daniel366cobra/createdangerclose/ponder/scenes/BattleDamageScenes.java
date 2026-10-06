package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.api.damage.Fault;
import io.github.daniel366cobra.createdangerclose.block.ballistic_computer.BallisticComputerBlockEntity;
import io.github.daniel366cobra.createdangerclose.damage.EquipmentHealth;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Ponder scene for battle damage, repairs and upkeep of the mechanical equipment. */
public final class BattleDamageScenes {

    private BattleDamageScenes() {}

    // What the computer's modules show turning: the network the structure was saved on.
    private static final float NETWORK_RPM = 64;
    // How long a click hint stays up.
    private static final int CLICK_HINT_TICKS = 30;

    /**
     * Structure {@code battle_damage.nbt}: a 7×6 floor, power coming in from a cogwheel
     * column at its east edge and along the south side. On the east, a computer bank
     * facing west: the Ballistic Computer at (6, 1, 2) with a Differentiator beside it,
     * an Integrator and a Fuze Computer on top, and a Network Interface behind it. On the
     * west, two Cannon Servos facing north, each driving a shaft down into the floor.
     */
    public static void battleDamage(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("battle_damage", "Battle damage and upkeep");
        scene.configureBasePlate(0, 0, 7);

        BlockPos core = util.grid().at(6, 1, 2);
        BlockPos differentiator = util.grid().at(6, 1, 1);
        BlockPos integrator = util.grid().at(6, 2, 1);
        BlockPos fuzeComputer = util.grid().at(6, 2, 2);
        BlockPos servoOutput = util.grid().at(3, 1, 2);
        BlockPos servoInput = util.grid().at(3, 1, 3);

        Selection floor = util.select().fromTo(0, 0, 0, 6, 0, 5);
        Selection drive = util.select().fromTo(7, 0, 5, 7, 1, 5)
                .add(util.select().fromTo(1, 1, 4, 6, 1, 5));
        Selection servos = util.select().fromTo(1, 1, 0, 1, 1, 3).add(util.select().fromTo(3, 1, 0, 3, 1, 3));
        Selection bank = util.select().fromTo(6, 1, 1, 6, 2, 2).add(util.select().position(6, 1, 3));

        scene.world().showSection(floor, Direction.UP);
        scene.world().modifyBlockEntity(core, BallisticComputerBlockEntity.class,
                computer -> computer.setPonderNetworkRpm(NETWORK_RPM));
        scene.idle(10);
        scene.world().showSection(drive, Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(bank, Direction.DOWN);
        scene.world().showSection(servos, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(core))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Fire control equipment can be damaged in battle");
        scene.idle(PonderTiming.AFTER_TEXT);

        // A shell bursts just off the bank, by the Differentiator.
        burst(scene, util, new Vec3(6.5, 1.4, 0.5));
        setFault(scene, differentiator, Fault.BROKEN_MECHANISM, true);
        setFault(scene, core, Fault.JOSTLED, true);
        setFault(scene, integrator, Fault.JOSTLED, true);
        scene.idle(10);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().blockSurface(differentiator, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A close blast, or a shell or shrapnel passing through, breaks it: it stops working until repaired");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(core, Direction.WEST))
                .placeNearTarget()
                .text("A blast further off jostles it: it still works, but throws every aim off a little");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Repairs: realign the jostled ones, mend the broken one.
        scene.overlay().showControls(util.vector().blockSurface(core, Direction.WEST), Pointing.LEFT, CLICK_HINT_TICKS)
                .leftClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(15);
        setFault(scene, core, Fault.JOSTLED, false);
        // Let the first click's hint go (15 of its ticks are past) before the second shows.
        scene.idle(CLICK_HINT_TICKS - 15 + 5);
        scene.overlay().showControls(util.vector().blockSurface(integrator, Direction.WEST), Pointing.LEFT, CLICK_HINT_TICKS)
                .leftClick()
                .withItem(AllItems.WRENCH.asStack());
        scene.idle(15);
        setFault(scene, integrator, Fault.JOSTLED, false);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(core, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Left-click jostled equipment with a Wrench to set it true");
        scene.idle(PonderTiming.AFTER_TEXT);

        // On its north face, clear of the text pointing at the west one.
        scene.overlay().showControls(util.vector().blockSurface(differentiator, Direction.NORTH), Pointing.DOWN, 30)
                .rightClick()
                .withItem(AllItems.PRECISION_MECHANISM.asStack());
        scene.idle(15);
        setFault(scene, differentiator, Fault.BROKEN_MECHANISM, false);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(differentiator, Direction.WEST))
                .placeNearTarget()
                .text("Right-click a broken mechanism with a Precision Mechanism");
        scene.idle(PonderTiming.AFTER_TEXT);

        // A cog this time: the servos (and interfaces) break those.
        burst(scene, util, new Vec3(3.5, 1.8, 0.2));
        setFault(scene, servoOutput, Fault.BROKEN_COG, true);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(servoOutput))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Servos and Network Interfaces break a cog instead");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showControls(util.vector().topOf(servoInput), Pointing.DOWN, 30)
                .rightClick()
                .withItem(AllBlocks.COGWHEEL.asStack());
        scene.idle(15);
        setFault(scene, servoOutput, Fault.BROKEN_COG, false);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(servoInput))
                .placeNearTarget()
                .text("Right-click it with a Cogwheel: either half of a servo will do");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Upkeep.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(fuzeComputer))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Mechanisms dry out over the days, faster the faster their network runs: power down to make it last");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        setFault(scene, fuzeComputer, Fault.SEIZED, true);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(util.vector().topOf(fuzeComputer))
                .placeNearTarget()
                .text("Run dry, a working mechanism may seize, and stops");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showControls(util.vector().topOf(fuzeComputer), Pointing.DOWN, 30)
                .rightClick()
                .withItem(new ItemStack(Items.HONEYCOMB));
        scene.idle(10);
        AABB bankBox = new AABB(Vec3.atLowerCornerOf(util.grid().at(6, 1, 1)),
                Vec3.atLowerCornerOf(util.grid().at(7, 3, 3)));
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, bankBox, bankBox, 60);
        setFault(scene, fuzeComputer, Fault.SEIZED, false);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(fuzeComputer))
                .placeNearTarget()
                .text("Right-click with Honeycomb: one click lubricates the whole computer bank, and frees what has seized");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /**
     * Structure {@code piped_lubrication.nbt}: a 7×6 floor, power in from a cogwheel column
     * at the east edge. A computer bank in a row facing north (Fuze Computer, Integrator,
     * Differentiator, Ballistic Computer from west to east) with its Network Interface
     * behind the core, a Cannon Servo facing west in the south-west corner, and in the
     * south-east a tank of spindle oil over a pump, piping down into the interface and the
     * servo. A second run lies across the tops of the cabinets: hidden until the end.
     */
    public static void pipedLubrication(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("piped_lubrication", "Lubricating by pipe");
        scene.configureBasePlate(0, 0, 7);

        BlockPos tank = util.grid().at(5, 4, 4);
        BlockPos networkInterface = util.grid().at(5, 1, 2);
        BlockPos servoInput = util.grid().at(2, 1, 4);
        BlockPos teeAboveInterface = util.grid().at(5, 2, 2);

        Selection floor = util.select().fromTo(0, 0, 0, 6, 0, 5);
        Selection drive = util.select().fromTo(7, 0, 4, 7, 1, 4)
                .add(util.select().fromTo(3, 1, 4, 6, 1, 4))
                .add(util.select().position(0, 1, 4))
                .add(util.select().position(5, 1, 3));
        Selection servo = util.select().fromTo(1, 1, 4, 2, 1, 4);
        Selection bank = util.select().fromTo(2, 1, 1, 5, 1, 1).add(util.select().position(networkInterface));
        Selection oilSupply = util.select().fromTo(5, 3, 4, 5, 4, 4)
                .add(util.select().fromTo(6, 2, 4, 6, 3, 4));
        Selection pipes = util.select().fromTo(2, 2, 4, 5, 2, 4).add(util.select().fromTo(5, 2, 2, 5, 2, 3));
        Selection redundantPipes = util.select().fromTo(2, 2, 1, 5, 2, 1);

        // Until the run over the cabinets is laid, the pipe over the interface has no branch north.
        scene.world().modifyBlock(teeAboveInterface, state -> state.setValue(PipeBlock.NORTH, false), false);
        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.DOWN);
        scene.world().showSection(bank, Direction.DOWN);
        scene.world().showSection(servo, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(oilSupply, Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(pipes, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(tank, Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Mechanisms can also be lubricated by pipe: with Spindle Oil, or another mod's lubricating or plant oil");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(tank, Direction.WEST))
                .placeNearTarget()
                .text("Spindle Oil is pressed from Honeycomb and Water in a heated Basin");
        scene.idle(PonderTiming.AFTER_TEXT);

        AABB bankBox = new AABB(Vec3.atLowerCornerOf(util.grid().at(2, 1, 1)),
                Vec3.atLowerCornerOf(util.grid().at(6, 2, 3)));
        scene.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, bankBox, bankBox, PonderTiming.AFTER_LONG_TEXT);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().topOf(networkInterface))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Each keeps a small reservoir. Oil piped into any cabinet of a bank, or the interface driving it, lubricates the whole bank");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().topOf(servoInput))
                .placeNearTarget()
                .text("Servos and other interfaces take a pipe of their own");
        scene.idle(PonderTiming.AFTER_TEXT);

        // The redundant run across the cabinets.
        scene.world().showSection(redundantPipes, Direction.DOWN);
        scene.idle(5);
        scene.world().modifyBlock(teeAboveInterface, state -> state.setValue(PipeBlock.NORTH, true), false);
        scene.idle(10);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(util.grid().at(3, 2, 1)))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Piping oil into every cabinet is not needed, but keeps the bank supplied if one is lost in battle");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /** A shell bursting: a flash and some smoke. */
    private static void burst(CreateSceneBuilder scene, SceneBuildingUtil util, Vec3 at) {
        scene.effects().emitParticles(at, scene.effects().particleEmitterWithinBlockSpace(
                ParticleTypes.EXPLOSION, Vec3.ZERO), 1, 1);
        scene.effects().emitParticles(at, scene.effects().particleEmitterWithinBlockSpace(
                ParticleTypes.LARGE_SMOKE, new Vec3(0, 0.05, 0)), 3, 6);
        scene.idle(4);
    }

    private static void setFault(CreateSceneBuilder scene, BlockPos pos, Fault fault, boolean present) {
        scene.world().modifyBlockEntity(pos, SmartBlockEntity.class, be -> {
            EquipmentHealth health = EquipmentHealth.of(be);
            if (health != null)
                health.setPonderFault(fault, present);
        });
    }
}
