package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.CTType;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;

/** Connected texture sheets (Create's CT system). */
public class CDCSpriteShifts {

    // Map table: frame trim only along the outer edge of the whole table.
    public static final CTSpriteShiftEntry
            MAP_TABLE_TOP = ct(AllCTTypes.RECTANGLE, "map_table/top"),
            MAP_TABLE_SIDE = ct(AllCTTypes.HORIZONTAL, "map_table/side"),
            MAP_TABLE_BOTTOM = ct(AllCTTypes.RECTANGLE, "map_table/bottom");

    /**
     * Loads this class so the entries exist before the block atlas is stitched.
     * Entries created later never pick up their sprites (until a resource
     * reload), and the blocks render unconnected.
     */
    public static void init() {}

    /** Block texture {@code block/<name>} with its sheet {@code block/<name>_connected}. */
    private static CTSpriteShiftEntry ct(CTType type, String name) {
        return CTSpriteShifter.getCT(type,
                CreateDangerClose.asResource("block/" + name),
                CreateDangerClose.asResource("block/" + name + "_connected"));
    }
}
