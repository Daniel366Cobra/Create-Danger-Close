package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.util.entry.MenuEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableMenu;
import io.github.daniel366cobra.createdangerclose.block.map_table.MapTableScreen;

public class CDCMenuTypes {

    private static final CreateRegistrate REGISTRATE = CreateDangerClose.getRegistrate();

    public static final MenuEntry<MapTableMenu> MAP_TABLE = REGISTRATE
            .menu("map_table", MapTableMenu::fromNetwork, () -> MapTableScreen::new)
            .register();

    /** Forces class loading so the entries are registered. */
    public static void register() {}
}