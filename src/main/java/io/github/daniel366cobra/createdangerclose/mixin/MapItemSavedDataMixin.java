package io.github.daniel366cobra.createdangerclose.mixin;

import io.github.daniel366cobra.createdangerclose.block.map_table.SpottedMap;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/** Not saved: tables only compare it between their syncs. */
@Mixin(value = MapItemSavedData.class, remap = false)
public abstract class MapItemSavedDataMixin implements SpottedMap {

    @Unique
    private int createdangerclose$spotterChanges;
    @Unique
    @Nullable
    private UUID createdangerclose$lastSpotter;

    @Override
    public int createdangerclose$getSpotterChanges() {
        return createdangerclose$spotterChanges;
    }

    @Override
    @Nullable
    public UUID createdangerclose$getLastSpotter() {
        return createdangerclose$lastSpotter;
    }

    @Override
    public void createdangerclose$spottedBy(UUID player) {
        createdangerclose$spotterChanges++;
        createdangerclose$lastSpotter = player;
    }
}