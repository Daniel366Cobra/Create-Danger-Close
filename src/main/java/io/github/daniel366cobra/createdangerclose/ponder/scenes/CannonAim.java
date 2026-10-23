package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a Ponder cannon section like a mount: traverse about the vertical, elevation about the trunnions.
 * Simulated patches the rotation order (X, Z, Y), so angles are worked out for whichever order is in
 * effect, and turns play as short steps.
 */
final class CannonAim {

    private static final int STEP_TICKS = 2;
    private static final boolean SWAPPED_ORDER = ModList.get().isLoaded("simulated");

    private final CreateSceneBuilder scene;
    // Turned alike about one pivot (an autocannon's barrel apart, so it can recoil).
    private final List<ElementLink<WorldSectionElement>> sections;
    // Turned in traverse only, about the same pivot: a turret carrying the guns' cradle.
    private final List<ElementLink<WorldSectionElement>> turret = new ArrayList<>();
    // Barrels facing north elevate about x; facing east, about z.
    private boolean facingNorth;
    // Traverse left and elevation, degrees.
    private double yaw, pitch;
    private double rotX, rotY, rotZ;

    @SafeVarargs
    CannonAim(CreateSceneBuilder scene, ElementLink<WorldSectionElement>... sections) {
        this.scene = scene;
        this.sections = List.of(sections);
    }

    CannonAim facingNorth() {
        facingNorth = true;
        return this;
    }

    @SafeVarargs
    final CannonAim withTurret(ElementLink<WorldSectionElement>... turret) {
        this.turret.addAll(List.of(turret));
        return this;
    }

    double yaw() { return yaw; }
    double pitch() { return pitch; }

    /** At an even rate over {@code ticks}. */
    void turn(double dYaw, double dPitch, int ticks) {
        double fromYaw = yaw, fromPitch = pitch;
        for (int t = STEP_TICKS; t - STEP_TICKS < ticks; t += STEP_TICKS) {
            int step = Math.min(STEP_TICKS, ticks - (t - STEP_TICKS));
            double f = Math.min(1, (double) t / ticks);
            stepTo(fromYaw + dYaw * f, fromPitch + dPitch * f, step);
        }
    }

    private void stepTo(double toYaw, double toPitch, int ticks) {
        double[] angles = anglesFor(toYaw, toPitch, facingNorth);
        for (ElementLink<WorldSectionElement> section : sections)
            scene.world().rotateSection(section, angles[0] - rotX, angles[1] - rotY, angles[2] - rotZ, ticks);
        // A turn about the vertical alone reads the same in either order.
        for (ElementLink<WorldSectionElement> section : turret)
            scene.world().rotateSection(section, 0, toYaw - yaw, 0, ticks);
        scene.idle(ticks);
        rotX = angles[0];
        rotY = angles[1];
        rotZ = angles[2];
        yaw = toYaw;
        pitch = toPitch;
    }

    /** Rotation angles (x, y, z), degrees, that give the pose Ry(yaw)·Rz(pitch), or Ry(yaw)·Rx(pitch) facing north. */
    private static double[] anglesFor(double yawDeg, double pitchDeg, boolean north) {
        if (!SWAPPED_ORDER && !north)
            return new double[] {0, yawDeg, pitchDeg};
        double[][] r = pose(Math.toRadians(yawDeg), Math.toRadians(pitchDeg), north);
        double a, b, c;
        if (SWAPPED_ORDER) {
            // Rx(a)·Rz(c)·Ry(b): r01 = -sin c, r21/r11 = tan a, r02/r00 = tan b.
            a = Math.atan2(r[2][1], r[1][1]);
            c = Math.asin(-r[0][1]);
            b = Math.atan2(r[0][2], r[0][0]);
        } else {
            // Rx(a)·Ry(b)·Rz(c): r02 = sin b, -r12/r22 = tan a, -r01/r00 = tan c.
            a = Math.atan2(-r[1][2], r[2][2]);
            b = Math.asin(r[0][2]);
            c = Math.atan2(-r[0][1], r[0][0]);
        }
        return new double[] {Math.toDegrees(a), Math.toDegrees(b), Math.toDegrees(c)};
    }

    private static double[][] pose(double y, double p, boolean north) {
        double cy = Math.cos(y), sy = Math.sin(y), cp = Math.cos(p), sp = Math.sin(p);
        return north
                ? new double[][] {{cy, sy * sp, sy * cp}, {0, cp, -sp}, {-sy, cy * sp, cy * cp}}
                : new double[][] {{cy * cp, -cy * sp, sy}, {sp, cp, 0}, {-sy * cp, sy * sp, cy}};
    }

    /** Elevated, then turned left (toward -z) from east. */
    static Vec3 barrelDirection(double traverseDeg, double elevationDeg) {
        double pitch = Math.toRadians(elevationDeg), yaw = Math.toRadians(traverseDeg);
        return new Vec3(Math.cos(pitch) * Math.cos(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.sin(yaw));
    }

    /** A point turned with a north-facing gun about {@code pivot}: elevated, then turned left (toward -x). */
    static Vec3 turnedFacingNorth(Vec3 pivot, Vec3 point, double traverseDeg, double elevationDeg) {
        double[][] r = pose(Math.toRadians(traverseDeg), Math.toRadians(elevationDeg), true);
        Vec3 d = point.subtract(pivot);
        return pivot.add(r[0][0] * d.x + r[0][1] * d.y + r[0][2] * d.z,
                r[1][0] * d.x + r[1][1] * d.y + r[1][2] * d.z,
                r[2][0] * d.x + r[2][1] * d.y + r[2][2] * d.z);
    }

    /** As its lever would. */
    static BlockState assembled(BlockState mount) {
        Property<?> property = mount.getBlock().getStateDefinition().getProperty("assembly_powered");
        return property instanceof BooleanProperty powered ? mount.setValue(powered, true) : mount;
    }
}