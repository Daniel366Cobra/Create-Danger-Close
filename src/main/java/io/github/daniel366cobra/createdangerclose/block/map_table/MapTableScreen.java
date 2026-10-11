package io.github.daniel366cobra.createdangerclose.block.map_table;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu.*;

/** Stitched maps on the left, a slot per table block on the right (north up), inventory below. */
public class MapTableScreen extends AbstractContainerScreen<MapTableMenu> {

    private static final int PANEL = 0xFFC6C6C6, PANEL_LIGHT = 0xFFFFFFFF, PANEL_DARK = 0xFF555555;
    private static final int SLOT_BG = 0xFF8B8B8B, SLOT_DARK = 0xFF373737;
    private static final int SHEET_FRAME = 0xFF373737;

    // Per table size: baize at one texel per GUI pixel, chalked with the cell lines.
    private static final ResourceLocation[] SHEET_BACKGROUNDS = {
            CreateDangerClose.asResource("textures/gui/map_table/map_background_small.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_medium.png"),
            CreateDangerClose.asResource("textures/gui/map_table/map_background_large.png")};
    private static final int BACKGROUND_TEXTURE_SIZE = 132;
    // Vanilla's label gray, a softer hint gray, the target's red.
    private static final int LABEL_COLOR = 0xFF404040, HINT_COLOR = 0xFF707070, TARGET_COLOR = 0xFFB02E26;

    // 1 = the whole sheet, doubling per scroll step.
    private static final float MAX_ZOOM = 8;

    private float zoom = 1;
    // Fractions of the sheet.
    private float viewU = 0, viewV = 0;
    private boolean panning;
    // Right-click marks the target, or (in this mode) draws safe zones.
    private boolean safeZoneMode;
    // The zone being dragged out: sheet fractions u0, v0, u1, v1.
    @Nullable private float[] drawing;

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
        // Below the slots and readout, beside the sheet's foot.
        addRenderableWidget(Button.builder(modeLabel(), button -> {
                    safeZoneMode = !safeZoneMode;
                    drawing = null;
                    button.setMessage(modeLabel());
                })
                .bounds(leftPos + SLOT_GRID_X, topPos + PREVIEW_Y + PREVIEW_SIZE - 20, IMAGE_WIDTH - SLOT_GRID_X - 6, 20)
                .tooltip(Tooltip.create(Component.translatable("createdangerclose.map_table.mode.tooltip")))
                .build());
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
            MapTablePlot.renderSafeZones(pose, graphics.bufferSource(), controller, zoomedSize, 1, 1.25f,
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

        graphics.disableScissor();

        if (zoom > 1) {
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
        int readoutWidth = IMAGE_WIDTH - SLOT_GRID_X - 6;
        MapTableBlockEntity controller = menu.getController();
        Vec3 target = controller != null ? controller.getTarget() : null;
        drawFitted(graphics, Component.translatable("createdangerclose.map_table.target"),
                SLOT_GRID_X, readoutY, readoutWidth, LABEL_COLOR);
        drawFitted(graphics, Component.literal(target != null
                        ? BlockPos.containing(target).toShortString()
                        : "—"),
                SLOT_GRID_X, readoutY + 10, readoutWidth, TARGET_COLOR);

        int hintWidth = IMAGE_WIDTH - 2 * PREVIEW_X;
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