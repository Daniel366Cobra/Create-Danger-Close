package io.github.daniel366cobra.createdangerclose.client.rangefinder;

import dev.ryanhcode.sable.sublevel.SubLevel;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderBearingBlockEntity;
import io.github.daniel366cobra.createdangerclose.block.rangefinder.RangefinderOptics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Holds the rangefinder's line of sight steady in the world while the ship moves under it.
 * The operator's look is relative to the ship, so each tick the ship's turn since the last
 * tick is undone on it: its pitch and roll with a Gimbal Sensor on the network, its yaw with
 * a heading sensor. The operator's own turning is left as it is.
 *
 * <p>The server takes the player's rotation from the client and judges steadiness from it, so
 * a stabilised sight converges there too without any server-side change. The ship's pose is
 * Sable's logical one, as the server uses to turn the look into the world.
 */
final class SightStabiliser {

    // The ship's orientation at the last tick, and which ship it was.
    @Nullable private static SubLevel lastShip;
    private static final Quaterniond lastOrientation = new Quaterniond();

    private SightStabiliser() {}

    static void tick(@Nullable LocalPlayer player, @Nullable RangefinderBearingBlockEntity bearing) {
        SubLevel ship = RangefinderOptics.operatedShip(player);
        boolean tilt = bearing != null && bearing.isStabilisedTilt();
        boolean yaw = bearing != null && bearing.isStabilisedYaw();
        if (ship == null || (!tilt && !yaw)) {
            lastShip = null;
            return;
        }
        Quaterniond orientation = new Quaterniond(ship.logicalPose().orientation());
        if (ship != lastShip) {
            lastShip = ship;
            lastOrientation.set(orientation);
            return;
        }

        // The ship's turn since the last tick, in the world: its yaw about the vertical
        // (the twist), and the tilt left over (the swing), turn = swing · twist.
        Quaterniond turn = orientation.mul(new Quaterniond(lastOrientation).conjugate(), new Quaterniond());
        lastOrientation.set(orientation);
        Quaterniond twist = new Quaterniond(0, turn.y, 0, turn.w);
        if (twist.lengthSquared() < 1e-12)
            twist.identity();
        else
            twist.normalize();
        Quaterniond swing = turn.mul(new Quaterniond(twist).conjugate(), new Quaterniond());

        // Undo the parts the sensors allow, in the world: turn⁻¹ = twist⁻¹ · swing⁻¹.
        Quaterniond undo = new Quaterniond();
        if (yaw)
            undo.mul(new Quaterniond(twist).conjugate());
        if (tilt)
            undo.mul(new Quaterniond(swing).conjugate());

        Vec3 look = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        Vector3d world = orientation.transform(new Vector3d(look.x, look.y, look.z));
        undo.transform(world);
        Vector3d local = orientation.transformInverse(world).normalize();

        // Back to the player's rotation (Minecraft: x = -sin yaw · cos pitch, y = -sin pitch,
        // z = cos yaw · cos pitch), keeping yaw continuous and pitch within the optics' travel.
        float newPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-local.y, -1, 1)));
        float newYaw = (float) Math.toDegrees(Math.atan2(-local.x, local.z));
        float yawStep = Mth.wrapDegrees(newYaw - player.getYRot());
        player.setYRot(player.getYRot() + yawStep);
        player.setXRot(Mth.clamp(newPitch, -RangefinderSight.MAX_ELEVATION_DEG, RangefinderSight.MAX_DEPRESSION_DEG));
    }
}
