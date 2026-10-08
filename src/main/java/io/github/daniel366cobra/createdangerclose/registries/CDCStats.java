package io.github.daniel366cobra.createdangerclose.registries;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The mod's statistics, as listed in the statistics screen. */
public class CDCStats {

    private static final DeferredRegister<ResourceLocation> CUSTOM_STAT =
            DeferredRegister.create(BuiltInRegistries.CUSTOM_STAT, CreateDangerClose.MOD_ID);
    // Looked up once registered, so they show in the statistics screen.
    private static final List<Stat> STATS_TO_BOOTSTRAP = new ArrayList<>();

    /** Dusty lenses cleaned with a water bucket. */
    public static final Stat LENSES_CLEANED = makeStat("lenses_cleaned", StatFormatter.DEFAULT);
    /** Jostled equipment realigned with a wrench. */
    public static final Stat EQUIPMENT_REALIGNED = makeStat("equipment_realigned", StatFormatter.DEFAULT);
    /** Equipment put back in order: a broken cog or mechanism, a cracked lens or a seized mechanism. */
    public static final Stat EQUIPMENT_REPAIRED = makeStat("equipment_repaired", StatFormatter.DEFAULT);

    private static Stat makeStat(String key, StatFormatter formatter) {
        ResourceLocation id = CreateDangerClose.asResource(key);
        Supplier<ResourceLocation> statId = CUSTOM_STAT.register(key, () -> id);
        Stat stat = new Stat(statId, formatter);
        STATS_TO_BOOTSTRAP.add(stat);
        return stat;
    }

    public static void register(IEventBus modEventBus) {
        CUSTOM_STAT.register(modEventBus);
    }

    /** Once the registries are frozen: makes each stat known to the statistics screen. */
    public static void bootstrap() {
        for (Stat stat : STATS_TO_BOOTSTRAP)
            Stats.CUSTOM.get(stat.identifier().get(), stat.formatter());
        STATS_TO_BOOTSTRAP.clear();
    }

    public record Stat(Supplier<ResourceLocation> identifier, StatFormatter formatter) {
        public void awardTo(Player player) {
            player.awardStat(identifier.get());
        }
    }
}