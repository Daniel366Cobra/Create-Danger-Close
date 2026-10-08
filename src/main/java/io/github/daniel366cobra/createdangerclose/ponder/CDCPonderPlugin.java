package io.github.daniel366cobra.createdangerclose.ponder;

import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Registers the Ponder scenes and tags; also added in datagen, for the scene lang entries. */
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