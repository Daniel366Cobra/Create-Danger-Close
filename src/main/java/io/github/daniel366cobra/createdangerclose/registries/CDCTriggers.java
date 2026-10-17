package io.github.daniel366cobra.createdangerclose.registries;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.foundation.advancement.SimpleTrigger;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Advancement triggers fired from code. */
public class CDCTriggers {

    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, CreateDangerClose.MOD_ID);

    /** A computer saw every kind of module in its bank. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> FULL_BANK = simple("full_bank");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> RANGEFINDER_ASSEMBLED = simple("rangefinder_assembled");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> RANGEFINDER_LOCK = simple("rangefinder_lock");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> RANGEFINDER_LOCK_FAR = simple("rangefinder_lock_far");
    /** A lock on another ship than the rangefinder's own. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> RANGEFINDER_LOCK_SHIP = simple("rangefinder_lock_ship");
    /** Directing or merged with another rangefinder on one target. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> RANGEFINDERS_MERGED = simple("rangefinders_merged");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> LENS_CRACKED = simple("lens_cracked");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> LENS_REPAIRED = simple("lens_repaired");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> LENS_DUSTY = simple("lens_dusty");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> LENS_CAKED = simple("lens_caked");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> LENS_CLEANED = simple("lens_cleaned");

    private static DeferredHolder<CriterionTrigger<?>, SimpleTrigger> simple(String name) {
        return TRIGGERS.register(name, SimpleTrigger::new);
    }

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }
}