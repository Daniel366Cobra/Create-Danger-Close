package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.network.MapTableTargetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

import static io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu.*;

/**
 * Map table screen: the table's maps stitched into one sheet on the left, a
 * slot per table block on the right (laid out like the table, north up), the
 * player's inventory below. The sheet lies on a baize background (a texture); the
 * panel and the slots are drawn with plain fills, vanilla-style.
 */
public class MapTableScreen extends AbstractContainerScreen<MapTableMenu> {

    private static final int PANEL = 0xFFC6C6C6, PANEL_LIGHT = 0xFFFFFFFF, PANEL_DARK = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B, SLOT_DARK = 0xFF373737;
    private static final int SHEET_FRAME = 0xFF373737;

    // The sheet's background, one per table size (1×1, 2×2, 3×3): the same baize, the size of
    // the sheet so it shows one texel per GUI pixel, chalked with the lines between the cells.
    private static final ResourceLocation[] SHEET_BACKGROUNDS = {
            CreateDangerClose.asResource("textures/gui/map_table/map_background_small.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_medium.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_large.png")};
    private static final int BACKGROUND_TEXTURE_SIZE = 132;
    // Text: vanilla's label grey, a softer grey for hints, the red of the target's X.
    private static final int LABEL_COLOR = 0xFF404040, HINT_COLOR = 0xFF707070, TARGET_COLOR = 0xFFB02E26;

    // Zoom: 1 = the whole sheet, doubling per scroll step.
    private static final float MAX_ZOOM = 8;

    private float zoom = 1;
    // North-west corner of the view window, as fractions of the whole sheet.
    private float viewU = 0, viewV = 0;
    private boolean panning;

    public MapTableScreen(MapTableMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = IMAGE_WIDTH;
        imageHeight = IMAGE_HEIGHT;
        inventoryLabelX = PLAYER_INV_X + 1;
        inventoryLabelY = PLAYER_INV_Y - 11;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;

        // Panel with a bevelled edge.
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

    /**
     * The table's maps stitched together, north up, seen through the zoomed
     * view window and clipped to the sheet frame.
     */
    private void renderSheet(GuiGraphics graphics, int sheetX, int sheetY) {
        int size = sheetSize();
        graphics.fill(sheetX - 1, sheetY - 1, sheetX + size + 1, sheetY + size + 1, SHEET_FRAME);

        int tableWidth = menu.getTableWidth();
        int cell = size / Math.max(1, tableWidth);
        PoseStack pose = graphics.pose();
        float zoomedSize = size * zoom;
        // Sheet origin on screen: the view window's corner lands on the frame's corner.
        float originX = sheetX - viewU * zoomedSize, originY = sheetY - viewV * zoomedSize;

        graphics.enableScissor(sheetX, sheetY, sheetX + size, sheetY + size);

        pose.pushPose();
        pose.translate(originX, originY, 0);
        pose.scale(zoom, zoom, 1);
        // The baize, under the maps (empty cells show it).
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
                // Depth flipped: the map quad faces away from the GUI camera, and vanilla stacks the
                // markers just behind the map's face, which here would hide them under the paper.
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
            // Plot lines keep their thickness when zoomed: one line pixel per GUI pixel,
            // between the maps (z ≈ 1) and the markers.
            pose.pushPose();
            pose.translate(originX, originY, 0);
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
            // Markers keep their size when zoomed: one sprite texel per GUI pixel,
            // in front of the maps (drawn at z ≈ 1), later ones on top.
            for (int i = 0; i < markers.size(); i++)
                MapTableMarker.render(pose, graphics.bufferSource(), markers.get(i), zoomedSize, 8, 2 + i,
                        LightTexture.FULL_BRIGHT);
            graphics.flush();
            RenderSystem.enableCull();
            pose.popPose();
        }

        graphics.disableScissor();

        if (zoom > 1) {
            // In front of the maps and markers.
            pose.pushPose();
            pose.translate(0, 0, 10);
            graphics.drawString(font, "×" + (int) zoom, sheetX + 2, sheetY + size - 9, 0xFFFFFFFF, true);
            pose.popPose();
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

    /** The point of the whole sheet under the mouse, as fractions of its size (north-west = 0, 0). */
    private double sheetU(double mouseX) {
        return viewU + (mouseX - (leftPos + PREVIEW_X)) / sheetSize() / zoom;
    }

    private double sheetV(double mouseY) {
        return viewV + (mouseY - (topPos + PREVIEW_Y)) / sheetSize() / zoom;
    }

    /** Keeps the view window inside the sheet. */
    private void clampView() {
        float maxCorner = 1 - 1 / zoom;
        viewU = Mth.clamp(viewU, 0, maxCorner);
        viewV = Mth.clamp(viewV, 0, maxCorner);
    }

    /** Scrolling over the sheet zooms in and out around the point under the mouse. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0 || !isOverSheet(mouseX, mouseY))
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        double u = sheetU(mouseX), v = sheetV(mouseY);
        float newZoom = Mth.clamp(scrollY > 0 ? zoom * 2 : zoom / 2, 1, MAX_ZOOM);
        if (newZoom != zoom) {
            zoom = newZoom;
            // Keep the point under the mouse where it is.
            viewU = (float) (u - (mouseX - (leftPos + PREVIEW_X)) / sheetSize() / zoom);
            viewV = (float) (v - (mouseY - (topPos + PREVIEW_Y)) / sheetSize() / zoom);
            clampView();
        }
        return true;
    }

    /**
     * Right-click on the sheet designates the target there; shift + right-click
     * clears it. Left-click starts dragging the view around.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        MapTableBlockEntity controller = menu.getController();
        if (controller != null && isOverSheet(mouseX, mouseY) && menu.getCarried().isEmpty()) {
            if (button == 0) {
                panning = true;
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (panning && button == 0) {
            panning = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Title and inventory label, plus the target readout under the slots and the hints under the maps. */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);

        int readoutY = SLOT_GRID_Y + menu.getTableWidth() * SLOT_SIZE + 5;
        int readoutWidth = IMAGE_WIDTH - SLOT_GRID_X - 6;
        MapTableBlockEntity controller = menu.getController();
        Vec3 target = controller != null ? controller.getTarget() : null;
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.target"),
                SLOT_GRID_X, readoutY, readoutWidth, LABEL_COLOR);
        drawFitted(graphics, Component.literal(target != null
                        ? String.format("%.0f, %.0f, %.0f", target.x, target.y, target.z)
                        : "—"),
                SLOT_GRID_X, readoutY + 10, readoutWidth, TARGET_COLOR);

        int hintWidth = IMAGE_WIDTH - 2 * PREVIEW_X;
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.hint.target"),
                PREVIEW_X, HINTS_Y, hintWidth, HINT_COLOR);
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.hint.view"),
                PREVIEW_X, HINTS_Y + 10, hintWidth, HINT_COLOR);
    }

    /** Draws a line of text, shrunk to fit if it is wider than maxWidth. */
    private void drawFitted(GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        int width = font.width(text);
        if (width <= maxWidth) {
            graphics.drawString(font, text, x, y, color, false);
            return;
        }
        float scale = (float) maxWidth / width;
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // Keep the shrunk text vertically centred on its line.
        pose.translate(x, y + font.lineHeight * (1 - scale) / 2, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, text, 0, 0, color, false);
        pose.popPose();
    }

    /** A vanilla-style sunken slot, 18×18. */
    private static void slotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);
        graphics.fill(x, y, x + SLOT_SIZE - 1, y + 1, SLOT_DARK);
        graphics.fill(x, y, x + 1, y + SLOT_SIZE - 1, SLOT_DARK);
        graphics.fill(x + 1, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_LIGHT);
        graphics.fill(x + SLOT_SIZE - 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_LIGHT);
    }
}
