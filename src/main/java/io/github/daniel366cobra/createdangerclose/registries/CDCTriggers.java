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

    /** An interface began passing something on, to players close by. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> INTERFACE_LINKED = simple("interface_linked");
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
    /** Looking at faulty equipment up close; also with each repair or fault credited, so it always comes first. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> EQUIPMENT_MALFUNCTION = simple("equipment_malfunction");
    /** A jostled computer or module realigned with a wrench. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> MODULE_REALIGNED = simple("module_realigned");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> COG_REPLACED = simple("cog_replaced");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> MECHANISM_REPLACED = simple("mechanism_replaced");
    /** A seized servo freed: to the honeycomb's user, or with oil piped in, to whoever stands by. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> SERVO_UNSEIZED = simple("servo_unseized");
    /** A table at the largest size the server allows, a map on every block. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> MAP_TABLE_FULL = simple("map_table_full");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> MAP_TABLE_FULL_ZOOMED = simple("map_table_full_zoomed");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> SAFE_ZONE_DRAWN = simple("safe_zone_drawn");
    /** Watching a table as it charts around a rangefinder fix. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> FIX_CHARTED = simple("fix_charted");
    /** Watching a table as another player's held copy of its map charts. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> SPOTTER_CHARTED = simple("spotter_charted");
    // Fire orders: the rest land one time of flight after the order.
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> SALVO_FIRED = simple("salvo_fired");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> DANGER_CLOSE = simple("danger_close");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> OWN_SIDE_SHELLED = simple("own_side_shelled");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> SAFE_ZONE_SHELLED = simple("safe_zone_shelled");
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> VILLAGE_SHELLED = simple("village_shelled");
    /** An Autocannon Control Console set to weapons free. */
    public static final DeferredHolder<CriterionTrigger<?>, SimpleTrigger> WEAPONS_FREE = simple("weapons_free");

    private static DeferredHolder<CriterionTrigger<?>, SimpleTrigger> simple(String name) {
        return TRIGGERS.register(name, SimpleTrigger::new);
    }

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }
}