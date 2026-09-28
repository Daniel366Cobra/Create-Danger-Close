package io.github.daniel366cobra.createdangerclose;

import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.util.nullness.NonNullSupplier;
import io.github.daniel366cobra.createdangerclose.events.CDCCommonEvents;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlockEntities;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import io.github.daniel366cobra.createdangerclose.registries.CDCContraptionTypes;
import io.github.daniel366cobra.createdangerclose.registries.CDCCreativeTabs;
import io.github.daniel366cobra.createdangerclose.registries.CDCItems;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.RegisterEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(CreateDangerClose.MOD_ID)
public class CreateDangerClose {

    public static final String MOD_ID = "createdangerclose";
    public static final String MOD_NAME = "Create Danger Close";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final NonNullSupplier<CreateRegistrate> REGISTRATE = NonNullSupplier.lazy(() ->
            CreateRegistrate.create(MOD_ID)
                    .defaultCreativeTab((ResourceKey<CreativeModeTab>) null)
                    .setTooltipModifierFactory(item ->
                            new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                                    .andThen(TooltipModifier.mapNull(KineticStats.create(item))))
    );

    public CreateDangerClose(IEventBus eventBus, ModContainer modContainer) {
        // --- 1. REGISTRATE (Handles blocks, items, block entities, etc.) ---
        getRegistrate().addDataGenerator(ProviderType.LANG, CDCLang::registrateLang);
        getRegistrate().registerEventListeners(eventBus);

        // --- 2. FORCE CLASS LOADING (Populates Registrate's internal lists) ---
        CDCBlocks.register();
        CDCItems.register();
        CDCBlockEntities.register();

        // Create's contraption type registry only accepts entries while it is
        // being populated, i.e. during its RegisterEvent (same as CBC).
        eventBus.addListener(RegisterEvent.class, event -> {
            if (event.getRegistryKey().equals(CreateRegistries.CONTRAPTION_TYPE))
                CDCContraptionTypes.init();
        });

        // --- 3. VANILLA DEFERRED REGISTERS (Things Registrate doesn't handle) ---
        CDCCreativeTabs.register(eventBus);
        CDCStats.register(eventBus);

        // --- 4. RUNTIME EVENT HANDLERS (Game events, not mod lifecycle) ---
        NeoForge.EVENT_BUS.register(CDCCommonEvents.class);

        // --- 5. MOD LIFECYCLE EVENT HANDLERS (Mod loading events) ---
        eventBus.register(CDCCommonEvents.ModBusEvents.class);

        // --- 6. CONFIG ---
        modContainer.registerConfig(ModConfig.Type.COMMON, CDCConfig.SPEC);
    }

    public static CreateRegistrate getRegistrate() {
        return REGISTRATE.get();
    }

    public static ResourceLocation asResource(final String path) {
        return ResourceLocation.tryBuild(MOD_ID, path);
    }
}
