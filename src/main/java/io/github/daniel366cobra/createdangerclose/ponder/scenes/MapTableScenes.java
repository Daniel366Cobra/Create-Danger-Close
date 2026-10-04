package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.api.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMarker;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableRenderer;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Ponder scenes for the Map Table. */
public final class MapTableScenes {

    private MapTableScenes() {}

    private static final ResourceLocation CHART =
            ResourceLocation.fromNamespaceAndPath(CreateDangerClose.MOD_ID, "ponder/map_texture.png");
    private static final int WIDTH = 3;
    // The made-up chart: one block per map pixel, so the 3×3 table shows 384 blocks across.
    private static final double SHEET_BLOCKS = 128.0 * WIDTH;

    // Where things lie on the chart, as fractions of it (north-west = 0, 0):
    // the ship out at sea, the target inland, a spotter on the hills north-west of it.
    private static final double SHIP_U = 0.70, SHIP_V = 0.55;
    private static final double TARGET_U = 0.33, TARGET_V = 0.45;
    private static final double FIX_U = 0.337, FIX_V = 0.458, FIX_SIGMA = 12;
    private static final double SPOTTER_U = 0.22, SPOTTER_V = 0.30;
    // The gun's reach and its dead zone, blocks.
    private static final double GUN_RANGE = 230, GUN_MIN_RANGE = 30;
    // Markers and plot lines this many times their in-game size, to read in the small Ponder view.
    private static final float BOLDNESS = 3.0f;

    /**
     * Structure {@code map_table.nbt}: a 5×5 floor (the bottom layer), a 3×3
     * Map Table at (1..3, 1, 1..3) with its controller at (1, 1, 1), and east
     * of it a Network Interface at (4, 1, 2) facing it, driven by a cogwheel
     * column bringing power from off the scene.
     */
    public static void mapTable(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("map_table", "Plotting fire missions on a Map Table");
        scene.configureBasePlate(0, 0, 5);

        BlockPos controller = util.grid().at(1, 1, 1);
        BlockPos networkInterface = util.grid().at(4, 1, 2);

        Selection floor = util.select().fromTo(0, 0, 0, 4, 0, 4);
        Selection table = util.select().fromTo(1, 1, 1, 3, 1, 3);
        Selection drive = util.select().fromTo(5, 0, 2, 5, 1, 2);

        // The sheet is placed so the middle of the table, where the ship
        // marker goes, lies out at sea on the chart.
        Vec3 middle = new Vec3(controller.getX() + WIDTH / 2.0, 0, controller.getZ() + WIDTH / 2.0);
        double westX = middle.x - SHIP_U * SHEET_BLOCKS;
        double northZ = middle.z - SHIP_V * SHEET_BLOCKS;
        Vec3 target = new Vec3(westX + TARGET_U * SHEET_BLOCKS, 64, northZ + TARGET_V * SHEET_BLOCKS);
        double aimX = target.x - middle.x, aimZ = target.z - middle.z, aimLength = Math.hypot(aimX, aimZ);
        BattlePlot plot = new BattlePlot(
                List.of(new BattlePlot.RangefinderFix(westX + FIX_U * SHEET_BLOCKS, 64,
                        northZ + FIX_V * SHEET_BLOCKS, FIX_SIGMA, middle.x, middle.z)),
                List.of(new BattlePlot.GunRange(middle.x, middle.z, GUN_RANGE,
                        aimX / aimLength, aimZ / aimLength, GUN_MIN_RANGE)));
        // Looking towards the target; marker yaw 0 points south, 180 north.
        float spotterYaw = (float) Math.toDegrees(Math.atan2(-(TARGET_U - SPOTTER_U), TARGET_V - SPOTTER_V));
        List<MapTableMarker.Marker> spotters = List.of(new MapTableMarker.Marker(
                MapDecorationTypes.FRAME, (float) SPOTTER_U, (float) SPOTTER_V, spotterYaw));

        scene.world().modifyBlockEntity(controller, MapTableBlockEntity.class, be -> be.setPonderChart(
                new MapTableBlockEntity.PonderChart(
                        new MapTableBlockEntity.Sheet(westX, northZ, 1, WIDTH, be.getLevel().dimension()),
                        CHART, spotters, BOLDNESS)));

        scene.world().showSection(floor, Direction.UP);
        scene.idle(10);
        scene.world().showSection(table, Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(util.vector().blockSurface(util.grid().at(1, 1, 2), Direction.WEST))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Map Tables placed side by side join into one table, up to 3×3");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Lay the maps, one per block, row by row.
        scene.overlay().showControls(onChart(controller, 1 / 6.0, 1 / 6.0), Pointing.DOWN, 40)
                .rightClick()
                .withItem(new ItemStack(Items.FILLED_MAP));
        scene.idle(10);
        for (int dz = 0; dz < WIDTH; dz++) {
            for (int dx = 0; dx < WIDTH; dx++) {
                scene.world().modifyBlockEntity(controller.offset(dx, 0, dz), MapTableBlockEntity.class,
                        be -> be.getInventory().setStackInSlot(0, new ItemStack(Items.FILLED_MAP)));
                scene.idle(4);
            }
        }
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(onChart(controller, 0.5, 0.5))
                .placeNearTarget()
                .text("Right-click it with Filled Maps to lay them out. They must fit together like the land, north up");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(onChart(controller, 0.5, 0.5))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Right-click with an empty hand to open the table GUI to see and manage its maps");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.world().modifyBlockEntity(controller, MapTableBlockEntity.class, be -> be.setPonderTarget(target));
        scene.overlay().showText(PonderTiming.TEXT)
                .colored(PonderPalette.RED)
                .pointAt(onChart(controller, TARGET_U, TARGET_V))
                .placeNearTarget()
                .text("There, right-click the maps to mark a target");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(onChart(controller, TARGET_U, TARGET_V))
                .placeNearTarget()
                .text("On its own, the table only keeps the target as a note");
        scene.idle(PonderTiming.AFTER_TEXT);

        // Connect it: the plot comes up.
        scene.world().showSection(drive, Direction.WEST);
        scene.world().showSection(util.select().position(networkInterface), Direction.WEST);
        scene.idle(15);
        scene.world().modifyBlockEntity(controller, MapTableBlockEntity.class, be -> be.setPonderPlot(plot));
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(util.vector().topOf(networkInterface))
                .placeNearTarget()
                .attachKeyFrame()
                .text("A Network Interface facing it sends the target to the Ballistic Computer...");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.TEXT)
                .pointAt(onChart(controller, SHIP_U, SHIP_V))
                .placeNearTarget()
                .text("...and the table shows where the ship is");
        scene.idle(PonderTiming.AFTER_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.RED)
                .pointAt(onChart(controller, (SHIP_U + TARGET_U) / 2, (SHIP_V + TARGET_V) / 2))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Red: each gun's reach and where it points. Inside the inner ring is too close to hit");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.BLUE)
                .pointAt(onChart(controller, FIX_U, FIX_V))
                .placeNearTarget()
                .text("White: where a rangefinder has ranged, in a blue circle of how far off it may be");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .colored(PonderPalette.GREEN)
                .pointAt(onChart(controller, SPOTTER_U, SPOTTER_V))
                .placeNearTarget()
                .attachKeyFrame()
                .text("Green: spotters, players out in the field carrying a copy of one of the maps");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        // Charting.
        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(onChart(controller, SHIP_U, SHIP_V))
                .placeNearTarget()
                .attachKeyFrame()
                .text("While connected, the table keeps its maps up to date around itself, as a player holding them would...");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);

        scene.overlay().showText(PonderTiming.LONG_TEXT)
                .pointAt(onChart(controller, FIX_U, FIX_V))
                .placeNearTarget()
                .text("...and around each target being ranged. Ground out of loaded range stays as last seen");
        scene.idle(PonderTiming.AFTER_LONG_TEXT);
    }

    /** A point on the table's top, at a fraction of its chart (north-west = 0, 0). */
    private static Vec3 onChart(BlockPos controller, double u, double v) {
        double size = WIDTH - 2 * MapTableRenderer.BORDER;
        return new Vec3(controller.getX() + MapTableRenderer.BORDER + u * size, controller.getY() + 1,
                controller.getZ() + MapTableRenderer.BORDER + v * size);
    }
}
