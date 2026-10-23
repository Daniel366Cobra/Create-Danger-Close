package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.scene.Selection;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/**
 * One servo's cannon side, saved still: the blocks turning the same way as the mount's interface and those
 * turning against it. Like the servo, it runs only while laying its axis and stands still otherwise.
 */
record ServoDrive(Selection withInterface, @Nullable Selection againstInterface, Selection mount, String interfaceTag) {

    // Fast enough to see; a scene's lay takes far less time than the game's would.
    static final float TRAINING_RPM = 32;

    static ServoDrive yaw(Selection withInterface, @Nullable Selection againstInterface, Selection mount) {
        return new ServoDrive(withInterface, againstInterface, mount, "YawInterface");
    }

    static ServoDrive pitch(Selection withInterface, @Nullable Selection againstInterface, Selection mount) {
        return new ServoDrive(withInterface, againstInterface, mount, "PitchInterface");
    }

    /**
     * Laying by {@code deltaDeg}: traverse left, or elevation up, turns the interface forward
     * (CBC turns the yaw the other way round to Ponder). No turn, no drive.
     */
    void lay(CreateSceneBuilder scene, double deltaDeg) {
        set(scene, (float) Math.signum(deltaDeg) * TRAINING_RPM);
    }

    void stop(CreateSceneBuilder scene) {
        set(scene, 0);
    }

    private void set(CreateSceneBuilder scene, float interfaceRpm) {
        scene.world().setKineticSpeed(withInterface, interfaceRpm);
        if (againstInterface != null)
            scene.world().setKineticSpeed(againstInterface, -interfaceRpm);
        // The interfaces are saved inside the mount.
        scene.world().modifyBlockEntityNBT(mount, CannonMountBlockEntity.class,
                nbt -> nbt.getCompound(interfaceTag).putFloat("Speed", interfaceRpm));
    }
}