package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity.Phase;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.block.munitions_chute.MunitionsChuteBlockEntity;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class MunitionsChuteScenes {

    private MunitionsChuteScenes() {}

    private static final int ARM_REACH_TICKS = 24, ARM_PAUSE_TICKS = 20;

    /**
     * Structure {@code munitions_chute.nbt}: 7×6 floor, a servo pair at x = 3 whose cannon side runs through
     * a Rotation Speed Controller (3, 1, 2) to the chute (0, 2, 2) under a barrel (0, 3, 2), and a Mechanical Arm
     * (2, 1, 1) taking from the chute into the mount (5, 1, 0), which the second servo pair at x = 5 drives.
     */
    public static void munitionsChute(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("munitions_chute", "Staging rounds with the Munitions Chute");
        scene.configureBasePlate(0, 0, 6);

        BlockPos chute = util.grid().at(0, 2, 2);
        BlockPos storage = util.grid().at(0, 3, 2);
        BlockPos arm = util.grid().at(2, 1, 1);
        BlockPos mount = util.grid().at(5, 1, 0);
        BlockPos lever = util.grid().at(4, 1, 0);

        Selection floor = util.select().fromTo(0, 0, 0, 6, 0, 5);
        Selection drive = util.select().fromTo(7, 0, 5, 7, 1, 5);
        Selection network = util.select().fromTo(3, 1, 5, 6, 1, 5);
        Selection servos = util.select().fromTo(3, 1, 3, 3, 1, 4);
        Selection yawChain = util.select().fromTo(3, 1, 0, 3, 1, 2);
        Selection gun = util.select().fromTo(5, 1, 1, 5, 1, 4)
                .add(util.select().position(mount))
                .add(util.select().position(lever));
        Selection cannon = util.select().fromTo(5, 3, 0, 7, 3, 0);
        Selection feed = util.select().fromTo(1, 2, 2, 3, 2, 2)
                .add(util.select().position(2, 1, 2))
                .add(util.select().position(arm));
        Selection chuteStack = util.select().position(chute)
                .add(util.select().position(0, 1, 2));
        Selection barrel = util.select().position(storage);

        ItemStack shell = stack("createbigcannons", "he_shell");
        ItemStack cartridge = stack("createbigcannons", "big_cartridge");

        scene.world().modifyBlock(lever, state -> state.setValue(BlockStateProperties.POWERED, true), false);
        scene.world().modifyBlock(mount, CannonAim::assembled, false);
        setState(scene, util, chute, MunitionsChuteBlockEntity.State.READY);

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(network, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(servos, Direction.DOWN);
        scene.world().showSection(gun, Direction.DOWN);
        scene.world().showSection(cannon, Direction.DOWN);
        scene.idle(10);
        scene.world().showSection(yawChain, Direction.DOWN);
        scene.idle(20);

        // The chute and what feeds it.
        scene.world().showSection(feed, Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(chuteStack, Direction.EAST);
        scene.idle(5);
        scene.world().showSection(barrel, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(chute, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("The Munitions Chute stages a shell and a filled cartridge for a cannon loader");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(storage))
                .placeNearTarget()
                .text("It draws from storage on its top face and open sides, turning whenever it is driven");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().centerOf(util.grid().at(3, 2, 2)))
                .placeNearTarget()
                .text("A Rotation Speed Controller sets its pace and the arm's. Tapped off a servo's cannon side, the chute is linked to that cannon");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // The arm takes the shell, then the cartridge, into the mount.
        scene.world().instructArm(arm, Phase.MOVE_TO_INPUT, ItemStack.EMPTY, 0);
        scene.idle(ARM_REACH_TICKS);
        scene.world().instructArm(arm, Phase.SEARCH_OUTPUTS, shell, -1);
        scene.idle(ARM_PAUSE_TICKS);
        scene.world().instructArm(arm, Phase.MOVE_TO_OUTPUT, shell, 0);
        scene.idle(ARM_REACH_TICKS);
        scene.world().instructArm(arm, Phase.SEARCH_INPUTS, ItemStack.EMPTY, -1);
        scene.idle(ARM_PAUSE_TICKS);
        scene.world().instructArm(arm, Phase.MOVE_TO_INPUT, ItemStack.EMPTY, 0);
        scene.idle(ARM_REACH_TICKS);
        scene.world().instructArm(arm, Phase.SEARCH_OUTPUTS, cartridge, -1);
        scene.idle(ARM_PAUSE_TICKS);
        scene.world().instructArm(arm, Phase.MOVE_TO_OUTPUT, cartridge, 0);
        scene.idle(ARM_REACH_TICKS);
        scene.world().instructArm(arm, Phase.SEARCH_INPUTS, ItemStack.EMPTY, -1);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(chute))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Mechanical Arm takes the staged round, shell first, and loads it into the cannon's mount. Above a belt or depot, the chute drops it instead");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Linked: holds to what the computer orders.
        setState(scene, util, chute, MunitionsChuteBlockEntity.State.NO_MISSION);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(util.vector().blockSurface(chute, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A linked chute stages only what the Ballistic Computer orders for its cannon: the shell type and number of rounds of a fire mission");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(util.vector().blockSurface(chute, Direction.NORTH))
                .placeNearTarget()
                .text("Until a mission is ordered, or once its rounds are staged, it holds. Look at it with Goggles to see why");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Standalone.
        setState(scene, util, chute, MunitionsChuteBlockEntity.State.READY);
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().blockSurface(chute, Direction.NORTH))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Chutes not linked to a mount through a battle or cannon network stage and provide whatever they find in storage");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    private static ItemStack stack(String namespace, String path) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(namespace, path)));
    }

    /** The chute simulates nothing in a scene: its state is what its goggles show. */
    private static void setState(CreateSceneBuilder scene, SceneBuildingUtil util, BlockPos chute,
                                 MunitionsChuteBlockEntity.State state) {
        scene.world().modifyBlockEntityNBT(util.select().position(chute), MunitionsChuteBlockEntity.class,
                nbt -> nbt.putInt("State", state.ordinal()), true);
    }
}
