package io.github.daniel366cobra.createdangerclose.damage;

import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Lens dust, 0 (clean) to 1 (caked), settling with biome heat and dryness (none when cold). Rain or a
 * water bucket washes it off. Rates: config.
 */
public final class LensDust {

    private static final float TICKS_PER_DAY = 24000;
    // Biome temperature below which none settles.
    private static final float COLD_TEMPERATURE = 0.15f;
    // Desert, badlands, the Nether.
    private static final float HOT_TEMPERATURE = 2.0f;
    /** Goggles and particles show it from here. */
    public static final float DUSTY = 0.25f;

    private LensDust() {}

    /** After {@code ticks} of weather here. */
    public static float weather(Level level, BlockPos pos, float dust, int ticks) {
        if (level.isRainingAt(pos))
            return Math.max(0, dust - ticks / (20 * CDCConfig.server().maintenance.dust.rainWashSeconds.getF()));
        return Math.min(1, dust + settlingPerTick(level, pos) * ticks);
    }

    /** Per tick. */
    public static float settlingPerTick(Level level, BlockPos pos) {
        Biome biome = level.getBiome(pos).value();
        float temperature = biome.getBaseTemperature();
        if (temperature < COLD_TEMPERATURE)
            return 0;
        float heat = Mth.clamp((temperature - COLD_TEMPERATURE) / (HOT_TEMPERATURE - COLD_TEMPERATURE), 0, 1);
        float dryness = 1 - Mth.clamp(biome.getModifiedClimateSettings().downfall(), 0, 1);
        float climate = (0.25f + 0.75f * heat) * (0.3f + 0.7f * dryness);
        return climate / (CDCConfig.server().maintenance.dust.daysToCake.getF() * TICKS_PER_DAY);
    }
}