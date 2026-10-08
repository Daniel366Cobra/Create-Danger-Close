package io.github.daniel366cobra.createdangerclose.foundation.damage;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.UnaryOperator;

/**
 * A vulnerable part: a box in its own frame, and how to get there from the world.
 *
 * @param id           as the equipment knows it (a lens's local position, ...)
 * @param centerWorld  for blast distances
 * @param worldToLocal through ship pose and contraption rotation, as it stands now
 */
public record DamagePart(Object id, PartKind kind, AABB box, Vec3 centerWorld, UnaryOperator<Vec3> worldToLocal) {

    public enum PartKind {
        /** Glass: cracks, gathers dust. */
        LENS,
        /** Gears and linkages: jostled, broken, seized. */
        MECHANISM
    }
}