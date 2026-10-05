package io.github.daniel366cobra.createdangerclose.damage;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Dust settling on lenses: 0 (clean) to 1 (caked). It settles at a rate set by
 * the biome's heat and dryness: a desert or the Nether cakes a lens in a few
 * days, a temperate plain takes over a week, and cold biomes raise none. Rain
 * falling on the lens washes it off; so does a water bucket.
 */
public final class LensDust {

    // Days for a lens to cake over in the hottest, driest biome.
    private static final float DAYS_TO_CAKE_HOTTEST = 3;
    private static final float TICKS_PER_DAY = 24000;
    // Seconds of rain to wash a caked lens clean.
    private static final float RAIN_SECONDS_TO_CLEAN = 30;
    // Below this biome temperature no dust settles (snowy and cold biomes).
    private static final float COLD_TEMPERATURE = 0.15f;
    // The hottest biomes' temperature (desert, badlands, the Nether).
    private static final float HOT_TEMPERATURE = 2.0f;
    /** From this much on a lens counts as dusty (goggles, particles). */
    public static final float DUSTY = 0.25f;

    private LensDust() {}

    /** A lens's dust after {@code ticks} of weather at this position (in the world). */
    public static float weather(Level level, BlockPos pos, float dust, int ticks) {
        if (level.isRainingAt(pos))
            return Math.max(0, dust - ticks / (20 * RAIN_SECONDS_TO_CLEAN));
        return Math.min(1, dust + settlingPerTick(level, pos) * ticks);
    }

    /** How fast dust settles here, per tick. */
    public static float settlingPerTick(Level level, BlockPos pos) {
        Biome biome = level.getBiome(pos).value();
        float temperature = biome.getBaseTemperature();
        if (temperature < COLD_TEMPERATURE)
            return 0;
        float heat = Mth.clamp((temperature - COLD_TEMPERATURE) / (HOT_TEMPERATURE - COLD_TEMPERATURE), 0, 1);
        float dryness = 1 - Mth.clamp(biome.getModifiedClimateSettings().downfall(), 0, 1);
        float climate = (0.25f + 0.75f * heat) * (0.3f + 0.7f * dryness);
        return climate / (DAYS_TO_CAKE_HOTTEST * TICKS_PER_DAY);
    }
}
