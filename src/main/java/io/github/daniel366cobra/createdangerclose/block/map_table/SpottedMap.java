package io.github.daniel366cobra.createdangerclose.block.map_table;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Mixed into map data: pixels charted by a player holding a copy (vanilla), as against a table's charting. */
public interface SpottedMap {

    /** Counts up each time a held copy changes a pixel. */
    int createdangerclose$getSpotterChanges();

    /** Whose held copy changed one last. */
    @Nullable
    UUID createdangerclose$getLastSpotter();

    void createdangerclose$spottedBy(UUID player);
}