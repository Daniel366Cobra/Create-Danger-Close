package io.github.daniel366cobra.createdangerclose.block.map_table;

import io.github.daniel366cobra.createdangerclose.registries.CDCMenuTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * The map table screen: one slot per table block, laid out like the table
 * (north up), plus the player's inventory.
 */
public class MapTableMenu extends AbstractContainerMenu {

    // Screen layout, shared with MapTableScreen.
    public static final int IMAGE_WIDTH = 212, IMAGE_HEIGHT = 271;
    public static final int PREVIEW_X = 8, PREVIEW_Y = 18, PREVIEW_SIZE = 132;
    public static final int SLOT_GRID_X = PREVIEW_X + PREVIEW_SIZE + 10, SLOT_GRID_Y = PREVIEW_Y;
    // Two lines of hints under the map sheet.
    public static final int HINTS_Y = PREVIEW_Y + PREVIEW_SIZE + 5;
    public static final int PLAYER_INV_X = (IMAGE_WIDTH - 162) / 2, PLAYER_INV_Y = 189;
    public static final int SLOT_SIZE = 18;

    @Nullable private final MapTableBlockEntity controller;
    private final int tableWidth;
    private final MapTableBlockEntity[] parts;

    /** Server side. */
    public MapTableMenu(int containerId, Inventory playerInventory, MapTableBlockEntity controller) {
        this(CDCMenuTypes.MAP_TABLE.get(), containerId, playerInventory, controller, controller.getWidth());
    }

    /** Client side: the table position and width come from the open packet. */
    public static MapTableMenu fromNetwork(MenuType<MapTableMenu> type, int containerId, Inventory playerInventory,
                                           RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int width = buf.readVarInt();
        MapTableBlockEntity controller = playerInventory.player.level().getBlockEntity(pos) instanceof MapTableBlockEntity be
                ? be : null;
        return new MapTableMenu(type, containerId, playerInventory, controller, width);
    }

    private MapTableMenu(MenuType<?> type, int containerId, Inventory playerInventory,
                         @Nullable MapTableBlockEntity controller, int tableWidth) {
        super(type, containerId);
        this.controller = controller;
        this.tableWidth = tableWidth;
        this.parts = new MapTableBlockEntity[tableWidth * tableWidth];

        // Always the same number of slots on both sides; a missing block gets
        // a dummy slot that accepts nothing.
        for (int dz = 0; dz < tableWidth; dz++) {
            for (int dx = 0; dx < tableWidth; dx++) {
                MapTableBlockEntity part = controller != null ? controller.partAt(dx, dz) : null;
                parts[dz * tableWidth + dx] = part;
                IItemHandler handler = part != null ? part.getInventory() : new ItemStackHandler(1) {
                    @Override
                    public boolean isItemValid(int slot, ItemStack stack) {
                        return false;
                    }
                };
                addSlot(new SlotItemHandler(handler, 0,
                        SLOT_GRID_X + dx * SLOT_SIZE + 1, SLOT_GRID_Y + dz * SLOT_SIZE + 1));
            }
        }

        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PLAYER_INV_X + col * SLOT_SIZE + 1, PLAYER_INV_Y + row * SLOT_SIZE + 1));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col,
                    PLAYER_INV_X + col * SLOT_SIZE + 1, PLAYER_INV_Y + 58 + 1));
    }

    @Nullable
    public MapTableBlockEntity getController() {
        return controller;
    }

    public int getTableWidth() {
        return tableWidth;
    }

    /** The table block at a cell (north-west = 0, 0), or null. */
    @Nullable
    public MapTableBlockEntity getPart(int dx, int dz) {
        return parts[dz * tableWidth + dx];
    }

    private int tableSlotCount() {
        return tableWidth * tableWidth;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem())
            return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < tableSlotCount()) {
            if (!moveItemStackTo(stack, tableSlotCount(), slots.size(), true))
                return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, tableSlotCount(), false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty())
            slot.setByPlayer(ItemStack.EMPTY);
        else
            slot.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return controller != null
                && !controller.isRemoved()
                && controller.isController()
                && controller.getWidth() == tableWidth
                && Container.stillValidBlockEntity(controller, player);
    }
}
