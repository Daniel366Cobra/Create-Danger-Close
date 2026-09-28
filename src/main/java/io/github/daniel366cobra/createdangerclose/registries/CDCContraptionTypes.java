package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.contraptions.Contraption;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderContraption;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;

import java.util.function.Supplier;

/**
 * Contraption types, registered into Create's contraption type registry the
 * same way Create and CBC do it. Must be initialised from the mod constructor.
 */
public class CDCContraptionTypes {

    public static Holder.Reference<ContraptionType> RANGEFINDER;

    private static Holder.Reference<ContraptionType> register(String name, Supplier<? extends Contraption> factory) {
        // Saved contraptions store the namespaced registry id, so no legacy
        // name mapping is needed (that map exists for pre-registry Create saves).
        return Registry.registerForHolder(CreateBuiltInRegistries.CONTRAPTION_TYPE,
                CreateDangerClose.asResource(name), new ContraptionType(factory));
    }

    public static void init() {
        RANGEFINDER = register("rangefinder", RangefinderContraption::new);
    }
}
