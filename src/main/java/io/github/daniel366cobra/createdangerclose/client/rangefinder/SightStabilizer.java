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
 * Holds the sight steady in the world as the ship moves: each tick undoes the ship's turn on the
 * ship-relative look (pitch and roll with a Gimbal Sensor, yaw with a heading sensor). The server
 * takes the rotation from the client, so it converges there too.
 */
final class SightStabilizer {

    @Nullable private static SubLevel lastShip;
    private static final Quaterniond lastOrientation = new Quaterniond();

    private SightStabilizer() {}

    static void tick(@Nullable LocalPlayer player, @Nullable RangefinderBearingBlockEntity bearing) {
        SubLevel ship = RangefinderOptics.operatedShip(player);
        boolean tilt = bearing != null && bearing.isStabilizedTilt();
        boolean yaw = bearing != null && bearing.isStabilizedYaw();
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

        // The ship's turn since last tick, in the world: turn = swing · twist (twist: yaw about the vertical).
        Quaterniond turn = orientation.mul(new Quaterniond(lastOrientation).conjugate(), new Quaterniond());
        lastOrientation.set(orientation);
        Quaterniond twist = new Quaterniond(0, turn.y, 0, turn.w);
        if (twist.lengthSquared() < 1e-12)
            twist.identity();
        else
            twist.normalize();
        Quaterniond swing = turn.mul(new Quaterniond(twist).conjugate(), new Quaterniond());

        // Undo what the sensors allow: turn⁻¹ = twist⁻¹ · swing⁻¹.
        Quaterniond undo = new Quaterniond();
        if (yaw)
            undo.mul(new Quaterniond(twist).conjugate());
        if (tilt)
            undo.mul(new Quaterniond(swing).conjugate());

        Vec3 look = Vec3.directionFromRotation(player.getXRot(), player.getYRot());
        Vector3d world = orientation.transform(new Vector3d(look.x, look.y, look.z));
        undo.transform(world);
        Vector3d local = orientation.transformInverse(world).normalize();

        // Back to the player's rotation, yaw continuous, pitch within the optics' travel.
        float newPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-local.y, -1, 1)));
        float newYaw = (float) Math.toDegrees(Math.atan2(-local.x, local.z));
        float yawStep = Mth.wrapDegrees(newYaw - player.getYRot());
        player.setYRot(player.getYRot() + yawStep);
        player.setXRot(Mth.clamp(newPitch, -RangefinderSight.MAX_ELEVATION_DEG, RangefinderSight.MAX_DEPRESSION_DEG));
    }
}