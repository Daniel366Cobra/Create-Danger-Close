package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import io.github.daniel366cobra.createdangerclose.foundation.CDCLang;
import net.minecraft.ChatFormatting;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import io.github.daniel366cobra.createdangerclose.mixin.MapItemSavedDataAccessor;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One block of a map table: up to 3×3, joined via {@link ConnectivityHandler}, controller north-west.
 * Each block holds its own map, so joining never moves items. Maps fit like the land: same zoom and
 * dimension, offset by one map width per block, north up.
 */
public class MapTableBlockEntity extends SmartBlockEntity
        implements IMultiBlockEntityContainer, MenuProvider, IHaveGoggleInformation {

    public static final int MAX_WIDTH = 3;
    private static final int MAP_SYNC_INTERVAL_TICKS = 10;
    private static final double MAP_SYNC_RANGE = 64;

    // Connected while an interface keeps polling (at most every 20 ticks).
    private static final int LINK_TIMEOUT_TICKS = 40;

    protected final ItemStackHandler inventory;
    @Nullable protected BlockPos controller;
    @Nullable protected BlockPos lastKnownPos;
    protected boolean updateConnectivity;
    protected int width = 1;

    // Controller only.
    @Nullable protected Vec3 target;
    protected long targetTick;
    protected boolean linked;
    protected long lastLinkTick;
    // Controller only: as last carried over by an interface.
    protected BattlePlot plot = BattlePlot.EMPTY;
    // Client: renderers glide from the previous plot, as the interface polls only every so often.
    private BattlePlot plotShownBefore = BattlePlot.EMPTY;
    private long plotArrivalTick;
    // About one poll at a typical network speed.
    private static final int PLOT_GLIDE_TICKS = 10;
    // Controller, server only: per player, hashes of map pixels sent.
    private Map<UUID, Map<MapId, Integer>> sentPixels = new HashMap<>();
    // Client only: sent by the server.
    @Nullable protected MapPlacement clientMapPlacement;
    // Ponder only: stands in for the maps and the link.
    @Nullable protected PonderChart ponderChart;

    /** North-west corner, blocks per map pixel, and dimension of the stitched sheet. */
    public record Sheet(double westX, double northZ, int blocksPerPixel, int width, ResourceKey<Level> dimension) {
        public double sizeInPixels() {
            return 128.0 * width;
        }

    /** Sheet fraction (north-west = 0, 0) to world. */
        public double worldX(double u) {
            return westX + u * sizeInPixels() * blocksPerPixel;
        }

        public double worldZ(double v) {
            return northZ + v * sizeInPixels() * blocksPerPixel;
        }

    /** World to sheet pixels. */
        public double pixelX(double x) {
            return (x - westX) / blocksPerPixel;
        }

        public double pixelY(double z) {
            return (z - northZ) / blocksPerPixel;
        }
    }

    /** A Ponder chart: one image across the table, the spotters to mark, and a {@code boldness} scale for markers and lines. */
    public record PonderChart(Sheet sheet, ResourceLocation texture, List<MapTableMarker.Marker> spotters,
                              float boldness) {}

    public MapTableBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        inventory = new ItemStackHandler(1) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return canHold(stack);
            }

            @Override
            protected void onContentsChanged(int slot) {
                if (level != null && !level.isClientSide) {
                    setChanged();
                    sendData();
                }
            }
        };
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    // --- Maps ---

    public ItemStack getMap() {
        return inventory.getStackInSlot(0);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    /** Filled maps only; the server also checks the fit (the client may lack map data). */
    public boolean canHold(ItemStack stack) {
        if (!stack.is(Items.FILLED_MAP))
            return false;
        if (level == null || level.isClientSide)
            return true;
        return fitsTable(stack);
    }

    private boolean fitsTable(ItemStack stack) {
        MapItemSavedData data = MapItem.getSavedData(stack, level);
        if (data == null)
            return false;
        MapTableBlockEntity controllerBE = getControllerBE();
        if (controllerBE == null)
            return true;

        int mapWidth = 128 << data.scale;
        for (int dx = 0; dx < controllerBE.width; dx++) {
            for (int dz = 0; dz < controllerBE.width; dz++) {
                MapTableBlockEntity other = controllerBE.partAt(dx, dz);
                if (other == null || other == this || other.getMap().isEmpty())
                    continue;
                MapItemSavedData otherData = MapItem.getSavedData(other.getMap(), level);
                if (otherData == null)
                    continue;
                if (otherData.scale != data.scale || !otherData.dimension.equals(data.dimension))
                    return false;
                int blockDx = worldPosition.getX() - other.worldPosition.getX();
                int blockDz = worldPosition.getZ() - other.worldPosition.getZ();
                if (data.centerX - otherData.centerX != blockDx * mapWidth
                        || data.centerZ - otherData.centerZ != blockDz * mapWidth)
                    return false;
            }
        }
        return true;
    }

    public record MapPlacement(int centerX, int centerZ, int scale, ResourceKey<Level> dimension) {}

    /** Vanilla never tells clients where a map is centered, so the server sends it. */
    @Nullable
    public MapPlacement getMapPlacement() {
        if (level == null || level.isClientSide)
            return clientMapPlacement;
        ItemStack map = getMap();
        MapItemSavedData data = map.isEmpty() ? null : MapItem.getSavedData(map, level);
        return data == null ? null : new MapPlacement(data.centerX, data.centerZ, data.scale, data.dimension);
    }

    /** Fixed by any one map, since each lies one map width from its neighbors'. Null without maps. */
    @Nullable
    public Sheet getSheet() {
        if (ponderChart != null)
            return ponderChart.sheet();
        if (level == null)
            return null;
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                MapTableBlockEntity part = partAt(dx, dz);
                MapPlacement map = part != null ? part.getMapPlacement() : null;
                if (map == null)
                    continue;
                int blocksPerPixel = 1 << map.scale();
                return new Sheet(
                        map.centerX() - 64.0 * blocksPerPixel - dx * 128.0 * blocksPerPixel,
                        map.centerZ() - 64.0 * blocksPerPixel - dz * 128.0 * blocksPerPixel,
                        blocksPerPixel, width, map.dimension());
            }
        }
        return null;
    }

    // --- Target (controller only) ---

    @Nullable
    public Vec3 getTarget() {
        return target;
    }

    /** So the newest of several tables wins. */
    public long getTargetTick() {
        return targetTick;
    }

    /** At sheet fractions; the height is the ground (or water) if loaded, else sea level. */
    public void designateTarget(double u, double v) {
        Sheet sheet = getSheet();
        if (level == null || sheet == null || !sheet.dimension().equals(level.dimension()))
            return;
        int x = Mth.floor(sheet.worldX(Mth.clamp(u, 0, 1)));
        int z = Mth.floor(sheet.worldZ(Mth.clamp(v, 0, 1)));
        int y = level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                : level.getSeaLevel();
        target = new Vec3(x + 0.5, y, z + 0.5);
        targetTick = level.getGameTime();
        setChanged();
        sendData();
    }

    public void clearTarget() {
        if (target == null)
            return;
        target = null;
        setChanged();
        sendData();
    }

    // --- Battle network link (controller only) ---

    /** Called each time an interface reads this table. */
    public void markLinked(BattlePlot plot) {
        if (level == null)
            return;
        lastLinkTick = level.getGameTime();
        if (level instanceof ServerLevel serverLevel && CDCConfig.server().mapTable.allowChartingAroundFixes.get())
            chartRangedTargets(serverLevel, plot);
        if (!linked || !plot.equals(this.plot)) {
            linked = true;
            this.plot = plot;
            sendData();
        }
    }

    // --- Charting (controller, server) ---
    // On a battle network, maps stay charted around the table and each ranged target. Unloaded ground stays stale.

    private int chartStep;

    /** A sixteenth of the area each tick, as vanilla does for a held map. */
    private void chartSurroundings(ServerLevel serverLevel) {
        Vec3 here = worldCenter();
        int step = chartStep++ % MapCharting.STAGGER_COLUMNS;
        forEachChartedMap(serverLevel, data ->
                MapCharting.chart(serverLevel, data, here.x, here.z, MapCharting.holderRadius(serverLevel, data), step));
    }

    /** Charts around each ranged target once per poll, out to the configured radius. */
    private void chartRangedTargets(ServerLevel serverLevel, BattlePlot plot) {
        int radius = CDCConfig.server().mapTable.rangefinderChartRadius.get();
        for (BattlePlot.RangefinderFix fix : plot.fixes())
            forEachChartedMap(serverLevel, data -> MapCharting.chart(serverLevel, data, fix.x(), fix.z(),
                    Math.max(1, Mth.ceil((double) radius / (1 << data.scale))), -1));
    }

    public Set<MapId> getMapIds() {
        Set<MapId> ids = new HashSet<>();
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                MapTableBlockEntity part = partAt(dx, dz);
                MapId id = part != null ? part.getMap().get(DataComponents.MAP_ID) : null;
                if (id != null)
                    ids.add(id);
            }
        }
        return ids;
    }

    private void forEachChartedMap(ServerLevel serverLevel, Consumer<MapItemSavedData> action) {
        for (int dx = 0; dx < width; dx++) {
            for (int dz = 0; dz < width; dz++) {
                MapTableBlockEntity part = partAt(dx, dz);
                ItemStack map = part != null ? part.getMap() : ItemStack.EMPTY;
                MapItemSavedData data = map.isEmpty() ? null : MapItem.getSavedData(map, serverLevel);
                if (data != null)
                    action.accept(data);
            }
        }
    }

    /** Out of its ship, if on one. */
    private Vec3 worldCenter() {
        Vec3 center = new Vec3(worldPosition.getX() + width / 2.0, worldPosition.getY() + 1, worldPosition.getZ() + width / 2.0);
        SubLevel ship = level != null ? Sable.HELPER.getContaining(level, center) : null;
        return ship != null ? ship.logicalPose().transformPosition(center) : center;
    }

    /** Empty unless linked to a Ballistic Computer. */
    public BattlePlot getPlot() {
        return plot;
    }

    /** Client: glides from the previous plot so lines and fixes move instead of jumping. */
    public BattlePlot getShownPlot(float partialTicks) {
        if (level == null)
            return plot;
        double t = (level.getGameTime() + partialTicks - plotArrivalTick) / PLOT_GLIDE_TICKS;
        return t >= 1 ? plot : plotShownBefore.blendToward(plot, Math.max(0, t));
    }

    public boolean isLinked() {
        return linked;
    }

    // --- Ponder (controller only) ---

    @Nullable
    public PonderChart getPonderChart() {
        return ponderChart;
    }

    public void setPonderChart(@Nullable PonderChart chart) {
        ponderChart = chart;
    }

    public void setPonderTarget(@Nullable Vec3 target) {
        this.target = target;
    }

    /** It stays connected. */
    public void setPonderPlot(BattlePlot plot) {
        linked = true;
        this.plot = plot;
    }

    @Nullable
    public MapTableBlockEntity partAt(int dx, int dz) {
        if (level == null)
            return null;
        MapTableBlockEntity part = ConnectivityHandler.partAt(getType(), level, worldPosition.offset(dx, 0, dz));
        return part != null && worldPosition.equals(part.getController()) ? part : null;
    }

    /**
     * Vanilla only syncs held or framed maps, so the table pushes markers every time, and pixels when
     * changed. It tracks recipients itself: vanilla would drop holders without the map whenever a spotter
     * ticks, then resend whole maps.
     */
    private void syncMapsToPlayers() {
        if (!(level instanceof ServerLevel serverLevel))
            return;
        Vec3 center = Sable.HELPER.projectOutOfSubLevel(level, Vec3.atCenterOf(worldPosition));
        Map<UUID, Map<MapId, Integer>> stillSent = new HashMap<>();
        for (ServerPlayer player : serverLevel.players()) {
            boolean viewing = player.containerMenu instanceof MapTableMenu menu && menu.getController() == this;
            if (!viewing && player.position().distanceTo(center) > MAP_SYNC_RANGE)
                continue;
            Map<MapId, Integer> previous = sentPixels.getOrDefault(player.getUUID(), Map.of());
            Map<MapId, Integer> sent = new HashMap<>();
            for (int dx = 0; dx < width; dx++) {
                for (int dz = 0; dz < width; dz++) {
                    MapTableBlockEntity part = partAt(dx, dz);
                    if (part != null)
                        sendMap(part.getMap(), player, previous, sent);
                }
            }
            stillSent.put(player.getUUID(), sent);
        }
        sentPixels = stillSent; // players out of range start afresh when they come back
    }

    private void sendMap(ItemStack stack, ServerPlayer player, Map<MapId, Integer> previous, Map<MapId, Integer> sent) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        if (mapId == null)
            return;
        MapItemSavedData data = MapItem.getSavedData(mapId, level);
        if (data == null)
            return;
        int pixelsHash = Arrays.hashCode(data.colors);
        Integer previousHash = previous.get(mapId);
        MapItemSavedData.MapPatch pixels = previousHash != null && previousHash == pixelsHash
                ? null : new MapItemSavedData.MapPatch(0, 0, 128, 128, data.colors.clone());
        List<MapDecoration> markers = new ArrayList<>();
        ((MapItemSavedDataAccessor) data).createdangerclose$getDecorations().forEach((key, marker) -> {
            if (!isPlayerMarker(marker) || carriesMap(key, mapId, data))
                markers.add(marker);
        });
        player.connection.send(new ClientboundMapItemDataPacket(mapId, data.scale, data.locked, markers, pixels));
        sent.put(mapId, pixelsHash);
    }

    private static boolean isPlayerMarker(MapDecoration marker) {
        return marker.type().is(MapDecorationTypes.PLAYER) || marker.type().is(MapDecorationTypes.PLAYER_OFF_MAP)
                || marker.type().is(MapDecorationTypes.PLAYER_OFF_LIMITS);
    }

    /**
     * Vanilla only clears a player's arrow while someone carries the map, so the last to put it
     * down stays frozen where they stood.
     */
    private boolean carriesMap(String playerName, MapId mapId, MapItemSavedData data) {
        ServerPlayer carrier = level.getServer().getPlayerList().getPlayerByName(playerName);
        return carrier != null && carrier.level().dimension() == data.dimension
                && carrier.getInventory().contains(stack -> mapId.equals(stack.get(DataComponents.MAP_ID)));
    }

    // --- Ticking ---

    @Override
    public void tick() {
        super.tick();
        // A Ponder chart has no real maps, players or network to keep up with.
        if (ponderChart != null)
            return;

        if (lastKnownPos == null)
            lastKnownPos = getBlockPos();
        else if (!lastKnownPos.equals(worldPosition)) {
            removeController(true);
            lastKnownPos = worldPosition;
            return;
        }

        if (updateConnectivity)
            updateConnectivity();

        if (level == null || level.isClientSide || !isController())
            return;
        if (level.getGameTime() % MAP_SYNC_INTERVAL_TICKS == 0)
            syncMapsToPlayers();
        if (linked && level instanceof ServerLevel serverLevel)
            chartSurroundings(serverLevel);
        if (linked && level.getGameTime() - lastLinkTick > LINK_TIMEOUT_TICKS) {
            linked = false;
            plot = BattlePlot.EMPTY;
            sendData();
        }
    }

    protected void updateConnectivity() {
        updateConnectivity = false;
        if (level == null || level.isClientSide || !isController())
            return;
        ConnectivityHandler.formMulti(this);
    }

    // --- IMultiBlockEntityContainer ---

    @Override
    public BlockPos getController() {
        return isController() ? worldPosition : controller;
    }

    @Override
    @Nullable
    public MapTableBlockEntity getControllerBE() {
        if (isController())
            return this;
        return level != null && level.getBlockEntity(controller) instanceof MapTableBlockEntity be ? be : null;
    }

    @Override
    public boolean isController() {
        return controller == null || worldPosition.equals(controller);
    }

    @Override
    public void setController(BlockPos controller) {
        if (level != null && level.isClientSide && !isVirtual())
            return;
        if (controller.equals(this.controller))
            return;
        this.controller = controller;
        setChanged();
        sendData();
    }

    @Override
    public void removeController(boolean keepContents) {
        if (level == null || level.isClientSide)
            return;
        updateConnectivity = true;
        controller = null;
        width = 1;
        setChanged();
        sendData();
    }

    @Override
    @Nullable
    public BlockPos getLastKnownPos() {
        return lastKnownPos;
    }

    @Override
    public void preventConnectivityUpdate() {
        updateConnectivity = false;
    }

    @Override
    public void notifyMultiUpdated() {
        setChanged();
        sendData();
    }

    @Override
    public Direction.Axis getMainConnectionAxis() {
        return Direction.Axis.Y;
    }

    @Override
    public int getMaxLength(Direction.Axis longAxis, int width) {
        return 1;
    }

    @Override
    public int getMaxWidth() {
        return MAX_WIDTH;
    }

    @Override
    public int getHeight() {
        return 1;
    }

    /**
     * Create sets 0 when a queued table finds nothing to join after all, having set the width it
     * tried (up to 3): a lone block would then claim 3×3 and block every join around it.
     */
    @Override
    public void setHeight(int height) {
        if (height < 1)
            width = 1;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public void setWidth(int width) {
        this.width = width;
    }

    // --- Tooltip ---

    /** The controller holds the whole table's state. */
    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        MapTableBlockEntity table = getControllerBE();
        if (table == null)
            return false;

        CDCLang.builder()
                .add(Component.translatable("createdangerclose.map_table.title"))
                .forGoggles(tooltip);
        CDCLang.builder()
                .add(Component.translatable(table.linked
                        ? "createdangerclose.map_table.connected" : "createdangerclose.map_table.not_connected"))
                .style(table.linked ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip);
        if (table.target != null) {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.map_table.target"))
                    .add(Component.literal(" ").withStyle(ChatFormatting.RESET))
                    .add(Component.literal(BlockPos.containing(table.target).toShortString()).withStyle(ChatFormatting.GOLD))
                    .forGoggles(tooltip);
        } else {
            CDCLang.builder()
                    .add(Component.translatable("createdangerclose.map_table.no_target"))
                    .style(ChatFormatting.DARK_GRAY)
                    .forGoggles(tooltip);
        }
        return true;
    }

    // --- Menu ---

    @Override
    public Component getDisplayName() {
        return Component.translatable("createdangerclose.map_table.title");
    }

    // Fully qualified: IMultiBlockEntityContainer's nested Inventory would shadow the import.
    @Override
    public AbstractContainerMenu createMenu(int containerId, net.minecraft.world.entity.player.Inventory playerInventory,
                                            Player player) {
        return new MapTableMenu(containerId, playerInventory, this);
    }

    // --- NBT ---

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);

        BlockPos controllerBefore = controller;
        int widthBefore = width;

        updateConnectivity = compound.contains("Uninitialized");
        lastKnownPos = compound.contains("LastKnownPos") ? NBTHelper.readBlockPos(compound, "LastKnownPos") : null;
        controller = compound.contains("Controller") ? NBTHelper.readBlockPos(compound, "Controller") : null;
        width = isController() ? Math.max(1, compound.getInt("Size")) : 1;
        inventory.deserializeNBT(registries, compound.getCompound("Inventory"));
        target = compound.contains("Target") ? readVec3(compound.getCompound("Target")) : null;
        targetTick = compound.getLong("TargetTick");
        if (clientPacket) {
            linked = compound.getBoolean("Linked");
            BattlePlot arrived = compound.contains("Plot") ? BattlePlot.read(compound.getCompound("Plot")) : BattlePlot.EMPTY;
            if (!arrived.equals(plot)) {
                // Glide on from wherever the drawing is now (it may be mid-glide).
                plotShownBefore = getShownPlot(0);
                plotArrivalTick = level != null ? level.getGameTime() : 0;
                plot = arrived;
            }
            CompoundTag placement = compound.getCompound("MapPlacement");
            ResourceLocation dimension = ResourceLocation.tryParse(placement.getString("Dimension"));
            clientMapPlacement = compound.contains("MapPlacement") && dimension != null
                    ? new MapPlacement(placement.getInt("CenterX"), placement.getInt("CenterZ"),
                            placement.getInt("Scale"), ResourceKey.create(Registries.DIMENSION, dimension))
                    : null;
        }

        // Rebuild the chunk mesh so connected textures follow the new shape.
        boolean controllerChanged = controllerBefore == null ? controller != null : !controllerBefore.equals(controller);
        if (clientPacket && hasLevel() && (controllerChanged || widthBefore != width))
            level.setBlocksDirty(getBlockPos(), Blocks.AIR.defaultBlockState(), getBlockState());
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        if (updateConnectivity)
            compound.putBoolean("Uninitialized", true);
        if (lastKnownPos != null)
            compound.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
        if (!isController())
            compound.put("Controller", NbtUtils.writeBlockPos(controller));
        if (isController())
            compound.putInt("Size", width);
        // Synced to clients too: the renderer and screen need to know which map lies where.
        compound.put("Inventory", inventory.serializeNBT(registries));
        if (target != null) {
            CompoundTag targetTag = new CompoundTag();
            targetTag.putDouble("X", target.x);
            targetTag.putDouble("Y", target.y);
            targetTag.putDouble("Z", target.z);
            compound.put("Target", targetTag);
            compound.putLong("TargetTick", targetTick);
        }
        // Only clients need these: the link lapses on its own, and the map knows where it lies.
        if (clientPacket) {
            compound.putBoolean("Linked", linked);
            if (!plot.isEmpty())
                compound.put("Plot", plot.write());
            MapPlacement map = getMapPlacement();
            if (map != null) {
                CompoundTag placement = new CompoundTag();
                placement.putInt("CenterX", map.centerX());
                placement.putInt("CenterZ", map.centerZ());
                placement.putInt("Scale", map.scale());
                placement.putString("Dimension", map.dimension().location().toString());
                compound.put("MapPlacement", placement);
            }
        }
        super.write(compound, registries, clientPacket);
    }

    private static Vec3 readVec3(CompoundTag tag) {
        return new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
    }
}