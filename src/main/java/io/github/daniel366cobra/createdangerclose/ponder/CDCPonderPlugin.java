package io.github.daniel366cobra.createdangerclose.ponder;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers this mod's Ponder scenes and tags (Ponder is the library behind
 * Create's in-game "hold W to ponder" tutorials). Added to Ponder in client
 * setup, and in datagen, which turns the scene texts into lang entries.
 */
public class CDCPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return CreateDangerClose.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        CDCPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        CDCPonderTags.register(helper);
    }
}
