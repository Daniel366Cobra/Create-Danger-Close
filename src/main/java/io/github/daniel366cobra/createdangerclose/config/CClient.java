package io.github.daniel366cobra.createdangerclose.config;

import net.minecraft.world.item.DyeColor;

/** Each player's own settings: looks only, nothing that changes the game. */
public class CClient extends CDCConfigBase {

    public final ConfigBool damageParticles = b(true, "damageParticles",
            "Whether damaged, seized and dusty equipment gives off smoke, sparks and dust.");

    public final ConfigGroup mapTable = group(0, "mapTable", "How the battle plot is drawn on map tables");
    public final ConfigEnum<DyeColor> gunRangeColor = e(DyeColor.RED, "gunRangeColor",
            "Each gun's reach: its range ring (or fire sector) and dead zone.");
    public final ConfigEnum<DyeColor> gunAimColor = e(DyeColor.RED, "gunAimColor",
            "The line along each gun's barrel.");
    public final ConfigEnum<DyeColor> rangefinderColor = e(DyeColor.BLUE, "rangefinderColor",
            "Each rangefinder's line of sight, and the circle its target lies within.");
    public final ConfigDouble lineWidth = d(1, 0.5, 3, "lineWidth",
            "[in map pixels]", "How thick the plot lines are.");

    @Override
    public String getName() {
        return "client";
    }
}
