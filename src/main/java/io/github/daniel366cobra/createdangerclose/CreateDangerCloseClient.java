package io.github.daniel366cobra.createdangerclose;

import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensItem;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import io.github.daniel366cobra.createdangerclose.ponder.CDCPonderPlugin;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.renderer.item.ItemProperties;
import io.github.daniel366cobra.createdangerclose.registries.CDCSpriteShifts;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CreateDangerClose.MOD_ID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID, value = Dist.CLIENT)
public class CreateDangerCloseClient {
    public CreateDangerCloseClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        CDCPartialModels.register();
        // Ponder scenes and tags (Create's in-game tutorials).
        PonderIndex.addPlugin(new CDCPonderPlugin());
        CDCSpriteShifts.init();
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Lets the lens item model switch to its cracked variant.
        event.enqueueWork(() -> ItemProperties.register(CDCBlocks.RANGEFINDER_LENS.asItem(),
                CreateDangerClose.asResource("cracked"),
                (stack, level, entity, seed) -> RangefinderLensItem.isCracked(stack) ? 1 : 0));

        // Some client setup code
        CreateDangerClose.LOGGER.info("HELLO FROM CLIENT SETUP");
        CreateDangerClose.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
