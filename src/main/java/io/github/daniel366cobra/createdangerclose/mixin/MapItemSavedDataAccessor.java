package io.github.daniel366cobra.createdangerclose.mixin;

import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** A map's markers by key; a player's arrow is keyed by the player's name. */
@Mixin(value = MapItemSavedData.class, remap = false)
public interface MapItemSavedDataAccessor {

    @Accessor("decorations")
    Map<String, MapDecoration> createdangerclose$getDecorations();
}