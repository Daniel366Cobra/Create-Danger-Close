package io.github.daniel366cobra.createdangerclose.ponder;

import com.tterrag.registrate.util.entry.ItemProviderEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.BallisticComputerScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.BattleDamageScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.CannonServoScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.FireMissionConsoleScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.MapTableScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.NavigationConsoleScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.NetworkInterfaceScenes;
import io.github.daniel366cobra.createdangerclose.ponder.scenes.RangefinderScenes;
import io.github.daniel366cobra.createdangerclose.registries.CDCBlocks;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Which item opens which scene. Each scene is a storyboard: a structure file
 * at {@code assets/createdangerclose/ponder/<name>.nbt} (saved with a
 * Structure Block) animated by a script method in {@code ponder/scenes}.
 *
 * <p>Register a scene only once its structure file exists, e.g.:
 * <pre>
 * HELPER.forComponents(CDCBlocks.NETWORK_INTERFACE)
 *         .addStoryBoard("network_interface", NetworkInterfaceScenes::networkInterface, CDCPonderTags.FIRE_CONTROL);
 * </pre>
 */
public final class CDCPonderScenes {

    private CDCPonderScenes() {}

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        PonderSceneRegistrationHelper<ItemProviderEntry<?, ?>> HELPER = helper.withKeyFunction(RegistryEntry::getId);

        HELPER.forComponents(CDCBlocks.NETWORK_INTERFACE)
                .addStoryBoard("network_interface", NetworkInterfaceScenes::networkInterface, CDCPonderTags.FIRE_CONTROL);
        HELPER.forComponents(CDCBlocks.BALLISTIC_COMPUTER, CDCBlocks.DIFFERENTIATOR, CDCBlocks.INTEGRATOR,
                        CDCBlocks.CORRELATOR, CDCBlocks.FUZE_COMPUTER)
                .addStoryBoard("ballistic_computer", BallisticComputerScenes::ballisticComputer, CDCPonderTags.FIRE_CONTROL);
        HELPER.forComponents(CDCBlocks.CANNON_SERVO_INPUT)
                .addStoryBoard("cannon_servo", CannonServoScenes::cannonServo, CDCPonderTags.FIRE_CONTROL);
        HELPER.forComponents(CDCBlocks.RANGEFINDER_BEARING, CDCBlocks.RANGEFINDER_LENS, CDCBlocks.RANGEFINDER_EYEPIECE)
                .addStoryBoard("rangefinder", RangefinderScenes::rangefinder, CDCPonderTags.FIRE_CONTROL)
                .addStoryBoard("rangefinder", RangefinderScenes::rangefinderUpkeep, CDCPonderTags.DAMAGE_AND_MAINTENANCE);
        HELPER.forComponents(CDCBlocks.NAVIGATION_CONSOLE)
                .addStoryBoard("navigation_console", NavigationConsoleScenes::navigationConsole, CDCPonderTags.FIRE_CONTROL);
        HELPER.forComponents(CDCBlocks.FIRE_MISSION_CONSOLE)
                .addStoryBoard("fire_mission_console", FireMissionConsoleScenes::fireMissionConsole, CDCPonderTags.FIRE_CONTROL);
        HELPER.forComponents(CDCBlocks.MAP_TABLE)
                .addStoryBoard("map_table", MapTableScenes::mapTable, CDCPonderTags.FIRE_CONTROL);

        // After each block's own scene: battle damage, repairs and upkeep.
        HELPER.forComponents(CDCBlocks.BALLISTIC_COMPUTER, CDCBlocks.DIFFERENTIATOR, CDCBlocks.INTEGRATOR,
                        CDCBlocks.CORRELATOR, CDCBlocks.FUZE_COMPUTER, CDCBlocks.NETWORK_INTERFACE, CDCBlocks.CANNON_SERVO_INPUT)
                .addStoryBoard("battle_damage", BattleDamageScenes::battleDamage, CDCPonderTags.DAMAGE_AND_MAINTENANCE);
    }
}
