package io.github.daniel366cobra.createdangerclose;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class CDCStats {
    private static final DeferredRegister<ResourceLocation> CUSTOM_STAT =
            DeferredRegister.create(BuiltInRegistries.CUSTOM_STAT, CreateDangerClose.MOD_ID);

    // Track stats for bootstrapping (so they appear in the menu)
    private static final java.util.ArrayList<Stat> STATS_TO_BOOTSTRAP = new java.util.ArrayList<>();

    // --- Stat Definitions ---
    public static final Stat LENSES_CLEANED = makeStat("lenses_cleaned", StatFormatter.DEFAULT);
    public static final Stat EQUIPMENT_REALIGNED = makeStat("equipment_realigned", StatFormatter.DEFAULT);
    public static final Stat EQUIPMENT_REPAIRED = makeStat("equipment_repaired", StatFormatter.DEFAULT);

    // --- Helper method to create a stat ---
    private static Stat makeStat(String key, StatFormatter formatter) {
        ResourceLocation id = CreateDangerClose.asResource(key);
        Supplier<ResourceLocation> statId = CUSTOM_STAT.register(key, () -> id);
        Stat stat = new Stat(statId, formatter);
        STATS_TO_BOOTSTRAP.add(stat);
        return stat;
    }

    // --- Registration method (called from main class) ---
    public static void register(IEventBus modEventBus) {
        CUSTOM_STAT.register(modEventBus);
    }

    // --- Bootstrap method (called after registries are frozen) ---
    public static void bootstrap() {
        for (Stat stat : STATS_TO_BOOTSTRAP) {
            Stats.CUSTOM.get(stat.identifier().get(), stat.formatter());
        }
        STATS_TO_BOOTSTRAP.clear(); // Free memory
    }

    // --- Inner record to hold stat data ---
    public record Stat(Supplier<ResourceLocation> identifier, StatFormatter formatter) {
        public void awardTo(Player player) {
            player.awardStat(this.identifier.get());
        }
    }
}