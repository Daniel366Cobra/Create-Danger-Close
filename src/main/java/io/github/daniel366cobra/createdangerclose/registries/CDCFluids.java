package io.github.daniel366cobra.createdangerclose.registries;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.entry.FluidEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/**
 * Fluids, and the lubricant tags. Spindle oil: the light oil for fast, lightly loaded
 * mechanisms, pressed from wax (see the compacting recipe).
 */
public final class CDCFluids {

    private static final CreateRegistrate REGISTRATE = CreateDangerClose.getRegistrate();

    /** Lubricants proper: a block's worth of lubrication in 250 mB. Ours and TFMG's lubrication oil. */
    public static final TagKey<Fluid> LUBRICANTS = TagKey.create(Registries.FLUID,
            CreateDangerClose.asResource("lubricants"));
    /** Make-do lubricants: a block's worth in 500 mB. Seed and plant oils. */
    public static final TagKey<Fluid> WEAK_LUBRICANTS = TagKey.create(Registries.FLUID,
            CreateDangerClose.asResource("weak_lubricants"));

    public static final FluidEntry<BaseFlowingFluid.Flowing> SPINDLE_OIL = REGISTRATE.standardFluid("spindle_oil")
            .lang("Spindle Oil")
            // Slightly see-through, like water.
            .renderType(() -> RenderType::translucent)
            .properties(b -> b.viscosity(1500).density(900))
            .fluidProperties(p -> p.levelDecreasePerBlock(2)
                    .tickRate(15)
                    .slopeFindDistance(3)
                    .explosionResistance(100f))
            .tag(LUBRICANTS)
            .source(BaseFlowingFluid.Source::new)
            .block()
            .properties(p -> p.mapColor(MapColor.TERRACOTTA_YELLOW))
            .build()
            .bucket()
            .tag(Tags.Items.BUCKETS)
            .build()
            .register();

    static {
        // Other mods' oils, each optional: a missing mod or tag is simply skipped.
        REGISTRATE.addDataGenerator(ProviderType.FLUID_TAGS, tags -> {
            tags.addTag(LUBRICANTS).addOptionalTag(ResourceLocation.fromNamespaceAndPath("c", "lubrication_oil"));
            tags.addTag(WEAK_LUBRICANTS)
                    .addOptionalTag(ResourceLocation.fromNamespaceAndPath("c", "plantoil"))
                    .addOptionalTag(ResourceLocation.fromNamespaceAndPath("forge", "plantoil"));
        });
    }

    private CDCFluids() {}

    /** Forces class loading so the entries are registered. */
    public static void register() {}
}
