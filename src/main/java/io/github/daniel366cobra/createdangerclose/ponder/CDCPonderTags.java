package io.github.daniel366cobra.createdangerclose.ponder;

import com.tterrag.registrate.util.entry.RegistryEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Categories in the Ponder index. */
public final class CDCPonderTags {

    /** Everything in this mod: sensors, the computer, servos, rangefinders, plotting. */
    public static final ResourceLocation FIRE_CONTROL = CreateDangerClose.asResource("fire_control");

    private CDCPonderTags() {}

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<RegistryEntry<?, ?>> HELPER = helper.withKeyFunction(RegistryEntry::getId);

        helper.registerTag(FIRE_CONTROL)
                .addToIndex()
                .item(CDCBlocks.FIRE_CONTROL_SOLVER.get(), true, false)
                .title("Fire Control")
                .description("Aiming cannons with sensors, rangefinders and a ballistic computer")
                .register();

        // Add each block here once it has a scene.
        HELPER.addToTag(FIRE_CONTROL)
                .add(CDCBlocks.NETWORK_INTERFACE)
                .add(CDCBlocks.FIRE_CONTROL_SOLVER)
                .add(CDCBlocks.DIFFERENTIATOR)
                .add(CDCBlocks.INTEGRATOR)
                .add(CDCBlocks.CORRELATOR)
                .add(CDCBlocks.FUZE_COMPUTER)
                .add(CDCBlocks.CANNON_SERVO_INPUT)
                .add(CDCBlocks.RANGEFINDER_BEARING)
                .add(CDCBlocks.RANGEFINDER_LENS)
                .add(CDCBlocks.RANGEFINDER_EYEPIECE)
                .add(CDCBlocks.NAVIGATION_CONSOLE)
                .add(CDCBlocks.FIRE_MISSION_CONSOLE)
                .add(CDCBlocks.MAP_TABLE);
    }
}
