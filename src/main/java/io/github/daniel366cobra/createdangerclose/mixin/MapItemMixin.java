package io.github.daniel366cobra.createdangerclose.mixin;

import io.github.daniel366cobra.createdangerclose.block.map_table.SpottedMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Notes whose held map charted a pixel, so a table can tell a spotter's update from its own. */
@Mixin(value = MapItem.class, remap = false)
public abstract class MapItemMixin {

    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;updateColor(IIB)Z"))
    private boolean createdangerclose$noteSpotter(MapItemSavedData data, int x, int z, byte color,
                                                  Level level, Entity viewer, MapItemSavedData sameData) {
        boolean changed = data.updateColor(x, z, color);
        if (changed)
            ((SpottedMap) data).createdangerclose$spottedBy(viewer.getUUID());
        return changed;
    }
}