package io.github.daniel366cobra.createdangerclose.ponder.scenes;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

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
    // Traverse left and elevation, degrees.
    private double yaw, pitch;
    private double rotX, rotY, rotZ;

    @SafeVarargs
    CannonAim(CreateSceneBuilder scene, ElementLink<WorldSectionElement>... sections) {
        this.scene = scene;
        this.sections = List.of(sections);
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
        double[] angles = anglesFor(toYaw, toPitch);
        for (ElementLink<WorldSectionElement> section : sections)
            scene.world().rotateSection(section, angles[0] - rotX, angles[1] - rotY, angles[2] - rotZ, ticks);
        scene.idle(ticks);
        rotX = angles[0];
        rotY = angles[1];
        rotZ = angles[2];
        yaw = toYaw;
        pitch = toPitch;
    }

    /** Rotation angles (x, y, z), degrees, that give the pose Ry(yaw)·Rz(pitch). */
    private static double[] anglesFor(double yawDeg, double pitchDeg) {
        if (!SWAPPED_ORDER)
            return new double[] {0, yawDeg, pitchDeg};
        double y = Math.toRadians(yawDeg), p = Math.toRadians(pitchDeg);
        double cy = Math.cos(y), sy = Math.sin(y), cp = Math.cos(p), sp = Math.sin(p);
        double r00 = cy * cp, r01 = -cy * sp, r02 = sy;
        double r11 = cp, r21 = sy * sp;
        // Decomposed as Rx(a)·Rz(c)·Ry(b): r01 = -sin c, r21/r11 = tan a, r02/r00 = tan b.
        double a = Math.atan2(r21, r11);
        double c = Math.asin(-r01);
        double b = Math.atan2(r02, r00);
        return new double[] {Math.toDegrees(a), Math.toDegrees(b), Math.toDegrees(c)};
    }

    /** Elevated, then turned left (toward -z) from east. */
    static Vec3 barrelDirection(double traverseDeg, double elevationDeg) {
        double pitch = Math.toRadians(elevationDeg), yaw = Math.toRadians(traverseDeg);
        return new Vec3(Math.cos(pitch) * Math.cos(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.sin(yaw));
    }

    /** As its lever would. */
    static BlockState assembled(BlockState mount) {
        Property<?> property = mount.getBlock().getStateDefinition().getProperty("assembly_powered");
        return property instanceof BooleanProperty powered ? mount.setValue(powered, true) : mount;
    }
}