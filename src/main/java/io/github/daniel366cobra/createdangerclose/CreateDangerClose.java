package io.github.daniel366cobra.createdangerclose;

import com.mojang.logging.LogUtils;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import io.github.daniel366cobra.createdangerclose.registries.CDCContraptionTypes;
import io.github.daniel366cobra.createdangerclose.registries.CDCCreativeTabs;
import io.github.daniel366cobra.createdangerclose.registries.CDCMenuTypes;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

@Mod(CreateDangerClose.MOD_ID)
public class CreateDangerClose {

    public static final String MOD_ID = "createdangerclose";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final NonNullSupplier<CreateRegistrate> REGISTRATE = NonNullSupplier.lazy(() ->
            CreateRegistrate.create(MOD_ID)
                    .defaultCreativeTab((ResourceKey<CreativeModeTab>) null)
                    .setTooltipModifierFactory(item ->
                            new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                                    .andThen(TooltipModifier.mapNull(KineticStats.create(item))))
    );

    public CreateDangerClose(IEventBus eventBus, ModContainer container) {
        CDCConfig.register(container);
        // Registrate: blocks, items, block entities, menus, and their generated data.
        getRegistrate().addDataGenerator(ProviderType.LANG, CDCLang::registrateLang);
        getRegistrate().registerEventListeners(eventBus);
        // Loading these classes registers their entries with Registrate.
        CDCBlocks.register();
        CDCBlockEntities.register();
        CDCMenuTypes.register();

        // Create's contraption type registry only accepts entries while it is
        // being populated, i.e. during its RegisterEvent (same as CBC).
        eventBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(CreateRegistries.CONTRAPTION_TYPE))
                CDCContraptionTypes.init();
        });

        // Things Registrate does not handle.
        CDCCreativeTabs.register(eventBus);
        CDCStats.register(eventBus);
        // Custom stats only show in the statistics screen once looked up after
        // registration. Load-complete handlers run in parallel, so on the main thread.
        eventBus.addListener(FMLLoadCompleteEvent.class, event -> event.enqueueWork(CDCStats::bootstrap));
    }

    public static CreateRegistrate getRegistrate() {
        return REGISTRATE.get();
    }

    public static ResourceLocation asResource(final String path) {
        return ResourceLocation.tryBuild(MOD_ID, path);
    }
}
