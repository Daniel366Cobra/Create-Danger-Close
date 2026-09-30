package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.data.BlockStateGen;
import com.simibubi.create.foundation.data.BuilderTransformers;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.providers.RegistrateRecipeProvider;
import com.tterrag.registrate.util.entry.BlockEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoBlockItem;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoInputBlock;
import io.github.daniel366cobra.createdangerclose.block.cannon_servo.CannonServoOutputBlock;
import io.github.daniel366cobra.createdangerclose.block.fire_control_solver.ComputerModule;
import io.github.daniel366cobra.createdangerclose.block.fire_control_solver.ComputerModuleBlock;
import io.github.daniel366cobra.createdangerclose.block.fire_control_solver.FireControlSolverBlock;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlock;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableCTBehaviour;
import io.github.daniel366cobra.createdangerclose.block.navigation_console.NavigationConsoleBlock;
import io.github.daniel366cobra.createdangerclose.block.network_interface.NetworkInterfaceBlock;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlock;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderEyepieceBlock;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensBlock;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensInteraction;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensItem;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.functions.CopyBlockState;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.neoforged.neoforge.client.model.generators.ModelFile;

import java.util.ArrayList;

import static com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour.interactionBehaviour;
import static com.simibubi.create.foundation.data.CreateRegistrate.connectedTextures;
import static com.simibubi.create.foundation.data.ModelGen.customItemModel;
import static com.simibubi.create.foundation.data.TagGen.axeOrPickaxe;

public class CDCBlocks {

    private static final CreateRegistrate REGISTRATE = CreateDangerClose.getRegistrate();

    public static final ArrayList<BlockEntry> BLOCKS = new ArrayList<>();

    public static final BlockEntry<NetworkInterfaceBlock> NETWORK_INTERFACE = REGISTRATE.block("network_interface", NetworkInterfaceBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .transform(axeOrPickaxe())

            .onRegister(block -> {
                BlockStressValues.IMPACTS.register(block, () -> 4.0);
            })
            .item()
            .transform(customItemModel())
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern(" T ")
                    .pattern(" C ")
                    .pattern(" S ")
                    .define('T', AllItems.ELECTRON_TUBE)
                    .define('C', AllBlocks.BRASS_CASING)
                    .define('S', AllBlocks.SHAFT)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.BRASS_INGOT))
                    .save(p))
            .lang("Battle Network Interface")
            .register();

    public static final BlockEntry<FireControlSolverBlock> FIRE_CONTROL_SOLVER = REGISTRATE.block("fire_control_solver", FireControlSolverBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .transform(axeOrPickaxe())
            // No shaft and no stress impact: a network interface facing it drives it.
            .item()
            .transform(customItemModel())
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern("CEC")
                    .pattern("PPP")
                    .define('C', AllBlocks.COGWHEEL)
                    .define('E', AllItems.ELECTRON_TUBE)
                    .define('P', AllItems.PRECISION_MECHANISM)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.BRASS_INGOT))
                    .save(p))
            .lang("Ballistic Computer")
            .register();

    // Fire control computer modules: each adds one capability to the Ballistic Computer it touches.
    public static final BlockEntry<ComputerModuleBlock> DIFFERENTIATOR =
            computerModule(ComputerModule.DIFFERENTIATOR, "Differentiator", AllBlocks.LARGE_COGWHEEL);
    public static final BlockEntry<ComputerModuleBlock> INTEGRATOR =
            computerModule(ComputerModule.INTEGRATOR, "Integrator", AllBlocks.TURNTABLE);
    public static final BlockEntry<ComputerModuleBlock> CORRELATOR =
            computerModule(ComputerModule.CORRELATOR, "Correlator", Items.COMPARATOR);
    public static final BlockEntry<ComputerModuleBlock> FUZE_COMPUTER =
            computerModule(ComputerModule.FUZE_COMPUTER, "Fuze Computer", Items.CLOCK);

    public static final BlockEntry<NavigationConsoleBlock> NAVIGATION_CONSOLE = REGISTRATE
            .block("navigation_console", NavigationConsoleBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .transform(axeOrPickaxe())
            .blockstate((c, p) -> p.horizontalBlock(c.get(), p.models().orientable(c.getName(),
                    p.modLoc("block/computer/side"),
                    p.modLoc("block/computer/navigation_console_front"),
                    p.modLoc("block/computer/top"))))
            .simpleItem()
            .recipe((c, p) -> ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, c.get(), 1)
                    .requires(AllBlocks.BRASS_CASING)
                    .requires(AllItems.PRECISION_MECHANISM)
                    .requires(Items.COMPASS)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.PRECISION_MECHANISM))
                    .save(p))
            .lang("Navigation Console")
            .register();

    /**
     * A computer module: a casing-sided block whose face turns in yaw. Crafted
     * from a brass casing, a precision mechanism and one part of its own.
     */
    private static BlockEntry<ComputerModuleBlock> computerModule(ComputerModule module, String name,
                                                                  ItemLike signaturePart) {
        return REGISTRATE.block(module.id(), p -> new ComputerModuleBlock(p, module))
                .initialProperties(SharedProperties::softMetal)
                .properties(p -> p.mapColor(MapColor.PODZOL))
                .transform(axeOrPickaxe())
                .blockstate((c, p) -> p.horizontalBlock(c.get(), p.models().orientable(c.getName(),
                        p.modLoc("block/computer/side"),
                        p.modLoc("block/computer/" + c.getName() + "_front"),
                        p.modLoc("block/computer/top"))))
                .simpleItem()
                .recipe((c, p) -> ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, c.get(), 1)
                        .requires(AllBlocks.BRASS_CASING)
                        .requires(AllItems.PRECISION_MECHANISM)
                        .requires(signaturePart)
                        .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.PRECISION_MECHANISM))
                        .save(p))
                .lang(name)
                .register();
    }

    public static final BlockEntry<CannonServoInputBlock> CANNON_SERVO_INPUT =REGISTRATE.block("cannon_servo_input", CannonServoInputBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .transform(axeOrPickaxe())
            .onRegister(block -> {
                BlockStressValues.IMPACTS.register(block, () -> 4.0);
            })
            .onRegister(b -> BlockMovementChecks.registerAttachedCheck((state, world, pos, direction) -> {
                if (!(state.getBlock() instanceof CannonServoInputBlock))
                    return BlockMovementChecks.CheckResult.PASS;
                if (state.getValue(CannonServoInputBlock.FACING) != direction)
                    return BlockMovementChecks.CheckResult.PASS;
                return BlockMovementChecks.CheckResult.SUCCESS;
            }))
            .onRegister(b -> BlockMovementChecks.registerBrittleCheck(state -> {
                if (!(state.getBlock() instanceof CannonServoInputBlock))
                    return BlockMovementChecks.CheckResult.PASS;
                return BlockMovementChecks.CheckResult.SUCCESS;
            }))
            .blockstate((c, p) -> p.directionalBlock(c.get(),
                    $ -> p.models().getExistingFile(p.modLoc("block/cannon_servo/block_input"))))
            .item(CannonServoBlockItem::new)
            .transform(customItemModel("cannon_servo/item"))
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern("CCC")
                    .pattern("PBP")
                    .pattern(" S ")
                    .define('C', AllBlocks.COGWHEEL)
                    .define('B', AllBlocks.BRASS_CASING)
                    .define('P', AllItems.PRECISION_MECHANISM)
                    .define('S', AllBlocks.SHAFT)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.BRASS_INGOT))
                    .save(p))
            .lang("Cannon Mount Servo")
            .register();

    public static final BlockEntry<CannonServoOutputBlock> CANNON_SERVO_OUTPUT = REGISTRATE.block("cannon_servo_output", CannonServoOutputBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.PODZOL))
            .blockstate(BlockStateGen.directionalBlockProvider(true))
            .transform(axeOrPickaxe())
            .onRegister(block -> {
                BlockStressValues.IMPACTS.register(block, () -> 4.0);
            })
            .onRegister(b -> BlockMovementChecks.registerAttachedCheck((state, world, pos, direction) -> {
                if (!(state.getBlock() instanceof CannonServoOutputBlock))
                    return BlockMovementChecks.CheckResult.PASS;
                if (state.getValue(CannonServoOutputBlock.FACING).getOpposite() != direction)
                    return BlockMovementChecks.CheckResult.PASS;
                return BlockMovementChecks.CheckResult.SUCCESS;
            }))
            .onRegister(b -> BlockMovementChecks.registerBrittleCheck(state -> {
                if (!(state.getBlock() instanceof CannonServoOutputBlock))
                    return BlockMovementChecks.CheckResult.PASS;
                return BlockMovementChecks.CheckResult.SUCCESS;
            }))
            .blockstate((c, p) -> p.directionalBlock(c.get(),
                    $ -> p.models().getExistingFile(p.modLoc("block/cannon_servo/block_output"))))
            .register();

    public static final BlockEntry<RangefinderBearingBlock> RANGEFINDER_BEARING = REGISTRATE.block("rangefinder_bearing", RangefinderBearingBlock::new)
            .properties(p -> p.mapColor(MapColor.TERRACOTTA_BROWN))
            .transform(axeOrPickaxe())
            // Placeholder look: Create's brass (clockwork) bearing textures.
            .transform(BuilderTransformers.bearing("clockwork", "brass_gearbox"))
            .onRegister(block -> {
                BlockStressValues.IMPACTS.register(block, () -> 4.0);
            })
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern(" G ")
                    .pattern(" B ")
                    .pattern(" P ")
                    .define('G', Items.SPYGLASS)
                    .define('B', AllBlocks.MECHANICAL_BEARING)
                    .define('P', AllItems.PRECISION_MECHANISM)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.PRECISION_MECHANISM))
                    .save(p))
            .lang("Rangefinder Bearing")
            .register();

    public static final BlockEntry<RangefinderLensBlock> RANGEFINDER_LENS = REGISTRATE.block("rangefinder_lens", RangefinderLensBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.TERRACOTTA_BROWN))
            .transform(axeOrPickaxe())
            // A cracked lens is the same model with the lens glass texture ("5") swapped.
            .blockstate((c, p) -> p.horizontalBlock(c.get(), state -> state.getValue(RangefinderLensBlock.CRACKED)
                    ? p.models().withExistingParent("block/rangefinder_lens/block_cracked",
                                    p.modLoc("block/rangefinder_lens/block"))
                            .texture("5", p.modLoc("block/rangefinder_lens/lens_cracked"))
                    : p.models().getExistingFile(p.modLoc("block/rangefinder_lens/block"))))
            .onRegister(interactionBehaviour(new RangefinderLensInteraction()))
            // A cracked lens drops cracked: the state rides on the stack and is put back when placed.
            .loot((lt, block) -> lt.add(block, lt.createSingleItemTable(block)
                    .apply(CopyBlockState.copyState(block).copy(RangefinderLensBlock.CRACKED)
                            .when(LootItemBlockStatePropertyCondition.hasBlockStateProperties(block)
                                    .setProperties(StatePropertiesPredicate.Builder.properties()
                                            .hasProperty(RangefinderLensBlock.CRACKED, true))))))
            .item(RangefinderLensItem::new)
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/rangefinder_lens/block"))
                    .override()
                    .predicate(p.modLoc("cracked"), 1)
                    .model(new ModelFile.UncheckedModelFile(p.modLoc("block/rangefinder_lens/block_cracked")))
                    .end())
            .build()
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern(" S ")
                    .pattern("GCG")
                    .define('S', AllItems.BRASS_SHEET)
                    .define('G', Items.GLASS_PANE)
                    .define('C', AllBlocks.BRASS_CASING)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.BRASS_INGOT))
                    .save(p))
            .lang("Rangefinder Lens")
            .register();

    public static final BlockEntry<RangefinderEyepieceBlock> RANGEFINDER_EYEPIECE = REGISTRATE.block("rangefinder_eyepiece", RangefinderEyepieceBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p.mapColor(MapColor.TERRACOTTA_BROWN))
            .transform(axeOrPickaxe())
            .blockstate((c, p) -> p.horizontalBlock(c.get(),
                    p.models().getExistingFile(p.modLoc("block/rangefinder_eyepiece/block"))))
            .item()
            .model((c, p) -> p.withExistingParent(c.getName(), p.modLoc("block/rangefinder_eyepiece/block")))
            .build()
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern(" G ")
                    .pattern("SCS")
                    .define('G', Items.SPYGLASS)
                    .define('S', AllItems.BRASS_SHEET)
                    .define('C', AllBlocks.BRASS_CASING)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(AllItems.BRASS_INGOT))
                    .save(p))
            .lang("Rangefinder Eyepiece")
            .register();

    public static final BlockEntry<MapTableBlock> MAP_TABLE = REGISTRATE.block("map_table", MapTableBlock::new)
            .initialProperties(SharedProperties::wooden)
            .properties(p -> p.mapColor(MapColor.WOOD))
            .transform(axeOrPickaxe())
            .blockstate((c, p) -> p.simpleBlock(c.get(), p.models().cubeBottomTop(c.getName(),
                    p.modLoc("block/map_table/side"),
                    p.modLoc("block/map_table/bottom"),
                    p.modLoc("block/map_table/top"))))
            .onRegister(connectedTextures(MapTableCTBehaviour::new))
            .item()
            .build()
            .recipe((c, p) -> ShapedRecipeBuilder.shaped(RecipeCategory.MISC, c.get(), 1)
                    .pattern(" T ")
                    .pattern("SCS")
                    .define('T', Items.CARTOGRAPHY_TABLE)
                    .define('S', AllItems.BRASS_SHEET)
                    .define('C', AllBlocks.BRASS_CASING)
                    .unlockedBy("has_ingredient", RegistrateRecipeProvider.has(Items.CARTOGRAPHY_TABLE))
                    .save(p))
            .lang("Map Table")
            .register();

    public static void register() {
        BLOCKS.add(NETWORK_INTERFACE);
        BLOCKS.add(FIRE_CONTROL_SOLVER);
        BLOCKS.add(DIFFERENTIATOR);
        BLOCKS.add(INTEGRATOR);
        BLOCKS.add(CORRELATOR);
        BLOCKS.add(FUZE_COMPUTER);
        BLOCKS.add(NAVIGATION_CONSOLE);
        BLOCKS.add(CANNON_SERVO_INPUT);
        BLOCKS.add(CANNON_SERVO_OUTPUT);
        BLOCKS.add(RANGEFINDER_BEARING);
        BLOCKS.add(RANGEFINDER_LENS);
        BLOCKS.add(RANGEFINDER_EYEPIECE);
        BLOCKS.add(MAP_TABLE);
    }
}