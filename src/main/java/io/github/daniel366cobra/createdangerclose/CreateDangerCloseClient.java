package io.github.daniel366cobra.createdangerclose;

import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderLensItem;
import io.github.daniel366cobra.createdangerclose.config.CDCConfig;
import io.github.daniel366cobra.createdangerclose.ponder.CDCPonderPlugin;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import io.github.daniel366cobra.createdangerclose.registries.CDCPartialModels;
import io.github.daniel366cobra.createdangerclose.registries.CDCSpriteShifts;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client entry point; never loaded on a dedicated server. */
@Mod(value = CreateDangerClose.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateDangerClose.MOD_ID, value = Dist.CLIENT)
public class CreateDangerCloseClient {

    public CreateDangerCloseClient(ModContainer container) {
        // Settings in Create's style of config screen: client (looks) and gameplay (per world).
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, previous) -> new BaseConfigScreen(previous, CreateDangerClose.MOD_ID));
        BaseConfigScreen.setDefaultActionFor(CreateDangerClose.MOD_ID, base -> base
                .withButtonLabels("Client Settings", "Common Settings", "Gameplay Settings")
                .withSpecs(CDCConfig.client().specification, null, CDCConfig.server().specification));
        CDCPartialModels.register();
        // Ponder scenes and tags (Create's in-game tutorials).
        PonderIndex.addPlugin(new CDCPonderPlugin());
        // Connected texture shifts must exist before the block atlas is stitched.
        CDCSpriteShifts.init();
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Lets the lens item model switch to its cracked variant.
        event.enqueueWork(() -> ItemProperties.register(CDCBlocks.RANGEFINDER_LENS.asItem(),
                CreateDangerClose.asResource("cracked"),
                (stack, level, entity, seed) -> RangefinderLensItem.isCracked(stack) ? 1 : 0));
    }
}