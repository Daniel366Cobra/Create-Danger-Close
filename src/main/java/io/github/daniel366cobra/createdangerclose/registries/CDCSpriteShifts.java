package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.foundation.block.connected.AllCTTypes;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;
import com.simibubi.create.foundation.block.connected.CTType;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;

/** Connected texture sheets (Create's CT system). */
public class CDCSpriteShifts {

    // Map table: trim only along the whole table's outer edge.
    public static final CTSpriteShiftEntry
            MAP_TABLE_TOP = ct(AllCTTypes.RECTANGLE, "map_table/top"),
            MAP_TABLE_SIDE = ct(AllCTTypes.HORIZONTAL, "map_table/side"),
            MAP_TABLE_BOTTOM = ct(AllCTTypes.RECTANGLE, "map_table/bottom");

    // Computer casing joins in any shape: omnidirectional.
    public static final CTSpriteShiftEntry
            COMPUTER_CASING = ct(AllCTTypes.OMNIDIRECTIONAL, "computer/computer");

    /** Before the atlas is stitched: later entries never get their sprites, and render unconnected. */
    public static void init() {}

    /** {@code block/<name>} with {@code block/<name>_connected}. */
    private static CTSpriteShiftEntry ct(CTType type, String name) {
        return CTSpriteShifter.getCT(type,
                CreateDangerClose.asResource("block/" + name),
                CreateDangerClose.asResource("block/" + name + "_connected"));
    }
}