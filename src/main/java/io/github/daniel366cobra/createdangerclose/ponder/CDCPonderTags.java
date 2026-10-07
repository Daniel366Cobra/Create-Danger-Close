package io.github.daniel366cobra.createdangerclose.ponder;

import com.simibubi.create.AllItems;
import com.tterrag.registrate.util.entry.RegistryEntry;
import io.github.daniel366cobra.createdangerclose.CreateDangerClose;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/** Categories in the Ponder index. */
public final class CDCPonderTags {

    /** Everything in this mod: sensors, the computer, servos, rangefinders, plotting. */
    public static final ResourceLocation FIRE_CONTROL = CreateDangerClose.asResource("fire_control");
    /** Battle damage, repairs and upkeep of the equipment. */
    public static final ResourceLocation DAMAGE_AND_MAINTENANCE = CreateDangerClose.asResource("damage_and_maintenance");

    private CDCPonderTags() {}

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        PonderTagRegistrationHelper<RegistryEntry<?, ?>> HELPER = helper.withKeyFunction(RegistryEntry::getId);

        helper.registerTag(FIRE_CONTROL)
                .addToIndex()
                .item(CDCBlocks.BALLISTIC_COMPUTER.get(), true, false)
                .title("Fire Control")
                .description("Aiming cannons with sensors, rangefinders and a ballistic computer")
                .register();

        helper.registerTag(DAMAGE_AND_MAINTENANCE)
                .addToIndex()
                .item(AllItems.WRENCH.get(), true, false)
                .title("Damage & Maintenance")
                .description("Battle damage, repairs and upkeep of fire control equipment")
                .register();

        // Add each block here once it has a scene.
        HELPER.addToTag(FIRE_CONTROL)
                .add(CDCBlocks.NETWORK_INTERFACE)
                .add(CDCBlocks.BALLISTIC_COMPUTER)
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
                .add(CDCBlocks.AUTOCANNON_CONTROL_CONSOLE)
                .add(CDCBlocks.KINETIC_NETWORK_COUPLER)
                .add(CDCBlocks.MAP_TABLE);

        helper.addToTag(DAMAGE_AND_MAINTENANCE).add(CreateDangerClose.asResource("spindle_oil_bucket"));
        HELPER.addToTag(DAMAGE_AND_MAINTENANCE)
                .add(CDCBlocks.BALLISTIC_COMPUTER)
                .add(CDCBlocks.DIFFERENTIATOR)
                .add(CDCBlocks.INTEGRATOR)
                .add(CDCBlocks.CORRELATOR)
                .add(CDCBlocks.FUZE_COMPUTER)
                .add(CDCBlocks.NETWORK_INTERFACE)
                .add(CDCBlocks.CANNON_SERVO_INPUT)
                .add(CDCBlocks.RANGEFINDER_BEARING)
                .add(CDCBlocks.RANGEFINDER_LENS)
                .add(CDCBlocks.RANGEFINDER_EYEPIECE);
    }
}
