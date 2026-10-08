package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;

import java.util.function.Supplier;

/** Registered into Create's contraption type registry as Create and CBC do; initialized from the mod constructor. */
public class CDCContraptionTypes {

    public static Holder.Reference<ContraptionType> RANGEFINDER;

    private static Holder.Reference<ContraptionType> register(String name, Supplier<? extends Contraption> factory) {
        // Saves store the namespaced id: no legacy name mapping needed.
        return Registry.registerForHolder(CreateBuiltInRegistries.CONTRAPTION_TYPE,
                CreateDangerClose.asResource(name), new ContraptionType(factory));
    }

    public static void init() {
        RANGEFINDER = register("rangefinder", RangefinderContraption::new);
    }
}