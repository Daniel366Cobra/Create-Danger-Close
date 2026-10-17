package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.foundation.plot.BattlePlot;
import net.createmod.catnip.animation.AnimationTickHolder;
import io.github.daniel366cobra.createdangerclose.network.MapTableSafeZonePayload;
import io.github.daniel366cobra.createdangerclose.network.MapTableTargetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu.*;

/** Stitched maps on the left; on the right a slot per table block (north up), the target, and the inventory. */
public class MapTableScreen extends AbstractContainerScreen<MapTableMenu> {

    private static final int PANEL = 0xFFC6C6C6, PANEL_LIGHT = 0xFFFFFFFF, PANEL_DARK = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B, SLOT_DARK = 0xFF373737;
    private static final int SHEET_FRAME = 0xFF373737;

    // Per table size: baize at one texel per GUI pixel, chalked with the cell lines.
    private static final ResourceLocation[] SHEET_BACKGROUNDS = {
            CreateDangerClose.asResource("textures/gui/map_table/map_background_1.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_2.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_3.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_4.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_5.png")};
    private static final int BACKGROUND_TEXTURE_SIZE = PREVIEW_SIZE;
    // Clear of a 5×5 slot grid by 2 pixels.
    private static final int BUTTON_WIDTH = 70, BUTTON_PITCH = 22;
    // Vanilla's label gray, a softer hint gray, the target's red.
    private static final int LABEL_COLOR = 0xFF404040, HINT_COLOR = 0xFF707070, TARGET_COLOR = 0xFFB02E26;

    // 1 = the whole sheet, doubling per scroll step.
    private static final float MAX_ZOOM = 8;

    // Gun labels, GUI pixels: kept off each gun's point, first tried this far past that, then nudged out.
    private static final int GUN_CLEARANCE = 2, LABEL_GAP = 1, LABEL_NUDGE = 5, LABEL_NUDGES = 3;
    // Blocks: guns of one name within this of each other share a label.
    private static final double TURRET_SPREAD = 16;
    private static final int GUN_LABEL_BACKDROP = 0xA0202020, GUN_LABEL_COLOR = 0xFFFFFFFF;

    private float zoom = 1;
    // Fractions of the sheet.
    private float viewU = 0, viewV = 0;
    private boolean panning;
    // Right-click marks the target, or (in this mode) draws safe zones.
    private boolean safeZoneMode;
    // The zone being dragged out: sheet fractions u0, v0, u1, v1.
    @Nullable private float[] drawing;
    private Button labelsButton;
    // Gun labels: last frame's spot for each, by name and turn among same-named ones.
    private Map<String, Integer> lastLabelSpots = Map.of();

    public MapTableScreen(MapTableMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelX = PLAYER_INV_X + 1;
        inventoryLabelY = PLAYER_INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        // Top right, stacked beside the slot grid (5 wide at most), above the target readout.
        int buttonX = leftPos + IMAGE_WIDTH - 8 - BUTTON_WIDTH;
        addRenderableWidget(Button.builder(modeLabel(), button -> {
                    safeZoneMode = !safeZoneMode;
                    drawing = null;
                    button.setMessage(modeLabel());
                })
                .bounds(buttonX, topPos + PREVIEW_Y, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("createdangerclose.map_table.mode.tooltip")))
                .build());
        // The table's own setting: the label follows it, whoever toggles it.
        labelsButton = addRenderableWidget(Button.builder(labelsLabel(),
                        button -> pressMenuButton(LABELS_BUTTON, SoundEvents.UI_BUTTON_CLICK.value()))
                .bounds(buttonX, topPos + PREVIEW_Y + BUTTON_PITCH, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("createdangerclose.map_table.labels.tooltip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("createdangerclose.map_table.clear.button"),
                        button -> pressMenuButton(CLEAR_BUTTON, SoundEvents.BOOK_PAGE_TURN))
                .bounds(buttonX, topPos + PREVIEW_Y + 2 * BUTTON_PITCH, BUTTON_WIDTH, 20)
                .tooltip(Tooltip.create(Component.translatable("createdangerclose.map_table.clear.tooltip")))
                .build());
    }

    /** Done on the server, through vanilla's menu button packet. */
    private void pressMenuButton(int id, SoundEvent sound) {
        if (minecraft == null || minecraft.gameMode == null)
            return;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1f));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        labelsButton.setMessage(labelsLabel());
    }

    private boolean showsGunLabels() {
        MapTableBlockEntity controller = menu.getController();
        return controller == null || controller.showsGunLabels();
    }

    private Component labelsLabel() {
        return Component.translatable(showsGunLabels()
                ? "createdangerclose.map_table.labels.on" : "createdangerclose.map_table.labels.off");
    }

    private Component modeLabel() {
        return Component.translatable(safeZoneMode
                ? "createdangerclose.map_table.mode.safe_zone" : "createdangerclose.map_table.mode.target");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;

        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        graphics.fill(x, y, x + imageWidth - 1, y + 1, PANEL_LIGHT);
        graphics.fill(x, y, x + 1, y + imageHeight - 1, PANEL_LIGHT);
        graphics.fill(x + 1, y + imageHeight - 1, x + imageWidth, y + imageHeight, PANEL_DARK);
        graphics.fill(x + imageWidth - 1, y + 1, x + imageWidth, y + imageHeight, PANEL_DARK);

        renderSheet(graphics, x + PREVIEW_X, y + PREVIEW_Y);

        int tableWidth = menu.getTableWidth();
        for (int dz = 0; dz < tableWidth; dz++)
            for (int dx = 0; dx < tableWidth; dx++)
                slotFrame(graphics, x + SLOT_GRID_X + dx * SLOT_SIZE, y + SLOT_GRID_Y + dz * SLOT_SIZE);
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                slotFrame(graphics, x + PLAYER_INV_X + col * SLOT_SIZE, y + PLAYER_INV_Y + row * SLOT_SIZE);
        for (int col = 0; col < 9; col++)
            slotFrame(graphics, x + PLAYER_INV_X + col * SLOT_SIZE, y + PLAYER_INV_Y + 58);
    }

    /** North up, through the zoomed view window, clipped to the frame. */
    private void renderSheet(GuiGraphics graphics, int sheetX, int sheetY) {
        int size = sheetSize();
        graphics.fill(sheetX - 1, sheetY - 1, sheetX + size + 1, sheetY + size + 1, SHEET_FRAME);

        int tableWidth = menu.getTableWidth();
        int cell = size / Math.max(1, tableWidth);
        PoseStack pose = graphics.pose();
        float zoomedSize = size * zoom;
        float originX = sheetX - viewU * zoomedSize, originY = sheetY - viewV * zoomedSize;

        graphics.enableScissor(sheetX, sheetY, sheetX + size, sheetY + size);

        pose.pushPose();
        pose.translate(originX, originY, 0);
        pose.scale(zoom, zoom, 1);
        // Empty cells show the baize.
        ResourceLocation background = SHEET_BACKGROUNDS[Mth.clamp(tableWidth, 1, SHEET_BACKGROUNDS.length) - 1];
        graphics.blit(background, 0, 0, size, size, 0, 0, BACKGROUND_TEXTURE_SIZE, BACKGROUND_TEXTURE_SIZE,
                BACKGROUND_TEXTURE_SIZE, BACKGROUND_TEXTURE_SIZE);
        for (int dz = 0; dz < tableWidth; dz++) {
            for (int dx = 0; dx < tableWidth; dx++) {
                int cellX = dx * cell, cellY = dz * cell;
                MapTableBlockEntity part = menu.getPart(dx, dz);
                ItemStack map = part != null ? part.getMap() : ItemStack.EMPTY;
                if (map.isEmpty())
                    continue;

                pose.pushPose();
                pose.translate(cellX, cellY, 1);
                // Depth flipped: vanilla puts markers just behind the map's face, which here would hide them.
                pose.scale(cell / 128f, cell / 128f, -1);
                RenderSystem.disableCull();
                MapTableMaps.render(pose, graphics.bufferSource(), map, LightTexture.FULL_BRIGHT);
                graphics.flush();
                RenderSystem.enableCull();
                pose.popPose();
            }
        }
        pose.popPose();

        MapTableBlockEntity controller = menu.getController();
        if (controller != null) {
            // One line pixel per GUI pixel at any zoom, between maps (z ≈ 1) and markers.
            pose.pushPose();
            pose.translate(originX, originY, 0);
            MapTablePlot.renderSafeZones(pose, graphics.bufferSource(), controller, zoomedSize, 1, 1.25f, 1.375f,
                    LightTexture.FULL_BRIGHT, drawing);
            MapTablePlot.render(pose, graphics.bufferSource(), controller, zoomedSize, 1, 1.5f,
                    LightTexture.FULL_BRIGHT);
            graphics.flush();
            pose.popPose();
        }

        List<MapTableMarker.Marker> markers = controller != null ? MapTableMarker.computeAll(controller) : List.of();
        if (!markers.isEmpty()) {
            pose.pushPose();
            pose.translate(originX, originY, 0);
            RenderSystem.disableCull();
            // One sprite texel per GUI pixel at any zoom, in front of the maps, later ones on top.
            for (int i = 0; i < markers.size(); i++)
                MapTableMarker.render(pose, graphics.bufferSource(), markers.get(i), zoomedSize, 8, 2 + i,
                        LightTexture.FULL_BRIGHT);
            graphics.flush();
            RenderSystem.enableCull();
            pose.popPose();
        }

        if (controller != null && controller.showsGunLabels()) {
            pose.pushPose();
            pose.translate(0, 0, 3 + markers.size());
            renderGunLabels(graphics, controller, sheetX, sheetY, size, originX, originY, zoomedSize);
            pose.popPose();
        }

        graphics.disableScissor();

        if (zoom > 1) {
            pose.pushPose();
            pose.translate(0, 0, 10);
            graphics.drawString(font, "×" + (int) zoom, sheetX + 2, sheetY + size - 9, 0xFFFFFFFF, true);
            pose.popPose();
        }
    }

    /**
     * A label per named gun or turret, at one size at any zoom. Placed in name order, each beside its
     * guns on the first side clear of other guns and earlier labels, else further out; left off if no
     * spot nearby is clear. A label keeps last frame's spot while it stays clear, so panning across a
     * pixel boundary does not flip it.
     */
    private void renderGunLabels(GuiGraphics graphics, MapTableBlockEntity controller, int sheetX, int sheetY,
                                 int size, float originX, float originY, float zoomedSize) {
        BattlePlot plot = controller.getShownPlot(AnimationTickHolder.getPartialTicks());
        MapTableBlockEntity.Sheet sheet = controller.getSheet();
        if (plot.guns().isEmpty() || sheet == null || controller.getLevel() == null
                || !sheet.dimension().equals(controller.getLevel().dimension()))
            return;

        // Guns of one name close together share a label (a turret), beside all of them.
        List<GunLabel> labels = new ArrayList<>();
        List<Obstacle> taken = new ArrayList<>();
        for (BattlePlot.GunRange gun : plot.guns()) {
            int x = Math.round(originX + (float) (sheet.pixelX(gun.x()) / sheet.sizeInPixels()) * zoomedSize);
            int y = Math.round(originY + (float) (sheet.pixelY(gun.z()) / sheet.sizeInPixels()) * zoomedSize);
            GunLabel label = null;
            if (!gun.name().isEmpty()) {
                for (GunLabel other : labels) {
                    if (other.name.equals(gun.name())
                            && Math.hypot(other.firstX - gun.x(), other.firstZ - gun.z()) < TURRET_SPREAD) {
                        label = other;
                        break;
                    }
                }
                if (label == null)
                    labels.add(label = new GunLabel(gun.name(), gun.x(), gun.z(), x, y));
                label.include(x, y);
            }
            taken.add(new Obstacle(new int[]{x - GUN_CLEARANCE, y - GUN_CLEARANCE,
                    x + GUN_CLEARANCE + 1, y + GUN_CLEARANCE + 1}, label));
        }
        labels.sort(Comparator.comparing((GunLabel label) -> label.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparingDouble(label -> label.firstX).thenComparingDouble(label -> label.firstZ));
        if (zoom > 1)
            taken.add(new Obstacle(new int[]{sheetX, sheetY + size - 10,
                    sheetX + 2 + font.width("×" + (int) zoom), sheetY + size}, null));

        Map<String, Integer> placed = new HashMap<>();
        Map<String, Integer> sameName = new HashMap<>();
        for (GunLabel label : labels) {
            String key = label.name + "#" + sameName.merge(label.name, 1, Integer::sum);
            if (label.maxX < sheetX || label.maxY < sheetY || label.minX > sheetX + size || label.minY > sheetY + size)
                continue;
            int width = font.width(label.name) + 2, height = font.lineHeight + 1;
            // Its guns' extent, with their clearance; spots are numbered ring by ring, four sides each.
            int left = label.minX - GUN_CLEARANCE, right = label.maxX + GUN_CLEARANCE + 1;
            int top = label.minY - GUN_CLEARANCE, bottom = label.maxY + GUN_CLEARANCE + 1;
            int midX = (left + right) / 2, midY = (top + bottom) / 2;
            int spots = 4 * (LABEL_NUDGES + 1);
            int previous = lastLabelSpots.getOrDefault(key, -1);
            int[] spot = null;
            for (int i = previous >= 0 ? -1 : 0; spot == null && i < spots; i++) {
                int n = i < 0 ? previous : i;
                int gap = LABEL_GAP + LABEL_NUDGE * (n / 4);
                int[] corner = switch (n % 4) {
                    case 0 -> new int[]{right + gap, midY - height / 2};
                    case 1 -> new int[]{left - gap - width, midY - height / 2};
                    case 2 -> new int[]{midX - width / 2, top - gap - height};
                    default -> new int[]{midX - width / 2, bottom + gap};
                };
                int[] box = {corner[0], corner[1], corner[0] + width, corner[1] + height};
                if (box[0] >= sheetX && box[1] >= sheetY && box[2] <= sheetX + size && box[3] <= sheetY + size
                        && taken.stream().noneMatch(other -> other.owner != label && overlaps(box, other.box))) {
                    spot = box;
                    placed.put(key, n);
                }
            }
            if (spot == null)
                continue;
            taken.add(new Obstacle(spot, null));
            graphics.fill(spot[0], spot[1], spot[2], spot[3], GUN_LABEL_BACKDROP);
            graphics.drawString(font, label.name, spot[0] + 1, spot[1] + 1, GUN_LABEL_COLOR, false);
        }
        lastLabelSpots = placed;
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** A gun (its label's guns do not block it) or a placed label. */
    private record Obstacle(int[] box, @Nullable GunLabel owner) {}

    /** Its guns' extent on screen. */
    private static final class GunLabel {
        final String name;
        final double firstX, firstZ;
        int minX, minY, maxX, maxY;

        GunLabel(String name, double firstX, double firstZ, int x, int y) {
            this.name = name;
            this.firstX = firstX;
            this.firstZ = firstZ;
            minX = maxX = x;
            minY = maxY = y;
        }

        void include(int x, int y) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
    }

    private int sheetSize() {
        int tableWidth = Math.max(1, menu.getTableWidth());
        return PREVIEW_SIZE / tableWidth * tableWidth;
    }

    private boolean isOverSheet(double mouseX, double mouseY) {
        double x = mouseX - (leftPos + PREVIEW_X), y = mouseY - (topPos + PREVIEW_Y);
        return x >= 0 && y >= 0 && x < sheetSize() && y < sheetSize();
    }

    /** Fractions of the sheet (north-west = 0, 0). */
    private double sheetU(double mouseX) {
        return viewU + (mouseX - (leftPos + PREVIEW_X)) / sheetSize() / zoom;
    }

    private double sheetV(double mouseY) {
        return viewV + (mouseY - (topPos + PREVIEW_Y)) / sheetSize() / zoom;
    }

    private void clampView() {
        float maxCorner = 1 - 1 / zoom;
        viewU = Mth.clamp(viewU, 0, maxCorner);
        viewV = Mth.clamp(viewV, 0, maxCorner);
    }

    /** Zooms around the point under the mouse. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !isOverSheet(mouseX, mouseY))
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double u = sheetU(mouseX), v = sheetV(mouseY);
        float newZoom = Mth.clamp(scrollY > 0 ? zoom * 2 : zoom / 2, 1, MAX_ZOOM);
        if (newZoom != zoom) {
            zoom = newZoom;
            viewU = (float) (u - (mouseX - (leftPos + PREVIEW_X)) / sheetSize() / zoom);
            viewV = (float) (v - (mouseY - (topPos + PREVIEW_Y)) / sheetSize() / zoom);
            clampView();
        }
        return true;
    }

    /**
     * Right-click marks the target, Shift + right-click clears it, left-click drags the view. In safe
     * zone mode, right-drag draws a zone and Shift + right-click removes the one under the mouse.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MapTableBlockEntity controller = menu.getController();
        if (controller != null && isOverSheet(mouseX, mouseY) && menu.getCarried().isEmpty()) {
            if (button == 0) {
                panning = true;
                return true;
            }
            if (button == 1 && safeZoneMode) {
                if (controller.getSheet() == null)
                    return true;
                float u = (float) sheetU(mouseX), v = (float) sheetV(mouseY);
                if (hasShiftDown()) {
                    PacketDistributor.sendToServer(new MapTableSafeZonePayload(controller.getBlockPos(), true, u, v, u, v));
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
                } else {
                    drawing = new float[]{u, v, u, v};
                }
                return true;
            }
            if (button == 1) {
                boolean clear = hasShiftDown();
                if (clear || controller.getSheet() != null) {
                    float u = (float) sheetU(mouseX), v = (float) sheetV(mouseY);
                    PacketDistributor.sendToServer(new MapTableTargetPayload(controller.getBlockPos(), clear, u, v));
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1f));
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (panning && button == 0) {
            viewU -= (float) (dragX / sheetSize() / zoom);
            viewV -= (float) (dragY / sheetSize() / zoom);
            clampView();
            return true;
        }
        if (drawing != null && button == 1) {
            drawing[2] = (float) Mth.clamp(sheetU(mouseX), 0, 1);
            drawing[3] = (float) Mth.clamp(sheetV(mouseY), 0, 1);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == 0) {
            panning = false;
            return true;
        }
        if (drawing != null && button == 1) {
            MapTableBlockEntity controller = menu.getController();
            if (controller != null) {
                PacketDistributor.sendToServer(new MapTableSafeZonePayload(controller.getBlockPos(), false,
                        drawing[0], drawing[1], drawing[2], drawing[3]));
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1f));
            }
            drawing = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Plus the target readout and the hints. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);

        int readoutY = SLOT_GRID_Y + menu.getTableWidth() * SLOT_SIZE + 5;
        // Beside the buttons under a small slot grid, short of them.
        int readoutWidth = readoutY < PREVIEW_Y + 2 * BUTTON_PITCH + 20
                ? IMAGE_WIDTH - 8 - BUTTON_WIDTH - 4 - SLOT_GRID_X
                : IMAGE_WIDTH - SLOT_GRID_X - 8;
        MapTableBlockEntity controller = menu.getController();
        Vec3 target = controller != null ? controller.getTarget() : null;
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.target"),
                SLOT_GRID_X, readoutY, readoutWidth, LABEL_COLOR);
        drawFitted(graphics, Component.literal(target != null
                        ? BlockPos.containing(target).toShortString()
                        : "—"),
                SLOT_GRID_X, readoutY + 10, readoutWidth, TARGET_COLOR);

        // Under the sheet, clear of the inventory.
        int hintWidth = PREVIEW_SIZE;
        drawFitted(graphics, Component.translatable(safeZoneMode
                        ? "createdangerclose.map_table.hint.safe_zone" : "createdangerclose.map_table.hint.target"),
                PREVIEW_X, HINTS_Y, hintWidth, HINT_COLOR);
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.hint.view"),
                PREVIEW_X, HINTS_Y + 10, hintWidth, HINT_COLOR);
    }

    /** Shrunk to fit maxWidth. */
    private void drawFitted(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float scale = (float) maxWidth / width;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x, y + font.lineHeight * (1 - scale) / 2, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, false);
        pose.popPose();
    }

    /** 18×18. */
    private static void slotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);
        graphics.fill(x, y, x + SLOT_SIZE - 1, y + 1, SLOT_DARK);
        graphics.fill(x, y, x + 1, y + SLOT_SIZE - 1, SLOT_DARK);
        graphics.fill(x + 1, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_LIGHT);
        graphics.fill(x + SLOT_SIZE - 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_LIGHT);
    }
}
